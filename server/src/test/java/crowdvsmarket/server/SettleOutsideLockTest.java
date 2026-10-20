package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Identity;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * docs/research/settle_outside_lock_2026-10-18.md: {@code resolveRound} commits the {@code RoundResolved} event and applies
 * it to the engine under the engine lock, and writes the result row, the history and the oracle record after the lock was
 * released. What that must keep: the journal and the engine - not the table - say that a round is resolved (a second
 * resolution or a cancellation is refused by that marker), the next round takes bets as soon as the event is applied, a
 * stop leaves any number of rounds without rows and the next rebuild writes them all once, a write that keeps failing does
 * not stop the game and the rows follow when the failure ends, and a reader of the rows waits for them only a bounded time.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class SettleOutsideLockTest extends LiveGameTestBase {

    @InjectSpy
    RoundResultRepository roundResultsSpy;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void shutDown() {
        Mockito.reset(roundResultsSpy);
        pool.shutdownNow();
    }

    private record Fixture(Market market, RoundWindow betting, String p1, String p2) {
    }

    /** A market whose betting window holds two bets, its running window settled, the prices of the betting window scripted. */
    private Fixture fixture(String id, String symbol) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, symbol, 25, 1);
        prices.setPrice(market, "100.00");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String p1 = signUp(symbol.toLowerCase() + "1");
        String p2 = signUp(symbol.toLowerCase() + "2");
        gameService.placeBet(p1, bet(betting, Enums.Direction.UP, 5, "200.000"), betting.startMs() - 1);
        gameService.placeBet(p2, bet(betting, Enums.Direction.DOWN, 3, "100.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());
        prices.script(market, betting, "100.00", "110.00", "100.00", "110.00");
        return new Fixture(market, betting, p1, p2);
    }

    private long count(String sql, Object... params) {
        return db.read(c -> db.queryOne(c, sql, rs -> rs.getLong("n"), params));
    }

    private long resolvedEvents(Fixture f) {
        return count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'RoundResolved' "
                + "AND payload->>'market' = ? AND payload->>'interval' = ? AND (payload->>'windowStart')::bigint = ?",
                f.market().id(), f.betting().interval().name(), f.betting().startMs());
    }

    private long rows(String table, Fixture f) {
        return count("SELECT COUNT(*) AS n FROM " + table + " WHERE round_id = ?", f.betting().roundId());
    }

    private long oracleSeq() {
        String value = db.read(c -> db.queryOne(c, "SELECT value FROM server_meta WHERE key = 'oracle_seq'",
                rs -> rs.getString("value")));
        return value == null ? 0 : Long.parseLong(value);
    }

    private void assertStoredOnce(Fixture f) {
        assertThat(resolvedEvents(f)).as("resolved events of " + f.betting().roundId()).isEqualTo(1);
        assertThat(rows("round_results", f)).isEqualTo(1);
        assertThat(rows("player_round_history", f)).isEqualTo(2);
        assertThat(rows("oracle_records", f)).isEqualTo(1);
    }

    /** Makes the first write of the settled result wait until {@code release} is counted down; {@code started} says it arrived. */
    private void holdTheWrite(CountDownLatch started, CountDownLatch release) throws Exception {
        doAnswer(inv -> {
            started.countDown();
            assertThat(release.await(60, TimeUnit.SECONDS)).isTrue();
            return inv.callRealMethod();
        }).when(roundResultsSpy).insertSettled(any(Connection.class), anyString(), anyString(), any(Interval.class),
                anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString(), anyString(), anyLong(), any(), any());
    }

    /** Every write of the settled result of {@code roundId} fails, until the spy is reset; other rounds are written. */
    private void failTheWriteOf(String roundId) throws Exception {
        doAnswer(inv -> {
            if (roundId.equals(inv.getArgument(2))) {
                throw new IllegalStateException("injected failure after the event commit");
            }
            return inv.callRealMethod();
        }).when(roundResultsSpy).insertSettled(any(Connection.class), anyString(), anyString(), any(Interval.class),
                anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString(), anyString(), anyLong(), any(), any());
    }

    private void forgetTheRows(Fixture f) {
        db.tx(c -> {
            db.update(c, "DELETE FROM oracle_records WHERE round_id = ?", f.betting().roundId());
            db.update(c, "DELETE FROM player_round_history WHERE round_id = ?", f.betting().roundId());
            db.update(c, "DELETE FROM round_results WHERE round_id = ?", f.betting().roundId());
            return null;
        });
    }

    private void withFastRetry(ThrowingRunnable body) throws Exception {
        gameService.setRowsRetryDelays(20, 100);
        try {
            body.run();
        } finally {
            gameService.setRowsRetryDelays(1000, 60_000);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void aStopLeavingSeveralRoundsWithoutRowsIsRepairedByTheNextRebuildOnce() throws Exception {
        // one after the other: the scheduler tick of a fixture moves the shared clock of the series on
        Fixture f1 = fixture("T_SO_CRASH1", "SOC1");
        assertThat(gameService.resolveRound(f1.betting(), f1.betting().endMs())).isNotNull();
        Fixture f2 = fixture("T_SO_CRASH2", "SOC2");
        assertThat(gameService.resolveRound(f2.betting(), f2.betting().endMs())).isNotNull();
        Fixture f3 = fixture("T_SO_CRASH3", "SOC3");
        assertThat(gameService.resolveRound(f3.betting(), f3.betting().endMs())).isNotNull();
        for (Fixture f : java.util.List.of(f1, f2, f3)) {
            assertStoredOnce(f);
        }
        long seqAfterResolving = oracleSeq();
        // what a process that stopped after the three events were committed leaves: the journal ahead of the rows
        for (Fixture f : java.util.List.of(f1, f2, f3)) {
            forgetTheRows(f);
            assertThat(rows("round_results", f)).isZero();
        }

        rebuild.rebuild(f1.betting().endMs());

        assertThat(rebuild.isReady()).isTrue();
        for (Fixture f : java.util.List.of(f1, f2, f3)) {
            assertStoredOnce(f);
            assertThat(((Rounds.RoundSettled) roundResults.findRoundDto(f.betting().roundId()).orElseThrow()).positions())
                    .hasSize(2);
            assertThat(gameService.hasPendingBets(f.betting())).isFalse();
            assertThat(gameService.publicProfile(f.p1()).history()).hasSize(1);
        }
        assertThat(oracleSeq()).as("F4: the repair writes the numbers the events carry, it issues none").isEqualTo(seqAfterResolving);
        String balance1 = gameService.account(f1.p1()).balance();

        rebuild.rebuild(f1.betting().endMs());   // another restart: nothing is written twice
        for (Fixture f : java.util.List.of(f1, f2, f3)) {
            assertStoredOnce(f);
            assertThat(gameService.resolveRound(f.betting(), f.betting().endMs())).isNull();
        }
        assertThat(oracleSeq()).as("F4: the repair writes the numbers the events carry, it issues none").isEqualTo(seqAfterResolving);
        assertThat(gameService.account(f1.p1()).balance()).isEqualTo(balance1);
    }

    /** A round is a market, an interval and a start: the hourly and the daily round of a market may start at the same
     *  moment, and each has its own {@code RoundResolved}. (The count of a round's events once ignored the interval, and
     *  failed whenever the scheduler had resolved the daily window that started with the hourly one.) */
    @Test
    void theHourlyAndTheDailyRoundThatStartTogetherEachHaveOneResolvedEvent() throws Exception {
        Fixture f = fixture("T_SO_SAMESTART", "SOSS");
        assertThat(gameService.resolveRound(f.betting(), f.betting().endMs())).isNotNull();
        RoundWindow daily = new RoundWindow(f.market().id(), Interval.DAILY, f.betting().startMs(),
                f.betting().endMs() + 60_000);
        prices.script(f.market(), daily, "100.00", "100.00", "100.00", "100.00");
        assertThat(gameService.resolveRound(daily, daily.endMs())).isNotNull();

        assertThat(count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'RoundResolved' "
                + "AND payload->>'market' = ? AND (payload->>'windowStart')::bigint = ?", f.market().id(),
                f.betting().startMs())).isEqualTo(2);
        assertStoredOnce(f);
        assertThat(gameService.resolveRound(f.betting(), f.betting().endMs())).isNull();
        assertThat(gameService.resolveRound(daily, daily.endMs())).isNull();
        assertStoredOnce(f);
    }

    @Test
    void aRebuildThatCannotWriteTheRowsStillGivesAReadyEngineThatKnowsTheRoundIsResolved() throws Exception {
        Fixture f = fixture("T_SO_STUCK", "SOS");
        assertThat(gameService.resolveRound(f.betting(), f.betting().endMs())).as(f.betting().roundId()).isNotNull();
        forgetTheRows(f);
        failTheWriteOf(f.betting().roundId());   // the repair of the rebuild fails too

        withFastRetry(() -> {
            rebuild.rebuild(f.betting().endMs());

            assertThat(rebuild.isReady()).as("a missing projection does not stop the game").isTrue();
            assertThat(rows("round_results", f)).isZero();
            assertThat(gameService.missingRows()).extracting(GameService.MissingRows::roundId)
                    .containsExactly(f.betting().roundId());
            // the journal's marker, restored by the replay, is what refuses it - there is no row to say so
            assertThat(gameService.resolveRound(f.betting(), f.betting().endMs())).isNull();
            assertThatThrownBy(() -> gameService.cancelRoundById(f.betting().roundId(), "operator", f.betting().endMs()))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("already resolved"));
            assertThat(resolvedEvents(f)).isEqualTo(1);
            assertThat(roundProgress.next(f.market().id(), Interval.HOURLY).startMs()).isGreaterThanOrEqualTo(f.betting().endMs());

            Mockito.reset(roundResultsSpy);   // the cause is gone
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20))
                    .until(() -> rows("round_results", f) == 1);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                    .until(() -> gameService.missingRows().isEmpty() && !gameService.rowsPending(f.betting().roundId()));
            assertStoredOnce(f);
        });
    }

    @Test
    void aWriteThatKeepsFailingForOneRoundDoesNotStopTheGameAndTheRowsFollowOnce() throws Exception {
        Fixture bad = fixture("T_SO_BAD", "SOX");
        failTheWriteOf(bad.betting().roundId());
        RoundWindow badNext = RoundClock.next(RoundClock.next(bad.betting()));

        withFastRetry(() -> {
            assertThat(gameService.resolveRound(bad.betting(), bad.betting().endMs())).as("the call returns").isNotNull();
            assertThat(rebuild.isReady()).isTrue();
            assertThat(resolvedEvents(bad)).isEqualTo(1);
            assertThat(rows("round_results", bad)).isZero();

            // other rounds are settled and written, the series of the bad round goes on
            Fixture good = fixture("T_SO_GOOD", "SOY");
            assertThat(gameService.resolveRound(good.betting(), good.betting().endMs())).isNotNull();
            assertStoredOnce(good);
            String late = signUp("sobad");
            assertThat(gameService.placeBet(late, bet(badNext, Enums.Direction.UP, 2, "100.000"),
                    bad.betting().endMs() + 1).roundId()).isEqualTo(badNext.roundId());

            // the retry keeps failing, is visible, and the engine's view is unaffected
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                    .until(() -> !gameService.missingRows().isEmpty() && gameService.missingRows().get(0).failures() >= 3);
            GameService.MissingRows missing = gameService.missingRows().get(0);
            assertThat(missing.roundId()).isEqualTo(bad.betting().roundId());
            assertThat(missing.lastError()).contains("injected failure");
            assertThat(gameService.account(bad.p1()).inPlay()).isEqualTo("0.000");
            rebuild.rebuild(bad.betting().endMs());   // a rebuild meanwhile leaves the round to the retry
            assertThat(rebuild.isReady()).isTrue();
            assertThat(rows("round_results", bad)).isZero();

            // a reader that waits for the rows waits a bounded time and then answers with what there is
            long t = System.nanoTime();
            assertThat(gameService.publicProfile(bad.p1()).history()).isEmpty();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t)).isLessThan(15_000);
            assertThatThrownBy(() -> gameService.round(bad.betting().roundId(), null))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("not stored yet"));

            Mockito.reset(roundResultsSpy);   // the failure ends: the rows appear, once
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20))
                    .until(() -> rows("round_results", bad) == 1);
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(gameService.missingRows()::isEmpty);
            assertStoredOnce(bad);
            assertThat(gameService.publicProfile(bad.p1()).history()).hasSize(1);
        });
    }

    @Test
    void aBetOnTheNextRoundIsAcceptedRightAfterTheEventWhileTheRowsAreStillWrittenAndTheRoundCannotBeResolvedOrCancelledAgain()
            throws Exception {
        Fixture f = fixture("T_SO_BET", "SOB");
        // a second market, on the same windows as the first: its betting window is open for bets while f.betting() is resolved
        Market other = list("T_SO_OTHER", MarketKind.PERP, Category.CRYPTO, "SOO", 25, 1);
        prices.setPrice(other, "100.00");
        alignTo(other, Interval.HOURLY, roundProgress.next(f.market().id(), Interval.HOURLY));
        RoundWindow running = f.betting();   // the cursor: the round that is resolved next
        RoundWindow startedNow = RoundClock.next(running);   // under way as soon as it is resolved: no bets any more
        RoundWindow nextOfSeries = RoundClock.next(startedNow);   // takes bets as soon as the event is applied
        RoundWindow otherNext = roundProgress.bettingWindow(other.id(), Interval.HOURLY);
        String late = signUp("sobet");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdTheWrite(started, release);
        Future<RoundReport> settling = pool.submit(() -> gameService.resolveRound(f.betting(), f.betting().endMs()));
        assertThat(started.await(60, TimeUnit.SECONDS)).as("the write started").isTrue();
        long now = f.betting().endMs() + 1;

        // the write is stuck: the engine lock is free, the cursor has moved, the next round takes bets
        assertThat(roundProgress.next(f.market().id(), Interval.HOURLY)).isEqualTo(startedNow);
        Mockito.clearInvocations(roundResultsSpy);
        Future<Rounds.BetDto> sameSeries = pool.submit(() -> gameService.placeBet(late,
                bet(nextOfSeries, Enums.Direction.UP, 2, "100.000"), now));
        Future<Rounds.BetDto> otherMarket = pool.submit(() -> gameService.placeBet(late,
                bet(otherNext, Enums.Direction.UP, 2, "100.000"), f.betting().endMs() - 1));
        assertThat(sameSeries.get(10, TimeUnit.SECONDS).roundId()).isEqualTo(nextOfSeries.roundId());
        assertThat(otherMarket.get(10, TimeUnit.SECONDS).roundId()).isEqualTo(otherNext.roundId());
        assertThat(settling.isDone()).as("the write is still held").isFalse();
        verify(roundResultsSpy, never()).exists(anyString());   // placeBet does not ask the table
        assertThatThrownBy(() -> gameService.placeBet(late, bet(startedNow, Enums.Direction.UP, 2, "100.000"), now))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getMessage()).contains("not open for betting"));

        // resolved for the game, rows not there yet: neither a second resolution nor a cancellation touches it
        assertThat(gameService.resolveRound(f.betting(), f.betting().endMs())).isNull();
        assertThatThrownBy(() -> gameService.cancelRoundById(f.betting().roundId(), "operator", f.betting().endMs()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("already resolved"));
        assertThat(rows("round_results", f)).isZero();
        assertThat(gameService.hasPendingBets(f.betting())).isFalse();

        release.countDown();
        assertThat(settling.get(60, TimeUnit.SECONDS).playerOutcomes()).hasSize(2);
        assertStoredOnce(f);
        assertThat(gameService.resolveRound(f.betting(), f.betting().endMs())).isNull();
        assertStoredOnce(f);
    }

    @Test
    void aReaderOfTheRowsWaitsForThemAndThenSeesThem() throws Exception {
        Fixture f = fixture("T_SO_READ", "SOR");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdTheWrite(started, release);
        Future<RoundReport> settling = pool.submit(() -> gameService.resolveRound(f.betting(), f.betting().endMs()));
        assertThat(started.await(60, TimeUnit.SECONDS)).isTrue();

        Future<Rounds.RoundDto> round = pool.submit(() -> gameService.round(f.betting().roundId(), null));
        Future<Identity.PublicProfileDto> profile = pool.submit(() -> gameService.publicProfile(f.p1()));
        Thread.sleep(500);
        assertThat(round.isDone()).as("no running-round answer for a round that is already resolved").isFalse();
        assertThat(profile.isDone()).as("no balance without its history").isFalse();

        release.countDown();
        assertThat(round.get(30, TimeUnit.SECONDS)).isInstanceOf(Rounds.RoundSettled.class);
        assertThat(profile.get(30, TimeUnit.SECONDS).history()).hasSize(1);
        assertThat(settling.get(30, TimeUnit.SECONDS)).isNotNull();
        assertStoredOnce(f);
    }
}
