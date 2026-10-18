package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Identity;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
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
 * released. What that must keep: a stop or a failure between the two is repaired by the next rebuild (one row, never two,
 * no history lost), a bet is not held up by the write, the round cannot be resolved or cancelled a second time while it is
 * written, and a reader of the rows sees them only once they are committed.
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
                + "AND payload->>'market' = ? AND (payload->>'windowStart')::bigint = ?", f.market().id(),
                f.betting().startMs());
    }

    private long rows(String table, Fixture f) {
        return count("SELECT COUNT(*) AS n FROM " + table + " WHERE round_id = ?", f.betting().roundId());
    }

    private long oracleSeq() {
        return Long.parseLong(db.read(c -> db.queryOne(c, "SELECT value FROM server_meta WHERE key = 'oracle_seq'",
                rs -> rs.getString("value"))));
    }

    private void assertStoredOnce(Fixture f) {
        assertThat(resolvedEvents(f)).isEqualTo(1);
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

    private void failTheWrite() throws Exception {
        doAnswer(inv -> {   // a new exception each time: the repair's failure is added to the first one as suppressed
            throw new IllegalStateException("injected failure after the event commit");
        }).when(roundResultsSpy).insertSettled(any(Connection.class), anyString(), anyString(), any(Interval.class),
                anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString(), anyString(), anyLong(), any(), any());
    }

    @Test
    void aStopBetweenTheJournalAndTheRowsIsRepairedByTheNextRebuildOnce() throws Exception {
        Fixture f = fixture("T_SO_CRASH", "SOC");
        long seqBefore = oracleSeq();
        failTheWrite(); // the write fails and so does the repair of the rebuild behind it: the state of a process that stopped here

        assertThatThrownBy(() -> gameService.resolveRound(f.betting(), f.betting().endMs()))
                .hasMessageContaining("injected failure after the event commit");

        assertThat(resolvedEvents(f)).as("the event stays: the journal is the truth").isEqualTo(1);
        assertThat(rows("round_results", f)).isZero();
        assertThat(rows("player_round_history", f)).isZero();
        assertThat(rows("oracle_records", f)).isZero();
        assertThat(rebuild.isReady()).as("memory is not served until the rows can be written").isFalse();

        Mockito.reset(roundResultsSpy);   // the cause is gone: "the process starts again"
        rebuild.rebuild(f.betting().endMs());

        assertThat(rebuild.isReady()).isTrue();
        assertStoredOnce(f);
        assertThat(oracleSeq()).isEqualTo(seqBefore + 1);
        assertThat(((Rounds.RoundSettled) roundResults.findRoundDto(f.betting().roundId()).orElseThrow()).positions())
                .hasSize(2);
        assertThat(gameService.hasPendingBets(f.betting())).isFalse();
        assertThat(roundProgress.next(f.market().id(), Interval.HOURLY).startMs()).isGreaterThanOrEqualTo(f.betting().endMs());
        String balance1 = gameService.account(f.p1()).balance();
        assertThat(gameService.publicProfile(f.p1()).history()).hasSize(1);

        rebuild.rebuild(f.betting().endMs());   // another restart: nothing is written twice
        assertStoredOnce(f);
        assertThat(oracleSeq()).isEqualTo(seqBefore + 1);
        assertThat(gameService.account(f.p1()).balance()).isEqualTo(balance1);
        assertThat(gameService.resolveRound(f.betting(), f.betting().endMs())).isNull();
        assertStoredOnce(f);
    }

    @Test
    void aFailedWriteIsRepairedByTheRebuildBehindItAndTheCallStillFails() throws Exception {
        Fixture f = fixture("T_SO_FAIL", "SOF");
        long seqBefore = oracleSeq();
        doThrow(new IllegalStateException("injected failure after the event commit")).doCallRealMethod()
                .when(roundResultsSpy).insertSettled(any(Connection.class), anyString(), anyString(), any(Interval.class),
                        anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), anyString(), anyString(), anyLong(), any(),
                        any());

        assertThatThrownBy(() -> gameService.resolveRound(f.betting(), f.betting().endMs()))
                .hasMessageContaining("injected failure after the event commit");

        assertThat(rebuild.isReady()).as("the rebuild behind the failure wrote the rows").isTrue();
        assertStoredOnce(f);
        assertThat(oracleSeq()).isEqualTo(seqBefore + 1);
        assertThat(gameService.account(f.p1()).inPlay()).isEqualTo("0.000");
        assertThat(gameService.resolveRound(f.betting(), f.betting().endMs())).isNull();
        assertStoredOnce(f);
        assertThat(gameService.publicProfile(f.p2()).history()).hasSize(1);
    }

    @Test
    void aBetIsNotHeldUpWhileTheRowsAreWrittenAndTheRoundCannotBeResolvedOrCancelledAgain() throws Exception {
        Fixture f = fixture("T_SO_BET", "SOB");
        // a second market, on the same windows as the first: its betting window is open for bets while f.betting() is resolved
        Market other = list("T_SO_OTHER", MarketKind.PERP, Category.CRYPTO, "SOO", 25, 1);
        prices.setPrice(other, "100.00");
        alignTo(other, Interval.HOURLY, roundProgress.next(f.market().id(), Interval.HOURLY));
        RoundWindow nextOfSeries = roundProgress.bettingWindow(f.market().id(), Interval.HOURLY);
        RoundWindow otherNext = roundProgress.bettingWindow(other.id(), Interval.HOURLY);
        String late = signUp("sobet");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        holdTheWrite(started, release);
        Future<RoundReport> settling = pool.submit(() -> gameService.resolveRound(f.betting(), f.betting().endMs()));
        assertThat(started.await(60, TimeUnit.SECONDS)).as("the write started").isTrue();

        // the write is stuck: the engine lock is free
        Future<Rounds.BetDto> sameSeries = pool.submit(() -> gameService.placeBet(late,
                bet(nextOfSeries, Enums.Direction.UP, 2, "100.000"), f.betting().endMs() - 1));
        Future<Rounds.BetDto> otherMarket = pool.submit(() -> gameService.placeBet(late,
                bet(otherNext, Enums.Direction.UP, 2, "100.000"), f.betting().endMs() - 1));
        assertThat(sameSeries.get(10, TimeUnit.SECONDS).roundId()).isEqualTo(nextOfSeries.roundId());
        assertThat(otherMarket.get(10, TimeUnit.SECONDS).roundId()).isEqualTo(otherNext.roundId());
        assertThat(settling.isDone()).as("the write is still held").isFalse();

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
