package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.testsupport.EngineLockProbe;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import java.sql.Connection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A call that is queued behind the engine lock meets, when it gets the lock, whatever state the engine is in
 * by then (review of the core, findings 1 and 7a). {@code creditOnChainDeposit} used to check readiness,
 * reserve the deposit coin in {@code deposit_confirmations} — committed — and only then take the lock: queued
 * behind a settlement whose recovery failed too it was refused with 503 and the coin already reserved, the poller's
 * next offer of the coin found it "already credited", and the PLAY a player had sent on chain was never credited.
 * Now it reserves the coin and appends the event in ONE transaction, under the lock, readiness checked with the
 * lock held: a call that gets 503 changed nothing, and the poller's next offer credits the coin.
 *
 * <p>The scenario is the review's: a thread holds the engine's write lock, and while it does the calls under test
 * queue up behind it; then it fails a settlement and its recovery fails as well (the event log cannot be read), and
 * releases the lock. The queued calls get the lock and find the engine not ready — the post-lock re-check in {@code
 * GameService#lockWrite}/{@code lockRead} is the only thing between them and a half-done change.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class DepositCreditUnderLockTest extends LiveGameTestBase {

    @InjectSpy
    EventStore eventStoreSpy;
    @InjectSpy
    EventApplier applierSpy;

    @AfterEach
    void heal() {
        Mockito.reset(eventStoreSpy, applierSpy);
        rebuild.rebuild();
    }

    private long count(String sql, Object... params) {
        return db.read(c -> db.queryOne(c, sql, rs -> rs.getLong("n"), params));
    }

    private long reservations(String coinId) {
        return count("SELECT COUNT(*) AS n FROM deposit_confirmations WHERE coin_id = ?", coinId);
    }

    private long depositEvents(String coinId) {
        return count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'DepositConfirmedOnChain' "
                + "AND payload->>'coinId' = ?", coinId);
    }

    @Test
    void callsQueuedBehindAFailingRecoveryAnswer503AndChangeNothingAndTheCoinIsCreditedWhenOfferedAgain()
            throws Exception {
        String player = signUp("queued");
        RoundWindow betting = roundProgress.bettingWindow("BTC", Interval.HOURLY);
        long betEventsBefore = count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'BetPlaced'");
        EngineLockProbe.Probe depositCall = new EngineLockProbe.Probe();
        EngineLockProbe.Probe betCall = new EngineLockProbe.Probe();
        EngineLockProbe.Probe readCall = new EngineLockProbe.Probe();
        EngineLockProbe.Probe holderIn = new EngineLockProbe.Probe();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<?> holder = pool.submit(() -> gameService.runExclusively(() -> {
                holderIn.arrive();
                // the three calls pass their first readiness check and queue for the lock this thread holds
                EngineLockProbe.awaitQueued(depositCall);
                EngineLockProbe.awaitQueued(betCall);
                EngineLockProbe.awaitQueued(readCall);
                // a settlement of this thread failed, and so did its recovery: the engine is not ready
                doThrow(new IllegalStateException("db flapping")).when(eventStoreSpy).loadAll();
                rebuild.recoverAfterFailure(new IllegalStateException("the settlement failed"));
            }));
            // (the holder has the lock before the others start, or they would get it first)
            await(holderIn);
            Future<Throwable> deposit = pool.submit(() -> {
                depositCall.arrive();
                return catching(() -> gameService.creditOnChainDeposit(player, 1, PlayAmount.ofWholePlay(500),
                        "coin-queued", 7L, System.currentTimeMillis()));
            });
            Future<Throwable> bet = pool.submit(() -> {
                betCall.arrive();
                return catching(() -> gameService.placeBet(player,
                        bet(betting, Enums.Direction.UP, 3, "100.000"), betting.startMs() - 1));
            });
            Future<Throwable> read = pool.submit(() -> {
                readCall.arrive();
                // a read that still takes the engine's read lock (F8: account() is from the views and takes none)
                return catching(() -> gameService.ratings(null));
            });

            holder.get(30, TimeUnit.SECONDS);
            for (Future<Throwable> call : java.util.List.of(deposit, bet, read)) {
                assertThat(call.get(30, TimeUnit.SECONDS)).isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ENGINE_NOT_READY);
                    assertThat(e.httpStatus()).isEqualTo(503);
                });
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rebuild.isReady()).isFalse();
        assertThat(reservations("coin-queued")).as("the call that was refused reserved nothing").isZero();
        assertThat(depositEvents("coin-queued")).isZero();
        assertThat(count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'BetPlaced'"))
                .as("and the bet that was refused was not written").isEqualTo(betEventsBefore);

        // the engine recovers; the deposit poller offers the same coin again on its next pass
        Mockito.reset(eventStoreSpy);
        rebuild.rebuild();
        boolean credited = gameService.creditOnChainDeposit(player, 1, PlayAmount.ofWholePlay(500), "coin-queued", 7L,
                System.currentTimeMillis());

        assertThat(credited).as("the retry credits the coin: the refused call left it unreserved").isTrue();
        assertThat(gameService.account(player).balance()).isEqualTo("10500.000");
        assertThat(reservations("coin-queued")).isEqualTo(1);
        assertThat(depositEvents("coin-queued")).isEqualTo(1);
        assertThat(gameService.creditOnChainDeposit(player, 1, PlayAmount.ofWholePlay(500), "coin-queued", 7L,
                System.currentTimeMillis())).as("and only once").isFalse();
        assertThat(gameService.account(player).balance()).isEqualTo("10500.000");
    }

    @Test
    void aDepositWhoseEventCannotBeWrittenLeavesNoReservationAndTheNextOfferCreditsIt() throws Exception {
        String player = signUp("noevent");
        doThrow(new IllegalStateException("db flapping while writing the event")).when(eventStoreSpy)
                .append(any(Connection.class), any(GameEvent.DepositConfirmedOnChain.class), anyLong());

        assertThatThrownBy(() -> gameService.creditOnChainDeposit(player, 2, PlayAmount.ofWholePlay(250),
                "coin-noevent", 9L, System.currentTimeMillis())).hasMessageContaining("db flapping");

        assertThat(reservations("coin-noevent")).as("the reservation and the event are one transaction").isZero();
        assertThat(rebuild.isReady()).as("memory was never touched: nothing to recover from").isTrue();
        assertThat(gameService.account(player).balance()).isEqualTo("10000.000");

        Mockito.reset(eventStoreSpy);
        assertThat(gameService.creditOnChainDeposit(player, 2, PlayAmount.ofWholePlay(250), "coin-noevent", 9L,
                System.currentTimeMillis())).isTrue();
        assertThat(gameService.account(player).balance()).isEqualTo("10250.000");
        assertThat(reservations("coin-noevent")).isEqualTo(1);
        assertThat(depositEvents("coin-noevent")).isEqualTo(1);
    }

    @Test
    void aCreditThatCannotBeAppliedAfterItsCommitIsHealedByTheRebuildBecauseNoLaterOfferCouldCreditIt() {
        String player = signUp("applyfail");
        doThrow(new IllegalStateException("apply failed")).doCallRealMethod().when(applierSpy)
                .apply(any(GameEvent.DepositConfirmedOnChain.class));

        assertThatThrownBy(() -> gameService.creditOnChainDeposit(player, 4, PlayAmount.ofWholePlay(300),
                "coin-applyfail", 12L, System.currentTimeMillis())).hasMessageContaining("apply failed");

        // committed: the coin is reserved and the credit is in the log — so the poller's next offer finds the coin
        // "already credited" and could never put it right; the engine was rebuilt from the log, which has it
        assertThat(reservations("coin-applyfail")).isEqualTo(1);
        assertThat(depositEvents("coin-applyfail")).isEqualTo(1);
        assertThat(rebuild.isReady()).isTrue();
        assertThat(gameService.account(player).balance()).isEqualTo("10300.000");
        assertThat(gameService.creditOnChainDeposit(player, 4, PlayAmount.ofWholePlay(300), "coin-applyfail", 12L,
                System.currentTimeMillis())).isFalse();
        assertThat(gameService.account(player).balance()).as("credited once").isEqualTo("10300.000");
    }

    @Test
    void aCreditedCoinSurvivesARebuildExactlyOnce() {
        String player = signUp("rebuilt");
        gameService.creditOnChainDeposit(player, 3, PlayAmount.ofWholePlay(120), "coin-rebuilt", 11L,
                System.currentTimeMillis());

        rebuild.rebuild();

        assertThat(gameService.account(player).balance()).isEqualTo("10120.000");
        assertThat(gameService.creditOnChainDeposit(player, 3, PlayAmount.ofWholePlay(120), "coin-rebuilt", 11L,
                System.currentTimeMillis())).isFalse();
    }

    private static void await(EngineLockProbe.Probe probe) {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(15))
                .until(() -> probe.thread() != null);
    }

    private static Throwable catching(Runnable call) {
        try {
            call.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
