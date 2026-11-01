package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.chain.ChainRequestIds;
import crowdvsmarket.server.chain.FakeChainSvc;
import crowdvsmarket.server.dto.Ledger;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.ServerMetaRepository;
import crowdvsmarket.server.persistence.WithdrawalDepositRepository;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import crowdvsmarket.server.chain.ChainSvcClient;

/**
 * E1 and E3: what a withdrawal is called at the chain service, and what happens when it refuses the name
 * (docs/research/markets_architecture.md §4 item 16).
 *
 * <p>The scenario of the live stand: its database is new while the chain service keeps the state of the previous
 * one — its withdrawal queue still holds confirmed requests {@code w-11 … w-15}. The server's ids start again from
 * {@code w-1}, so the new database's eleventh withdrawal would be {@code w-11}, and the chain service answered with
 * the OLD {@code w-11}'s status, {@code confirmed}: the server would mark a payout that never happened as paid. Now
 * every id sent to the chain service is {@code "<engine id>-<epoch tag>"} (8 random hex digits, drawn once per
 * database), the id players see stays the engine's, and a chain service that refuses an id with HTTP 409 gets its
 * withdrawal failed with a plain reason and its PLAY refunded. The chain service is {@link FakeChainSvc}, which has the
 * rules of the real one.
 */
@QuarkusTest
@TestProfile(PlayOnChainTest.OnChainEnabledProfile.class)
class WithdrawalIdNamespaceTest {

    private static final String KEY = "withdrawal_epoch_tag";

    @InjectMock
    ChainSvcClient chainSvcClient;

    @Inject
    GameService gameService;
    @Inject
    WithdrawalPollerService withdrawalPoller;
    @Inject
    ChainRequestIds chainRequestIds;
    @Inject
    ServerMetaRepository serverMeta;
    @Inject
    WithdrawalDepositRepository withdrawalDeposits;
    @Inject
    Db db;

    private FakeChainSvc chain;

    /** A database nobody has ever withdrawn from: no namespace decided, no withdrawal row. */
    @BeforeEach
    void aNewDatabaseAndAChainServiceThatHoldsAnOlderOnesRequests() {
        db.tx(c -> {
            db.update(c, "DELETE FROM server_meta WHERE key = ?", KEY);
            db.update(c, "DELETE FROM withdrawal_requests");
            return null;
        });
        chain = new FakeChainSvc().install(chainSvcClient);
    }

    private String signUpPlayer(String label) {
        String wallet = "txch1ns" + label + UUID.randomUUID().toString().replace("-", "");
        return gameService.registerOrTouchPlayer(wallet, System.currentTimeMillis()).player().playerId();
    }

    /** What the previous database's chain service state looks like for ids {@code w-1 … w-500}: confirmed. */
    private void theOldDatabasesWithdrawalsAreOnTheChainService() {
        for (int i = 1; i <= 500; i++) {
            chain.holdConfirmed("w-" + i, "txch1someoneelse", 7_000_000L + i, "0xoldcoin" + i, 4_000_000L + i);
        }
    }

    private String namespace() {
        return db.read(c -> db.queryOne(c, "SELECT value FROM server_meta WHERE key = ?",
                rs -> rs.getString("value"), KEY));
    }

    @Test
    void aNewDatabasesWithdrawalsGoToTheChainServiceUnderTheEngineIdAndOneTagOfEightHexDigits() {
        String player = signUpPlayer("tag");

        Ledger.WithdrawalRequestDto first = gameService.requestWithdrawal(player, "200.000", System.currentTimeMillis());
        Ledger.WithdrawalRequestDto second = gameService.requestWithdrawal(player, "300.000", System.currentTimeMillis());

        String tag = namespace();
        assertThat(tag).as("8 lowercase hex characters, drawn once for this database").matches("[0-9a-f]{8}");
        assertThat(first.requestId()).as("the id the player sees is the engine's own").matches("w-\\d+");
        assertThat(chain.withdrawalSubmissions()).containsExactly(first.requestId() + "-" + tag,
                second.requestId() + "-" + tag);
        assertThat(chainRequestIds.forWithdrawal(first.requestId())).isEqualTo(first.requestId() + "-" + tag);
        assertThat(first.state().name()).isEqualTo("REQUESTED");
        assertThat(gameService.withdrawal(player, first.requestId()).requestId()).isEqualTo(first.requestId());
        // the status of each is asked under the chain service's id, and a confirmation is applied to the engine's
        chain.confirmWithdrawal(first.requestId() + "-" + tag, "0xcoinnew", 4_900_000L);

        withdrawalPoller.poll();

        assertThat(chain.withdrawalStatusReads()).contains(first.requestId() + "-" + tag)
                .doesNotContain(first.requestId());
        Ledger.WithdrawalRequestDto confirmed = gameService.withdrawal(player, first.requestId());
        assertThat(confirmed.state().name()).isEqualTo("CONFIRMED");
        assertThat(confirmed.coinId()).isEqualTo("0xcoinnew");
        assertThat(gameService.withdrawal(player, second.requestId()).state().name()).as("not confirmed yet")
                .isEqualTo("REQUESTED");
        assertThat(namespace()).as("decided once: it does not change").isEqualTo(tag);
    }

    @Test
    void anOldDatabasesConfirmedWithdrawalWithTheSameEngineIdIsNeverTakenForTheNewOne() {
        theOldDatabasesWithdrawalsAreOnTheChainService();
        String player = signUpPlayer("collision");
        assertThat(chain.withdrawalStatusReads()).isEmpty();
        assertThat(chain.heldWithdrawal("w-11")).as("the old database's request is there under the plain id")
                .hasValueSatisfying(held -> assertThat(held.state()).isEqualTo("confirmed"));

        Ledger.WithdrawalRequestDto requested = gameService.requestWithdrawal(player, "400.000",
                System.currentTimeMillis());
        String balanceAfterTheRequest = gameService.account(player).balance();
        withdrawalPoller.poll();
        withdrawalPoller.poll();

        // whatever engine id it got — even one that the old database used — it is not the old request's
        assertThat(chain.withdrawalSubmissions()).singleElement().isNotEqualTo(requested.requestId());
        Ledger.WithdrawalRequestDto after = gameService.withdrawal(player, requested.requestId());
        assertThat(after.state().name()).as("not paid: the old %s is not this withdrawal", requested.requestId())
                .isEqualTo("REQUESTED");
        assertThat(after.coinId()).isNull();
        assertThat(gameService.account(player).balance()).isEqualTo(balanceAfterTheRequest);
        // and when the chain service really pays it, it is paid
        chain.confirmWithdrawal(chain.withdrawalSubmissions().get(0), "0xnewcoin", 4_900_001L);
        withdrawalPoller.poll();
        assertThat(gameService.withdrawal(player, requested.requestId()).state().name()).isEqualTo("CONFIRMED");
        assertThat(gameService.withdrawal(player, requested.requestId()).coinId()).isEqualTo("0xnewcoin");
    }

    @Test
    void aDatabaseThatAlreadyHasWithdrawalsKeepsThePlainIdsAndSaysSo() {
        String player = signUpPlayer("plain");
        // a withdrawal row from before this rule, submitted under its plain id and still in flight
        db.tx(c -> {
            withdrawalDeposits.insertWithdrawal(c, "w-90001", player, 150_000L, "txch1inflight", 1L);
            return null;
        });
        withdrawalDeposits.markWithdrawalSubmitted("w-90001", 2L);
        chain.confirmWithdrawal("w-90001", "0xinflightcoin", 4_000_001L);

        assertThat(chainRequestIds.forWithdrawal("w-90001")).as("asked about under the id it was submitted under")
                .isEqualTo("w-90001");
        assertThat(namespace()).as("the decision is stored").isEqualTo(ServerMetaRepository.PLAIN_WITHDRAWAL_IDS);
        withdrawalPoller.poll();
        assertThat(chain.withdrawalStatusReads()).contains("w-90001");
        // and a withdrawal made now goes plain too: nothing in flight breaks, nothing is half and half
        Ledger.WithdrawalRequestDto requested = gameService.requestWithdrawal(player, "100.000", System.currentTimeMillis());
        assertThat(chain.withdrawalSubmissions()).containsExactly(requested.requestId());
        assertThat(namespace()).isEqualTo(ServerMetaRepository.PLAIN_WITHDRAWAL_IDS);
    }

    @Test
    void anOperatorCanMoveAPlainDatabaseOverToATagByHandAndEveryIdAfterThatCarriesIt() {
        String player = signUpPlayer("manual");
        db.tx(c -> {
            withdrawalDeposits.insertWithdrawal(c, "w-90003", player, 150_000L, "txch1done", 1L);
            withdrawalDeposits.markWithdrawalConfirmedOnChain(c, "w-90003", "0xdone", 1L, 2L);   // nothing in flight
            return null;
        });
        assertThat(chainRequestIds.forWithdrawal("w-90003")).isEqualTo("w-90003");
        assertThat(namespace()).isEqualTo(ServerMetaRepository.PLAIN_WITHDRAWAL_IDS);

        db.tx(c -> db.update(c, "UPDATE server_meta SET value = ? WHERE key = ?", "0a1b2c3d", KEY));
        Ledger.WithdrawalRequestDto requested = gameService.requestWithdrawal(player, "100.000", System.currentTimeMillis());

        assertThat(chain.withdrawalSubmissions()).containsExactly(requested.requestId() + "-0a1b2c3d");
        assertThat(chainRequestIds.forWithdrawal("w-5")).isEqualTo("w-5-0a1b2c3d");
    }

    @Test
    void theNamespaceIsDecidedBeforeTheFirstRowSoTheFirstWithdrawalOfANewDatabaseDoesNotTurnItIntoAnOldOne() {
        assertThat(namespace()).isNull();
        String player = signUpPlayer("first");

        gameService.requestWithdrawal(player, "100.000", System.currentTimeMillis());

        assertThat(namespace()).isNotEqualTo(ServerMetaRepository.PLAIN_WITHDRAWAL_IDS).matches("[0-9a-f]{8}");
    }

    // ------------------------------------------------------------------------------------------
    // E3: HTTP 409
    // ------------------------------------------------------------------------------------------

    private static final class Records extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    /** A database whose withdrawals keep plain ids (it had one before) against the stand's chain service, which holds
     *  the older database's {@code w-N} without the recipient and the amount: the same id is refused for any request. */
    private String aPlainDatabaseAgainstAChainServiceWithLegacyEntries(String label) {
        String player = signUpPlayer(label);
        db.tx(c -> {
            withdrawalDeposits.insertWithdrawal(c, "w-90002", player, 120_000L, "txch1earlier", 1L);
            withdrawalDeposits.markWithdrawalConfirmedOnChain(c, "w-90002", "0xearlier", 1L, 2L);
            return null;
        });
        chainRequestIds.ensureNamespace(); // a row exists: plain
        assertThat(namespace()).isEqualTo(ServerMetaRepository.PLAIN_WITHDRAWAL_IDS);
        for (int i = 1; i <= 500; i++) {
            chain.holdLegacyConfirmed("w-" + i, "0xoldcoin" + i, 4_000_000L + i);
        }
        return player;
    }

    @Test
    void aWithdrawalTheChainServiceRefusesAsAConflictIsFailedWithAPlainReasonAndItsPlayIsReturned() {
        String player = aPlainDatabaseAgainstAChainServiceWithLegacyEntries("conflict");
        String balanceBefore = gameService.account(player).balance();
        java.util.logging.Logger logger = java.util.logging.Logger
                .getLogger("crowdvsmarket.server.service.WithdrawalSubmitter");
        Records log = new Records();
        logger.addHandler(log);
        Ledger.WithdrawalRequestDto requested;
        try {
            requested = gameService.requestWithdrawal(player, "500.000", System.currentTimeMillis());
        } finally {
            logger.removeHandler(log);
        }

        Ledger.WithdrawalRequestDto failed = gameService.withdrawal(player, requested.requestId());
        assertThat(failed.state().name()).as("failed, not paid").isEqualTo("FAILED");
        assertThat(failed.failureReason()).as("a plain reason").isNotBlank().doesNotContain("requestId")
                .contains("PLAY is back");
        assertThat(failed.coinId()).isNull();
        assertThat(gameService.account(player).balance()).as("the PLAY went back through the failure path")
                .isEqualTo(balanceBefore);
        assertThat(log.records).filteredOn(r -> r.getLevel().intValue() >= Level.SEVERE.intValue())
                .as("an ERROR says what the chain service said").singleElement().satisfies(r -> {
                    assertThat(r.getMessage()).contains("refused withdrawal", requested.requestId());
                });
        // nothing resubmits a withdrawal that is failed
        int submissions = chain.withdrawalSubmissions().size();
        withdrawalPoller.poll();
        assertThat(chain.withdrawalSubmissions()).hasSize(submissions);
    }

    @Test
    void aConflictAnsweredToTheRetryOfAWithdrawalThatCouldNotBeSubmittedFailsItToo() {
        String player = aPlainDatabaseAgainstAChainServiceWithLegacyEntries("retry409");
        String balanceBefore = gameService.account(player).balance();
        chain.down = true;   // the chain service does not answer the first submission
        Ledger.WithdrawalRequestDto requested = gameService.requestWithdrawal(player, "250.000",
                System.currentTimeMillis());
        assertThat(gameService.withdrawal(player, requested.requestId()).state().name()).isEqualTo("REQUESTED");
        assertThat(gameService.account(player).balance()).as("escrowed").isNotEqualTo(balanceBefore);
        chain.down = false;

        withdrawalPoller.poll();   // the retry gets the 409

        assertThat(gameService.withdrawal(player, requested.requestId()).state().name()).isEqualTo("FAILED");
        assertThat(gameService.account(player).balance()).isEqualTo(balanceBefore);
    }

    @Test
    void theSameRequestWithTheSameContentIsARepeatAndNotAConflict() {
        String player = signUpPlayer("repeat");
        Ledger.WithdrawalRequestDto requested = gameService.requestWithdrawal(player, "100.000",
                System.currentTimeMillis());
        String chainId = chain.withdrawalSubmissions().get(0);

        ChainSvcClient.PlayWithdrawalSubmitResult again = chain.submitPlayWithdrawal(chainId,
                gameService.withdrawal(player, requested.requestId()).toAddress(), 100_000L);
        ChainSvcClient.PlayWithdrawalSubmitResult different = chain.submitPlayWithdrawal(chainId,
                gameService.withdrawal(player, requested.requestId()).toAddress(), 100_001L);

        assertThat(again.accepted()).isTrue();
        assertThat(again.isConflict()).isFalse();
        assertThat(different.accepted()).isFalse();
        assertThat(different.isConflict()).isTrue();
        assertThat(different.conflict()).contains("different recipient or amount");
    }
}
