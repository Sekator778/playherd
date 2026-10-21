package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.WithdrawalDepositRepository;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Р-107: the row of a withdrawal request is written after the request's event is durable in the journal. A failure of that write does not
 * fail the request (the journal is the truth, the PLAY is escrowed): the caller still gets its answer, and the rebuild writes any row the
 * table lacks from the engine.
 */
@QuarkusTest
@TestProfile(AdminReverseWithdrawalTest.WithdrawalEnabledProfile.class)
class SidecarRowsTest {

    @InjectMock
    ChainSvcClient chainSvcClient;
    @InjectSpy
    WithdrawalDepositRepository withdrawalRows;
    @Inject
    GameService gameService;
    @Inject
    EngineRebuildService rebuild;
    @Inject
    Db db;

    @AfterEach
    void healTheSpy() {
        Mockito.reset(withdrawalRows);
    }

    private String signUp(String label) {
        String wallet = "txch1sidecar" + label + UUID.randomUUID().toString().replace("-", "");
        return gameService.registerOrTouchPlayer(wallet, System.currentTimeMillis()).player().playerId();
    }

    private long rows(String requestId) {
        Long n = db.read(c -> db.queryOne(c, "SELECT COUNT(*) FROM withdrawal_requests WHERE request_id = ?", rs -> rs.getLong(1), requestId));
        return n == null ? 0 : n;
    }

    @Test
    void aWithdrawalRowThatTheRebuildFindsMissingIsWrittenFromTheEngine() {
        String player = signUp("rows");
        var request = gameService.requestWithdrawal(player, "500.000", System.currentTimeMillis());
        assertThat(rows(request.requestId())).isEqualTo(1);
        db.tx(c -> db.update(c, "DELETE FROM withdrawal_requests WHERE request_id = ?", request.requestId())); // a stop after the event, before the row

        rebuild.rebuild();

        assertThat(rows(request.requestId())).as("written again from the engine").isEqualTo(1);
        String state = db.read(c -> db.queryOne(c, "SELECT state FROM withdrawal_requests WHERE request_id = ?", rs -> rs.getString(1), request.requestId()));
        assertThat(state).isEqualTo("REQUESTED");
        long amount = db.read(c -> db.queryOne(c, "SELECT amount_mojos FROM withdrawal_requests WHERE request_id = ?", rs -> rs.getLong(1), request.requestId()));
        assertThat(amount).isEqualTo(500_000L);
    }

    @Test
    void aFailedRowWriteAfterTheJournalDoesNotFailTheRequest() throws Exception {
        String player = signUp("fail");
        String before = gameService.account(player).balance();
        doThrow(new IllegalStateException("db flapping while writing the row")).when(withdrawalRows)
                .insertWithdrawal(any(Connection.class), anyString(), anyString(), anyLong(), anyString(), anyLong());

        var request = gameService.requestWithdrawal(player, "500.000", System.currentTimeMillis());

        assertThat(request.requestId()).isNotBlank();
        assertThat(gameService.account(player).balance()).as("the PLAY is escrowed: the event is in the journal").isNotEqualTo(before);
        assertThat(rows(request.requestId())).as("the caller needed the row and it was written by the call itself").isEqualTo(1);
        Mockito.reset(withdrawalRows);
        rebuild.rebuild();
        assertThat(rows(request.requestId())).isEqualTo(1);
        assertThat(gameService.account(player).balance()).isNotEqualTo(before);
    }
}
