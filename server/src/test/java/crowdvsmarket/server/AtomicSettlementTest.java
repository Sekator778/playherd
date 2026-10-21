package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.service.OpenBets;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Settlement and cancellation are atomic where the journal is concerned (docs/research/markets_architecture.md §4 п.3): ONE
 * transaction appends the event and applies it to the engine inside it; a cancellation writes its result row in that
 * transaction too. A failure at any point after the event was appended — injected here by making the applier, resp. the
 * cancelled-result insert, throw — rolls everything back and rebuilds the in-memory engine from the log, so afterwards
 * there is no event and the engine is exactly what a fresh rebuild gives; the very same call then succeeds. The rows of a
 * settlement (result, history, oracle record) are written after that transaction, without the engine lock
 * (docs/research/settle_outside_lock_2026-10-18.md): a failure there keeps the event and is repaired, which
 * {@link SettleOutsideLockTest} covers.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class AtomicSettlementTest extends LiveGameTestBase {

    /** The bean the live game applies its events with (a rebuild uses an applier of its own). */
    @InjectSpy
    EventApplier applier;
    @InjectSpy
    RoundResultRepository roundResultsSpy;

    private record Snapshot(String balance, String inPlay, int crowdHistory, String crowdCapital, int holdHistory,
                            int pendingBets, int feedEntries) {
    }

    @Inject
    OpenBets openBets;
    @Inject
    BetFeed betFeed;

    /** The engine itself, as it is at this instant - not the reads, which show the committed state only (F8): this test looks at
     *  what the engine holds in the middle of the settlement's transaction, and at what a rebuild leaves in its place. */
    private Snapshot snapshot(String playerId, Market market, RoundWindow window) {
        var engines = hub.market(market.id());
        return new Snapshot(hub.ledger().balance(playerId).toMoney().toPlainString(),
                openBets.stake(playerId).toMoney().toPlainString(),
                engines.crowd().history().size(), engines.crowd().capital().toPlainString(),
                engines.comparison().holdHistory().size(),
                engines.engine(window.interval()).betBook().betsFor(window.roundId()).size(),
                betFeed.since(window.roundId(), 0L).entries().size());
    }

    private long count(String sql, Object... params) {
        return db.read(c -> db.queryOne(c, sql, rs -> rs.getLong("n"), params));
    }

    private long oracleSeq() {
        return Long.parseLong(db.read(c -> db.queryOne(c, "SELECT value FROM server_meta WHERE key = 'oracle_seq'",
                rs -> rs.getString("value"))));
    }

    private long resolvedEvents(Market market, RoundWindow window) {
        return count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'RoundResolved' "
                + "AND payload->>'market' = ? AND (payload->>'windowStart')::bigint = ?", market.id(),
                window.startMs());
    }

    @Test
    void aFailureAfterTheEventAppendLeavesNoEventNoResultAndTheEngineEqualsAFreshRebuild() throws Exception {
        Market market = list("T_ATOMIC", MarketKind.PERP, Category.CRYPTO, "ATM", 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String p = signUp("atomic");
        gameService.placeBet(p, bet(betting, Enums.Direction.DOWN, 10, "300.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());   // `running` settles empty; `betting` is the running round now
        prices.script(market, betting, "2500.000", "2400.000", "2400.000", "2510.000");

        Snapshot before = snapshot(p, market, betting);
        long seqBefore = oracleSeq();
        assertThat(before.pendingBets()).isEqualTo(1);
        assertThat(before.balance()).isEqualTo("9699.600"); // 300 staked + herd fee 300 * 10 bp = 0.3 + network fee 0.1
        assertThat(before.inPlay()).isEqualTo("300.000");

        // The settlement fails — AFTER the RoundResolved event was appended and applied to the engine inside the
        // transaction (the applier throws once it has applied it). What the engine looks like at that very moment:
        AtomicReference<Snapshot> atFailure = new AtomicReference<>();
        AtomicReference<String> readAtFailure = new AtomicReference<>();
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            if (inv.getArgument(0) instanceof GameEvent.RoundResolved) {
                atFailure.set(snapshot(p, market, betting));
                readAtFailure.set(gameService.account(p).balance());
                throw new RuntimeException("injected failure after the event append");
            }
            return result;
        }).when(applier).apply(any(GameEvent.class));

        assertThatThrownBy(() -> gameService.resolveRound(betting, betting.endMs()))
                .hasMessageContaining("injected failure after the event append");

        // the failure really came after the engine had been changed: the bet had been settled and paid
        assertThat(atFailure.get().pendingBets()).isZero();
        assertThat(atFailure.get().balance()).isEqualTo("10119.600");   // 9699.6 + 420 (10x short, -4%)
        assertThat(atFailure.get().crowdHistory()).isEqualTo(before.crowdHistory() + 1);
        // F8: ... which the reads did not show: they answer from what has committed
        assertThat(readAtFailure.get()).isEqualTo(before.balance());
        // ... and yet nothing of it survived: no event, no result, no oracle record, no burned seq
        assertThat(resolvedEvents(market, betting)).isZero();
        assertThat(roundResults.exists(betting.roundId())).isFalse();
        assertThat(count("SELECT COUNT(*) AS n FROM oracle_records WHERE round_id = ?", betting.roundId())).isZero();
        assertThat(count("SELECT COUNT(*) AS n FROM player_round_history WHERE round_id = ?", betting.roundId()))
                .isZero();
        assertThat(oracleSeq()).isEqualTo(seqBefore);
        // the engine was put back from the log: the bet is pending again, exactly as before
        assertThat(snapshot(p, market, betting)).isEqualTo(before);
        assertThat(roundProgress.next(market.id(), Interval.HOURLY)).isEqualTo(betting);

        // and that IS what a fresh rebuild gives (on the test's clock: the one the cursor was seeded at)
        rebuild.rebuild(betting.endMs());
        assertThat(snapshot(p, market, betting)).isEqualTo(before);

        // the same call now succeeds, once
        Mockito.reset(applier);
        assertThat(gameService.resolveRound(betting, betting.endMs())).isNotNull();
        assertThat(resolvedEvents(market, betting)).isEqualTo(1);
        assertThat(roundResults.exists(betting.roundId())).isTrue();
        assertThat(count("SELECT COUNT(*) AS n FROM oracle_records WHERE round_id = ?", betting.roundId()))
                .isEqualTo(1);
        assertThat(oracleSeq()).isEqualTo(seqBefore + 1);
        assertThat(gameService.account(p).balance()).isEqualTo("10119.600");
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
        // and a second attempt at an already settled round does nothing at all (re-checked under the lock)
        assertThat(gameService.resolveRound(betting, betting.endMs())).isNull();
        assertThat(resolvedEvents(market, betting)).isEqualTo(1);
        assertThat(gameService.account(p).balance()).isEqualTo("10119.600");
    }

    @Test
    void aFailureWhileWritingTheSettledResultKeepsTheEventAndTheRowsFollowOnce() throws Exception {
        Market market = list("T_ATOMIC2", MarketKind.PERP, Category.CRYPTO, "AT2", 10, 0);
        prices.setPrice(market, "100.00");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String p = signUp("atomic2");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 5, "200.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());
        prices.script(market, betting, "100.00", "110.00", "100.00", "110.00");
        doThrow(new IllegalStateException("injected result failure")).doCallRealMethod().when(roundResultsSpy)
                .insertSettled(any(Connection.class), anyString(), anyString(), any(Interval.class), anyLong(), anyLong(),
                        anyLong(), anyLong(), any(), any(), anyString(), anyString(), anyLong(), any(), any());

        gameService.setRowsRetryDelays(20, 100);
        try {
            // the write of the rows fails once: the round is resolved all the same (the journal and the engine say so), the call
            // returns, the game is ready, and the retry in the background writes the rows once
            assertThat(gameService.resolveRound(betting, betting.endMs())).isNotNull();
            assertThat(rebuild.isReady()).isTrue();
            assertThat(resolvedEvents(market, betting)).isEqualTo(1);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20))
                    .until(() -> roundResults.exists(betting.roundId()));
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> gameService.missingRows().isEmpty() && !gameService.rowsPending(betting.roundId()));
        } finally {
            gameService.setRowsRetryDelays(1000, 60_000);
        }
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
        assertThat(((Rounds.RoundSettled) roundResults.findRoundDto(betting.roundId()).orElseThrow()).positions())
                .hasSize(1);

        // the scheduler's next tick settles nothing a second time
        Mockito.reset(roundResultsSpy);
        scheduler.tick(betting.endMs());
        assertThat(resolvedEvents(market, betting)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) AS n FROM player_round_history WHERE round_id = ?", betting.roundId())).isEqualTo(1);
    }

    /**
     * Р-107: the cancellation is the event in the journal; the result row is written after it is durable. A failure of the row does not undo
     * the cancellation (the journal is the truth, its callers are answered), it leaves the round without a row, and the next rebuild
     * writes the row from the event.
     */
    @Test
    void aFailureWhileWritingTheCancelledRowKeepsTheCancellationAndTheRebuildWritesTheRow() throws Exception {
        Market market = list("T_ATOMIC3", MarketKind.PERP, Category.CRYPTO, "AT3", 10, 0);
        prices.setPrice(market, "100.00");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String p = signUp("atomic3");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 5, "250.000"), betting.startMs() - 1);
        Snapshot before = snapshot(p, market, betting);
        assertThat(before.balance()).isEqualTo("9749.775"); // 250 staked + herd fee 250 * 5 bp = 0.125 + network fee 0.1
        doThrow(new IllegalStateException("injected cancel failure")).when(roundResultsSpy).insertCancelled(
                any(Connection.class), anyString(), anyString(), any(Interval.class), anyLong(), anyLong(),
                anyString(), anyLong(), anyInt(), anyString(), anyString(), any());

        Rounds.CancellationDto cancellation = gameService.cancelRoundById(betting.roundId(), "operator test", betting.startMs() - 1);

        assertThat(cancellation.refundedBetCount()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'RoundCancelled' "
                + "AND payload->>'market' = ?", market.id())).as("the event is in the journal").isEqualTo(1);
        assertThat(gameService.account(p).balance()).as("the refund is applied").isEqualTo("10000.000");
        assertThat(roundResults.exists(betting.roundId())).as("the row is what failed").isFalse();

        Mockito.reset(roundResultsSpy);
        rebuild.rebuild();

        assertThat(roundResults.exists(betting.roundId())).as("the rebuild writes the row from the event").isTrue();
        assertThat(gameService.account(p).balance()).isEqualTo("10000.000");
        assertThat(count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'RoundCancelled' "
                + "AND payload->>'market' = ?", market.id())).isEqualTo(1);
        assertThat(count("SELECT refunded_bet_count AS n FROM round_results WHERE round_id = ?", betting.roundId())).isEqualTo(1);
    }
}
