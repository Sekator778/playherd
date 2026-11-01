package crowdvsmarket.engine.round;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.CandleSeries;
import crowdvsmarket.engine.market.Interval;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link PositionSimulator} replayed over <b>multiple</b> candles. Every other test in this
 * module (golden vectors, {@code HistoricalScenarioTest}, {@code FullSweepReplayTest}) opens a
 * position and replays it through exactly one candle — the "hourly round = single 1h candle"
 * case. This class covers the multi-candle path: a daily round replayed on its 24 real hourly
 * candles from {@code hl_btc_1h.csv}, plus hand-built synthetic paths, checking the three things
 * that only show up once a path has more than one candle:
 *
 * <ul>
 *   <li>liquidation is detected on a candle in the <b>middle</b> of the path (not just the first
 *       or last), reported with that candle's own {@code openTimeMs}, not the round's;
 *   <li>{@link PositionSimulator.Outcome.Settled} is returned when no candle in the whole path
 *       ever triggers liquidation;
 *   <li>the same-candle rule (doc section 2.3): when the <b>last</b> candle of the path both
 *       closes the round and triggers liquidation, the outcome is {@code Liquidated}, not
 *       {@code Settled}.
 * </ul>
 */
class PositionSimulatorMultiCandleTest {

    private static final BigDecimal STANDARD_SIZE_BTC = new BigDecimal("0.10000");

    // --- real fixture data: a daily round replayed on its 24 real hourly candles ---
    //
    // 2026-03-02T00:00Z..23:00Z (hl_btc_1h.csv): open 65734.00, and (short, leverage=13) the
    // liquidation price 69916.54 is first breached by the high of the 2026-03-02T16:00Z candle
    // (high 70067.00) — every earlier hour's high stays below it (peak before that is 69597.00
    // at 15:00Z) — i.e. a genuine middle-of-path breach, not an edge one. Verified independently
    // with a python3 decimal recomputation of docs/research/hl_liquidation_math.md section 2.2
    // against the fixture CSV before being hardcoded here.

    private static final long DAY_START_MS = 1_772_409_600_000L; // 2026-03-02T00:00:00Z
    private static final long DAY_LENGTH_MS = 24 * 3_600_000L;
    private static final long EXPECTED_BREACH_CANDLE_OPEN_MS = 1_772_467_200_000L; // 16:00Z, index 16 of 24

    private static List<Candle> loadDayOfHourlyCandles(long dayStartMs) {
        CandleSeries series = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1h.csv", Interval.HOURLY);
        List<Candle> day = series.candles().stream()
                .filter(c -> c.openTimeMs() >= dayStartMs && c.openTimeMs() < dayStartMs + DAY_LENGTH_MS)
                .collect(Collectors.toList());
        if (day.size() != 24) {
            throw new AssertionError("expected 24 contiguous hourly candles for the test day, found " + day.size());
        }
        return day;
    }

    @Test
    void dailyRoundOnRealHourlyCandles_liquidatesOnMiddleHourWithItsOwnCandleTime() {
        List<Candle> path = loadDayOfHourlyCandles(DAY_START_MS);
        BigDecimal entryPrice = path.get(0).open();

        Position position = Position.open(
                "crowd-user", "round-2026-03-02", Direction.DOWN, new Leverage(13), STANDARD_SIZE_BTC, entryPrice);
        PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, path);

        assertThat(outcome).isInstanceOf(PositionSimulator.Outcome.Liquidated.class);
        PositionSimulator.Outcome.Liquidated liquidated = (PositionSimulator.Outcome.Liquidated) outcome;

        // The breach candle is neither the first nor the last of the 24-candle path.
        assertThat(liquidated.candleOpenTimeMs()).isNotEqualTo(path.get(0).openTimeMs());
        assertThat(liquidated.candleOpenTimeMs()).isNotEqualTo(path.get(path.size() - 1).openTimeMs());
        assertThat(liquidated.candleOpenTimeMs()).isEqualTo(EXPECTED_BREACH_CANDLE_OPEN_MS);

        // D-7: payout_on_liquidation = 0, so the loss is the full initial margin.
        assertThat(liquidated.loss()).isEqualByComparingTo("505.65");
    }

    @Test
    void dailyRoundOnRealHourlyCandles_settlesWhenNeverTriggeredAcrossWholePath() {
        List<Candle> path = loadDayOfHourlyCandles(DAY_START_MS);
        BigDecimal entryPrice = path.get(0).open();

        // Leverage 2 puts the short liquidation price (97383.70) far above this day's high
        // (70067.00) — never breached across all 24 candles.
        Position position = Position.open(
                "crowd-user", "round-2026-03-02-calm", Direction.DOWN, new Leverage(2), STANDARD_SIZE_BTC, entryPrice);
        PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, path);

        assertThat(outcome).isInstanceOf(PositionSimulator.Outcome.Settled.class);
        PositionSimulator.Outcome.Settled settled = (PositionSimulator.Outcome.Settled) outcome;

        Candle last = path.get(path.size() - 1);
        assertThat(settled.closePrice()).isEqualByComparingTo(last.close());
        assertThat(settled.closePrice()).isEqualByComparingTo("68774.00");
        assertThat(settled.pnl()).isEqualByComparingTo("-304.00");
    }

    // --- synthetic multi-candle paths ---
    //
    // entry=50000.00, leverage=10 reuses the golden vectors (docs/research/hl_liquidation_math.md
    // section 3): long liq_price=45569.62 (isolated_margin=500.00), short liq_price=54320.99
    // (isolated_margin=500.00) — both already verified against the spec by
    // LiquidationCalculatorGoldenVectorTest, so these synthetic candles are built directly
    // against those known thresholds rather than re-deriving them here.

    private static final BigDecimal ENTRY_PRICE = new BigDecimal("50000.00");
    private static final Leverage LEVERAGE_10 = new Leverage(10);

    private static Candle candle(long openTimeMs, String open, String high, String low, String close) {
        return new Candle(openTimeMs, openTimeMs + 3_600_000L - 1,
                new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close),
                BigDecimal.ZERO, 0L);
    }

    @Test
    void syntheticPath_liquidatesOnMiddleCandleWithItsOwnCandleTime() {
        // Long liq_price = 45569.62. Only candle index 2 (of 5) dips below it.
        List<Candle> path = List.of(
                candle(1_000L, "50000.00", "50200.00", "49800.00", "50100.00"),
                candle(2_000L, "50100.00", "50300.00", "48000.00", "49000.00"),
                candle(3_000L, "49000.00", "49200.00", "45000.00", "45500.00"), // breach: low <= 45569.62
                candle(4_000L, "45500.00", "46000.00", "45100.00", "45800.00"),
                candle(5_000L, "45800.00", "46200.00", "45400.00", "46000.00"));

        Position position = Position.open(
                "u", "r", Direction.UP, LEVERAGE_10, STANDARD_SIZE_BTC, ENTRY_PRICE);
        PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, path);

        assertThat(outcome).isInstanceOf(PositionSimulator.Outcome.Liquidated.class);
        PositionSimulator.Outcome.Liquidated liquidated = (PositionSimulator.Outcome.Liquidated) outcome;
        assertThat(liquidated.candleOpenTimeMs()).isEqualTo(3_000L);
        assertThat(liquidated.loss()).isEqualByComparingTo("500.00"); // D-7: full initial margin
    }

    @Test
    void syntheticPath_settlesWhenLiquidationNeverTriggered() {
        // Long liq_price = 45569.62. Every candle's low stays well above it.
        List<Candle> path = List.of(
                candle(1_000L, "50000.00", "50200.00", "49800.00", "50100.00"),
                candle(2_000L, "50100.00", "50400.00", "49900.00", "50300.00"),
                candle(3_000L, "50300.00", "50600.00", "50000.00", "50500.00"),
                candle(4_000L, "50500.00", "50900.00", "50200.00", "50800.00"));

        Position position = Position.open(
                "u", "r", Direction.UP, LEVERAGE_10, STANDARD_SIZE_BTC, ENTRY_PRICE);
        PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, path);

        assertThat(outcome).isInstanceOf(PositionSimulator.Outcome.Settled.class);
        PositionSimulator.Outcome.Settled settled = (PositionSimulator.Outcome.Settled) outcome;
        assertThat(settled.closePrice()).isEqualByComparingTo("50800.00");
        assertThat(settled.pnl()).isEqualByComparingTo("80.00");
    }

    @Test
    void syntheticPath_sameCandlePrecedence_liquidationOnTheClosingCandleWinsOverSettlement() {
        // Short liq_price = 54320.99. Only the last candle of the path (which also closes the
        // round) breaches it — doc section 2.3's same-candle rule: liquidation wins.
        List<Candle> path = List.of(
                candle(1_000L, "50000.00", "50200.00", "49800.00", "50100.00"),
                candle(2_000L, "50100.00", "50600.00", "49900.00", "50500.00"),
                candle(3_000L, "50500.00", "55000.00", "50300.00", "54500.00")); // breach: high >= 54320.99

        Position position = Position.open(
                "u", "r", Direction.DOWN, LEVERAGE_10, STANDARD_SIZE_BTC, ENTRY_PRICE);
        PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, path);

        assertThat(outcome).isInstanceOf(PositionSimulator.Outcome.Liquidated.class);
        PositionSimulator.Outcome.Liquidated liquidated = (PositionSimulator.Outcome.Liquidated) outcome;
        assertThat(liquidated.candleOpenTimeMs()).isEqualTo(3_000L);
        assertThat(liquidated.loss()).isEqualByComparingTo("500.00"); // D-7: full initial margin
    }
}
