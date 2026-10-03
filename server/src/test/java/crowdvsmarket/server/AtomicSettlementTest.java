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
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Settlement and cancellation are atomic (docs/research/markets_architecture.md §4 п.3): ONE
 * transaction appends the event, writes the result rows and the oracle record, and applies the event
 * to the engine inside it. A failure at any point after the event was appended — injected here by
 * making the oracle insert, resp. the cancelled-result insert, throw — rolls everything back and
 * rebuilds the in-memory engine from the log, so afterwards there is neither event nor result and the
 * engine is exactly what a fresh rebuild gives; the very same call then succeeds. (Before, the event
 * and the result were separate transactions: a failure between them left a settled round without a
 * result row, which the next tick settled a second time.)
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class AtomicSettlementTest extends LiveGameTestBase {

    @InjectSpy
    OracleRecordRepository oracleRecords;
    @InjectSpy
    RoundResultRepository roundResultsSpy;

    private record Snapshot(String balance, String inPlay, int crowdHistory, String crowdCapital, int holdHistory,
                            int pendingBets, int feedEntries) {
    }

    private Snapshot snapshot(String playerId, Market market, RoundWindow window) {
        var engines = hub.market(market.id());
        return new Snapshot(gameService.account(playerId).balance(), gameService.account(playerId).inPlay(),
                engines.crowd().history().size(), engines.crowd().capital().toPlainString(),
                engines.comparison().holdHistory().size(),
                engines.engine(window.interval()).betBook().betsFor(window.roundId()).size(),
                gameService.betFeed(window.roundId(), null).entries().size());
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
        gameService.placeBet(p, bet(betting, Enums.Direction.DOWN, 20, "300.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());   // `running` settles empty; `betting` is the running round now
        prices.script(market, betting, "2500.000", "2400.000", "2400.000", "2510.000");

        Snapshot before = snapshot(p, market, betting);
        long seqBefore = oracleSeq();
        assertThat(before.pendingBets()).isEqualTo(1);
        assertThat(before.balance()).isEqualTo("9700.000");
        assertThat(before.inPlay()).isEqualTo("300.000");

        // The settlement's oracle insert fails — AFTER the RoundResolved event was appended and applied
        // to the engine inside the transaction. What the engine looks like at that very moment:
        AtomicReference<Snapshot> atFailure = new AtomicReference<>();
        doAnswer(inv -> {
            atFailure.set(snapshot(p, market, betting));
            throw new RuntimeException("injected failure after the event append");
        }).when(oracleRecords).insert(any(Connection.class), any(OracleRecordRepository.NewRecord.class));

        assertThatThrownBy(() -> gameService.resolveRound(betting, betting.endMs()))
                .hasMessageContaining("injected failure after the event append");

        // the failure really came after the engine had been changed: the bet had been settled and paid
        assertThat(atFailure.get().pendingBets()).isZero();
        assertThat(atFailure.get().balance()).isEqualTo("10240.000");   // 9700 + 540 (20x short, -4%)
        assertThat(atFailure.get().crowdHistory()).isEqualTo(before.crowdHistory() + 1);
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
        Mockito.reset(oracleRecords);
        assertThat(gameService.resolveRound(betting, betting.endMs())).isNotNull();
        assertThat(resolvedEvents(market, betting)).isEqualTo(1);
        assertThat(roundResults.exists(betting.roundId())).isTrue();
        assertThat(count("SELECT COUNT(*) AS n FROM oracle_records WHERE round_id = ?", betting.roundId()))
                .isEqualTo(1);
        assertThat(oracleSeq()).isEqualTo(seqBefore + 1);
        assertThat(gameService.account(p).balance()).isEqualTo("10240.000");
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
        // and a second attempt at an already settled round does nothing at all (re-checked under the lock)
        assertThat(gameService.resolveRound(betting, betting.endMs())).isNull();
        assertThat(resolvedEvents(market, betting)).isEqualTo(1);
        assertThat(gameService.account(p).balance()).isEqualTo("10240.000");
    }

    @Test
    void aFailureWhileWritingTheSettledResultRollsBackToo() throws Exception {
        Market market = list("T_ATOMIC2", MarketKind.PERP, Category.CRYPTO, "AT2", 10, 0);
        prices.setPrice(market, "100.00");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String p = signUp("atomic2");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 5, "200.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());
        prices.script(market, betting, "100.00", "110.00", "100.00", "110.00");
        Snapshot before = snapshot(p, market, betting);
        doThrow(new IllegalStateException("injected result failure")).when(roundResultsSpy).insertSettled(
                any(Connection.class), anyString(), anyString(), any(Interval.class), anyLong(), anyLong(), anyLong(),
                anyLong(), any(), any(), anyString(), anyString(), anyLong(), any(), any());

        assertThatThrownBy(() -> gameService.resolveRound(betting, betting.endMs()))
                .hasMessageContaining("injected result failure");

        assertThat(resolvedEvents(market, betting)).isZero();
        assertThat(roundResults.exists(betting.roundId())).isFalse();
        assertThat(snapshot(p, market, betting)).isEqualTo(before);

        // the scheduler simply tries again on its next tick: nothing is settled twice
        Mockito.reset(roundResultsSpy);
        scheduler.tick(betting.endMs());
        assertThat(resolvedEvents(market, betting)).isEqualTo(1);
        assertThat(roundResults.exists(betting.roundId())).isTrue();
        assertThat(((Rounds.RoundSettled) roundResults.findRoundDto(betting.roundId()).orElseThrow()).positions())
                .hasSize(1);
    }

    @Test
    void aFailureWhileCancellingRollsBackTheRefundToo() throws Exception {
        Market market = list("T_ATOMIC3", MarketKind.PERP, Category.CRYPTO, "AT3", 10, 0);
        prices.setPrice(market, "100.00");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String p = signUp("atomic3");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 5, "250.000"), betting.startMs() - 1);
        Snapshot before = snapshot(p, market, betting);
        assertThat(before.balance()).isEqualTo("9750.000");
        doThrow(new IllegalStateException("injected cancel failure")).when(roundResultsSpy).insertCancelled(
                any(Connection.class), anyString(), anyString(), any(Interval.class), anyLong(), anyLong(),
                anyString(), anyLong(), anyInt(), anyString(), anyString(), any());

        assertThatThrownBy(() -> gameService.cancelRoundById(betting.roundId(), "operator test", betting.startMs() - 1))
                .hasMessageContaining("injected cancel failure");

        // the refund that had been applied inside the transaction is gone again, along with the event
        assertThat(count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'RoundCancelled' "
                + "AND payload->>'market' = ?", market.id())).isZero();
        assertThat(roundResults.exists(betting.roundId())).isFalse();
        assertThat(snapshot(p, market, betting)).isEqualTo(before);

        Mockito.reset(roundResultsSpy);
        Rounds.CancellationDto cancellation = gameService.cancelRoundById(betting.roundId(), "operator test",
                betting.startMs() - 1);
        assertThat(cancellation.refundedBetCount()).isEqualTo(1);
        assertThat(gameService.account(p).balance()).isEqualTo("10000.000");
        assertThat(count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'RoundCancelled' "
                + "AND payload->>'market' = ?", market.id())).isEqualTo(1);
    }
}
