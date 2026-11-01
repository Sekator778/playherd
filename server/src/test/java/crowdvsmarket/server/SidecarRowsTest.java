package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * D-107: the row of a withdrawal request, and its terminal state, are written by the caller once the event is durable in the journal - never
 * in the writer's turn. A failure of that write leaves the request recorded (the journal is the truth, the PLAY is escrowed): the rebuild
 * writes any row the table lacks from the engine, and a poller that offers a confirmation or a failure again finds the event recorded and
 * brings the row up to date.
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
    void aRowTheCallerCouldNotWriteLeavesTheRequestEscrowedAndTheRebuildWritesIt() throws Exception {
        String player = signUp("fail");
        String before = gameService.account(player).balance();
        doThrow(new IllegalStateException("db flapping while writing the row")).when(withdrawalRows)
                .ensureWithdrawal(anyString(), anyString(), anyLong(), anyString(), anyLong());

        assertThatThrownBy(() -> gameService.requestWithdrawal(player, "500.000", System.currentTimeMillis()))
                .hasMessageContaining("db flapping");

        assertThat(gameService.account(player).balance()).as("the PLAY is escrowed: the event is in the journal").isNotEqualTo(before);
        Mockito.reset(withdrawalRows);
        String requestId = db.read(c -> db.queryOne(c, "SELECT request_id FROM withdrawal_requests WHERE player_id = ?",
                rs -> rs.getString(1), player));
        assertThat(requestId).as("no row yet").isNull();
        rebuild.rebuild();
        String written = db.read(c -> db.queryOne(c, "SELECT request_id FROM withdrawal_requests WHERE player_id = ?",
                rs -> rs.getString(1), player));
        assertThat(written).as("the rebuild wrote it from the engine").isNotNull();
        assertThat(rows(written)).isEqualTo(1);
        assertThat(gameService.account(player).balance()).isNotEqualTo(before);
    }

    @Test
    void theRowOfAConfirmationThatFailedIsWrittenByTheNextCall() {
        String player = signUp("confirm");
        var request = gameService.requestWithdrawal(player, "500.000", System.currentTimeMillis());
        doThrow(new IllegalStateException("db flapping while marking the row")).doCallRealMethod().when(withdrawalRows)
                .markWithdrawalConfirmedOnChain(anyString(), anyString(), anyLong(), anyLong());

        assertThatThrownBy(() -> gameService.confirmWithdrawalOnChain(request.requestId(), "0xcoin", 77L, System.currentTimeMillis()))
                .hasMessageContaining("db flapping");
        assertThat(state(request.requestId())).as("the event is recorded, the row is behind").isEqualTo("REQUESTED");

        gameService.confirmWithdrawalOnChain(request.requestId(), "0xcoin", 77L, System.currentTimeMillis()); // the poller offers it again

        assertThat(state(request.requestId())).isEqualTo("CONFIRMED");
        long height = db.read(c -> db.queryOne(c, "SELECT height FROM withdrawal_requests WHERE request_id = ?", rs -> rs.getLong(1),
                request.requestId()));
        assertThat(height).isEqualTo(77L);
    }

    @Test
    void theRowOfAFailureThatFailedIsWrittenByTheNextCall() {
        String player = signUp("failed");
        var request = gameService.requestWithdrawal(player, "500.000", System.currentTimeMillis());
        doThrow(new IllegalStateException("db flapping while marking the row")).doCallRealMethod().when(withdrawalRows)
                .markWithdrawalFailed(anyString(), anyString(), anyLong());

        assertThatThrownBy(() -> gameService.failWithdrawalOnChain(request.requestId(), "no route", System.currentTimeMillis()))
                .hasMessageContaining("db flapping");
        assertThat(state(request.requestId())).isEqualTo("REQUESTED");

        gameService.failWithdrawalOnChain(request.requestId(), "no route", System.currentTimeMillis());

        assertThat(state(request.requestId())).isEqualTo("FAILED");
    }

    private String state(String requestId) {
        return db.read(c -> db.queryOne(c, "SELECT state FROM withdrawal_requests WHERE request_id = ?", rs -> rs.getString(1), requestId));
    }
}
