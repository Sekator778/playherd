package crowdvsmarket.engine.round;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.CandleSeries;
import crowdvsmarket.engine.market.Interval;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * For every candle in both fixtures and every leverage 2..40, both sides: checks that
 * {@link PositionSimulator} (which calls {@code crowdvsmarket.engine.margin.LiquidationCalculator})
 * agrees with a second transcription of the same formulas
 * (docs/research/hl_liquidation_math.md section 2.2), written from scratch inside this test class
 * and deliberately not calling any production formula method.
 *
 * <p><b>Honesty note</b>: this is <i>not</i> an independent verification of the formulas
 * themselves — both this class and {@code LiquidationCalculator} are transcriptions of the same
 * spec section, so a mistake in the spec (or a shared misreading of it) would pass both equally.
 * What it does catch is a Java-level transcription bug — a typo, a flipped sign, an off-by-one —
 * present in one of the two implementations but not the other, which a single implementation
 * checked only against golden vectors could hide.
 */
class FullSweepReplayTest {

    private static final BigDecimal STANDARD_SIZE_BTC = new BigDecimal("0.10000");
    private static final int MIN_LEVERAGE = 2;
    private static final int MAX_LEVERAGE = 40;
    private static final int MAX_REPORTED_MISMATCHES = 20;

    // --- second transcription of doc section 2.2, deliberately written separately from
    // crowdvsmarket.engine.margin.LiquidationCalculator (see class javadoc's honesty note) ---

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal MAINTENANCE_MARGIN_RATE = new BigDecimal("0.0125"); // BTC tier0: 1/(2*40)
    private static final BigDecimal ZERO_2DP = new BigDecimal("0.00");

    private static BigDecimal q2(BigDecimal x) {
        return x.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal independentInitialMargin(BigDecimal size, BigDecimal entryPrice, int leverage) {
        return q2(size.multiply(entryPrice).divide(BigDecimal.valueOf(leverage), MC));
    }

    private static BigDecimal independentLiquidationPrice(
            BigDecimal size, BigDecimal entryPrice, int leverage, int sideSign) {
        BigDecimal isolatedMargin = independentInitialMargin(size, entryPrice, leverage);
        BigDecimal sideBd = BigDecimal.valueOf(sideSign);
        BigDecimal numerator = size.multiply(entryPrice).subtract(sideBd.multiply(isolatedMargin));
        BigDecimal denominator = size.multiply(BigDecimal.ONE.subtract(MAINTENANCE_MARGIN_RATE.multiply(sideBd)));
        return q2(numerator.divide(denominator, MC));
    }

    private static BigDecimal independentUnrealizedPnl(BigDecimal size, BigDecimal entryPrice, int sideSign, BigDecimal price) {
        return q2(BigDecimal.valueOf(sideSign).multiply(size).multiply(price.subtract(entryPrice), MC));
    }

    private static BigDecimal independentPayoutAtClose(
            BigDecimal size, BigDecimal entryPrice, int leverage, int sideSign, BigDecimal closePrice) {
        BigDecimal isolatedMargin = independentInitialMargin(size, entryPrice, leverage);
        BigDecimal pnl = independentUnrealizedPnl(size, entryPrice, sideSign, closePrice);
        BigDecimal equity = q2(isolatedMargin.add(pnl));
        return equity.max(ZERO_2DP);
    }

    /** {@code payout_on_liquidation = 0} — product decision D-7 (the decision log). */
    private static BigDecimal independentPayoutOnLiquidation(BigDecimal size, BigDecimal entryPrice) {
        return ZERO_2DP;
    }

    // --- sweep ---

    @Test
    void dailyFixtureFullSweepAgreesWithIndependentRecomputation() {
        sweep(CandleSeries.loadFromClasspath("/fixtures/hl_btc_1d.csv", Interval.DAILY));
    }

    @Test
    void hourlyFixtureFullSweepAgreesWithIndependentRecomputation() {
        sweep(CandleSeries.loadFromClasspath("/fixtures/hl_btc_1h.csv", Interval.HOURLY));
    }

    private void sweep(CandleSeries series) {
        List<String> mismatches = new ArrayList<>();
        long[] totalMismatches = {0};
        long checked = 0;

        for (Candle candle : series.candles()) {
            for (int leverageValue = MIN_LEVERAGE; leverageValue <= MAX_LEVERAGE; leverageValue++) {
                Leverage leverage = new Leverage(leverageValue);
                for (Direction direction : Direction.values()) {
                    int sideSign = direction == Direction.UP ? 1 : -1;
                    checked++;

                    BigDecimal independentLiqPrice = independentLiquidationPrice(
                            STANDARD_SIZE_BTC, candle.open(), leverageValue, sideSign);

                    Position position = Position.open(
                            "sweep-user", "sweep-round", direction, leverage, STANDARD_SIZE_BTC, candle.open());

                    if (position.liquidationPrice().compareTo(independentLiqPrice) != 0) {
                        totalMismatches[0]++;
                        recordMismatch(mismatches, candle, leverageValue, direction,
                                "liquidationPrice production=" + position.liquidationPrice()
                                        + " independent=" + independentLiqPrice);
                        continue;
                    }

                    boolean independentLiquidated = direction == Direction.UP
                            ? candle.low().compareTo(independentLiqPrice) <= 0
                            : candle.high().compareTo(independentLiqPrice) >= 0;

                    PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, List.of(candle));
                    boolean productionLiquidated = outcome instanceof PositionSimulator.Outcome.Liquidated;

                    if (productionLiquidated != independentLiquidated) {
                        totalMismatches[0]++;
                        recordMismatch(mismatches, candle, leverageValue, direction,
                                "liquidated production=" + productionLiquidated + " independent=" + independentLiquidated);
                        continue;
                    }

                    if (independentLiquidated) {
                        BigDecimal independentLoss = independentInitialMargin(STANDARD_SIZE_BTC, candle.open(), leverageValue)
                                .subtract(independentPayoutOnLiquidation(STANDARD_SIZE_BTC, candle.open()));
                        BigDecimal productionLoss = ((PositionSimulator.Outcome.Liquidated) outcome).loss();
                        if (productionLoss.compareTo(independentLoss) != 0) {
                            totalMismatches[0]++;
                            recordMismatch(mismatches, candle, leverageValue, direction,
                                    "loss production=" + productionLoss + " independent=" + independentLoss);
                        }
                    } else {
                        BigDecimal independentPnl = independentPayoutAtClose(
                                STANDARD_SIZE_BTC, candle.open(), leverageValue, sideSign, candle.close())
                                .subtract(independentInitialMargin(STANDARD_SIZE_BTC, candle.open(), leverageValue));
                        BigDecimal productionPnl = ((PositionSimulator.Outcome.Settled) outcome).pnl();
                        if (productionPnl.compareTo(independentPnl) != 0) {
                            totalMismatches[0]++;
                            recordMismatch(mismatches, candle, leverageValue, direction,
                                    "pnl production=" + productionPnl + " independent=" + independentPnl);
                        }
                    }
                }
            }
        }

        assertThat(checked)
                .as("sanity: swept at least one candle x leverage x side combination")
                .isGreaterThan(0);
        assertThat(mismatches)
                .as("%d / %d combinations mismatched (showing up to %d): %s",
                        totalMismatches[0], checked, MAX_REPORTED_MISMATCHES, mismatches)
                .isEmpty();
    }

    private static void recordMismatch(
            List<String> mismatches, Candle candle, int leverage, Direction direction, String detail) {
        if (mismatches.size() < MAX_REPORTED_MISMATCHES) {
            mismatches.add("openTimeMs=" + candle.openTimeMs() + " leverage=" + leverage
                    + " direction=" + direction + " " + detail);
        }
    }
}
