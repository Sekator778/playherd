package crowdvsmarket.engine.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.CandleSeries;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.CandleSeriesRoundPrices;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Small, hand-scripted scenarios exercising {@link GameEngine}'s round lifecycle directly (bet →
 * modify/cancel → round resolution) — the exhaustive month-long replay lives in
 * {@code MonthLongHourlySimulationTest}. */
class GameEngineRoundLifecycleTest {

    private static Candle candle(long openTimeMs, String open, String high, String low, String close) {
        return new Candle(openTimeMs, openTimeMs + 3_600_000L - 1,
                new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close), BigDecimal.ZERO, 0L);
    }

    private static RoundPrices fixedPrices(RoundWindow window, Candle... path) {
        return new RoundPrices() {
            @Override
            public BigDecimal openPrice(RoundWindow w) {
                return path[0].open();
            }

            @Override
            public BigDecimal closePrice(RoundWindow w) {
                return path[path.length - 1].close();
            }

            @Override
            public List<Candle> path(RoundWindow w) {
                return List.of(path);
            }
        };
    }

    @Test
    void oneRoundWithALiquidationAndASettlement() {
        GameEngine engine = new GameEngine();
        RoundWindow window = new RoundWindow(Interval.HOURLY, 0L, 3_600_000L);
        Instant t0 = Instant.ofEpochMilli(0L);

        // alice: 40x long, deep low liquidates her.
        engine.placeBet("alice", window, Direction.UP, new Leverage(40), PlayAmount.ofWholePlay(1_000), t0);
        // bob: 2x long, survives and settles with a profit.
        engine.placeBet("bob", window, Direction.UP, new Leverage(2), PlayAmount.ofWholePlay(1_000), t0);

        RoundPrices prices = fixedPrices(window, candle(0L, "50000.00", "51000.00", "30000.00", "51000.00"));
        RoundReport report = engine.runRound(window, prices);

        assertThat(report.playerOutcomes()).hasSize(2);
        PlayerRoundOutcome aliceOutcome = report.playerOutcomes().stream().filter(o -> o.playerId().equals("alice")).findFirst().orElseThrow();
        PlayerRoundOutcome bobOutcome = report.playerOutcomes().stream().filter(o -> o.playerId().equals("bob")).findFirst().orElseThrow();

        assertThat(aliceOutcome.liquidated()).isTrue();
        assertThat(aliceOutcome.payout()).isEqualByComparingTo("0.00");
        assertThat(engine.ledger().balance("alice")).isEqualTo(LedgerBook.STARTER_PACK.minus(PlayAmount.ofWholePlay(1_000)));

        assertThat(bobOutcome.liquidated()).isFalse();
        assertThat(bobOutcome.payout()).isGreaterThan(new BigDecimal("1000.00"));
        assertThat(engine.ledger().balance("bob"))
                .isEqualTo(LedgerBook.STARTER_PACK.minus(PlayAmount.ofWholePlay(1_000)).plus(PlayAmount.fromMoney(bobOutcome.payout())));

        // Round is fully cleared after resolution.
        assertThat(engine.betBook().betsFor(window.roundId())).isEmpty();
    }

    @Test
    void modifyingABetBeforeStartReplacesItAndRefundsTheOld() {
        GameEngine engine = new GameEngine();
        RoundWindow window = new RoundWindow(Interval.HOURLY, 0L, 3_600_000L);
        Instant t0 = Instant.ofEpochMilli(0L);

        engine.placeBet("alice", window, Direction.UP, new Leverage(10), PlayAmount.ofWholePlay(500), t0);
        assertThat(engine.ledger().balance("alice")).isEqualTo(LedgerBook.STARTER_PACK.minus(PlayAmount.ofWholePlay(500)));

        // Grow the stake well past what would be left after just subtracting the old one --
        // this only works because the old stake is refunded before the new one is reserved.
        engine.placeBet("alice", window, Direction.DOWN, new Leverage(5), PlayAmount.ofWholePlay(9_000), t0);

        assertThat(engine.ledger().balance("alice")).isEqualTo(LedgerBook.STARTER_PACK.minus(PlayAmount.ofWholePlay(9_000)));
        assertThat(engine.betBook().betsFor(window.roundId())).hasSize(1);
        assertThat(engine.betBook().betsFor(window.roundId()).iterator().next().direction()).isEqualTo(Direction.DOWN);
    }

    @Test
    void cancellingABetRefundsAndRemovesItFromTheRound() {
        GameEngine engine = new GameEngine();
        RoundWindow window = new RoundWindow(Interval.HOURLY, 0L, 3_600_000L);
        Instant t0 = Instant.ofEpochMilli(0L);

        engine.placeBet("alice", window, Direction.UP, new Leverage(10), PlayAmount.ofWholePlay(500), t0);
        engine.cancelBet("alice", window, t0);

        assertThat(engine.ledger().balance("alice")).isEqualTo(LedgerBook.STARTER_PACK);
        assertThat(engine.betBook().betsFor(window.roundId())).isEmpty();

        RoundPrices prices = fixedPrices(window, candle(0L, "50000.00", "51000.00", "49000.00", "50500.00"));
        RoundReport report = engine.runRound(window, prices);
        assertThat(report.playerOutcomes()).isEmpty();
        assertThat(report.crowdResult().traded()).isFalse(); // no bets at all this round
    }

    @Test
    void crowdAccountTradesTheMajorityOfTheRoundsActualBets() {
        GameEngine engine = new GameEngine();
        RoundWindow window = new RoundWindow(Interval.HOURLY, 0L, 3_600_000L);
        Instant t0 = Instant.ofEpochMilli(0L);

        engine.placeBet("alice", window, Direction.UP, new Leverage(10), PlayAmount.ofWholePlay(2_000), t0);
        engine.placeBet("bob", window, Direction.DOWN, new Leverage(10), PlayAmount.ofWholePlay(500), t0);

        RoundPrices prices = fixedPrices(window, candle(0L, "50000.00", "50200.00", "49900.00", "50100.00"));
        RoundReport report = engine.runRound(window, prices);

        assertThat(report.crowdResult().traded()).isTrue();
        assertThat(report.crowdResult().direction()).isEqualTo(Direction.UP);
        assertThat(engine.crowdAccount().capital()).isNotEqualByComparingTo(CrowdAccount.STARTING_CAPITAL);
    }

    @Test
    void aFewDailyRoundsOnTheHourlyFixtureWorkTheSameWay() {
        GameEngine engine = new GameEngine(new LedgerBook(), new CrowdAccount());
        CandleSeries hourly = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1h.csv", Interval.HOURLY);
        RoundPrices prices = new CandleSeriesRoundPrices(hourly);

        long dayStart = 1_772_409_600_000L; // 2026-03-02T00:00:00Z, documented in PositionSimulatorMultiCandleTest
        long dayMs = 24 * 3_600_000L;

        for (int day = 0; day < 3; day++) {
            RoundWindow window = new RoundWindow(Interval.DAILY, dayStart + day * dayMs, dayStart + (day + 1) * dayMs);
            Instant now = Instant.ofEpochMilli(window.startMs());
            engine.placeBet("alice", window, Direction.UP, new Leverage(3), PlayAmount.ofWholePlay(200), now);
            engine.placeBet("bob", window, Direction.DOWN, new Leverage(3), PlayAmount.ofWholePlay(200), now);

            RoundReport report = engine.runRound(window, prices);
            assertThat(report.playerOutcomes()).hasSize(2);
        }

        assertThat(engine.ledger().balance("alice").isNegative()).isFalse();
        assertThat(engine.ledger().balance("bob").isNegative()).isFalse();
    }

    @Test
    void placingABetOnAnUnaffordableStakeThrowsAndDoesNotRegisterTheBet() {
        GameEngine engine = new GameEngine();
        RoundWindow window = new RoundWindow(Interval.HOURLY, 0L, 3_600_000L);
        Instant t0 = Instant.ofEpochMilli(0L);

        assertThatThrownBy(() -> engine.placeBet("alice", window, Direction.UP, new Leverage(10), PlayAmount.ofWholePlay(20_000), t0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(engine.betBook().betsFor(window.roundId())).isEmpty();
    }
}
