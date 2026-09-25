package crowdvsmarket.engine.margin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Structural invariants of the liquidation formula (docs/research/hl_liquidation_math.md section
 * 2.2), independent of any single golden vector.
 */
class LiquidationCalculatorInvariantTest {

    private static final BigDecimal SIZE = new BigDecimal("0.10000");
    // Rounding tolerance: liquidationPrice is rounded to 2dp before equityAt uses it, so equity at
    // that rounded price differs from maintenance_margin_required by at most a fraction of a cent
    // (size * half-a-cent price rounding). One cent is a generous, spec-scale tolerance.
    private static final BigDecimal ROUNDING_TOLERANCE = new BigDecimal("0.01");

    @ParameterizedTest
    @ValueSource(strings = {"83451.00", "50000.00", "11574.80", "121615.00", "1000.00"})
    void longLiqPriceBelowEntryBelowShortLiqPrice(String entryPriceStr) {
        BigDecimal entryPrice = new BigDecimal(entryPriceStr);
        for (int leverage = Leverage.MIN; leverage <= Leverage.MAX; leverage++) {
            Leverage lev = new Leverage(leverage);
            BigDecimal longLiq = LiquidationCalculator.liquidationPrice(SIZE, entryPrice, lev, Side.LONG);
            BigDecimal shortLiq = LiquidationCalculator.liquidationPrice(SIZE, entryPrice, lev, Side.SHORT);
            assertThat(longLiq)
                    .as("long liqPx < entry at leverage %d", leverage)
                    .isLessThan(entryPrice);
            assertThat(shortLiq)
                    .as("entry < short liqPx at leverage %d", leverage)
                    .isGreaterThan(entryPrice);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"83451.00", "50000.00", "11574.80"})
    void liqPriceMovesTowardEntryMonotonicallyAsLeverageGrows(String entryPriceStr) {
        BigDecimal entryPrice = new BigDecimal(entryPriceStr);
        BigDecimal previousLong = null;
        BigDecimal previousShort = null;
        for (int leverage = Leverage.MIN; leverage <= Leverage.MAX; leverage++) {
            Leverage lev = new Leverage(leverage);
            BigDecimal longLiq = LiquidationCalculator.liquidationPrice(SIZE, entryPrice, lev, Side.LONG);
            BigDecimal shortLiq = LiquidationCalculator.liquidationPrice(SIZE, entryPrice, lev, Side.SHORT);
            if (previousLong != null) {
                assertThat(longLiq)
                        .as("long liqPx increases toward entry as leverage grows to %d", leverage)
                        .isGreaterThan(previousLong);
                assertThat(shortLiq)
                        .as("short liqPx decreases toward entry as leverage grows to %d", leverage)
                        .isLessThan(previousShort);
            }
            previousLong = longLiq;
            previousShort = shortLiq;
        }
    }

    /**
     * At the liquidation price, equity equals the <b>dynamic</b> maintenance margin evaluated at
     * that same price ({@code Q*liq_price*l}) — this is literally the equation the spec's
     * derivation of {@code liquidation_price} solves (doc section 2.2, "Вывод liquidation_price":
     * {@code isolated_margin + side*Q*(liq_price - P0) = Q*liq_price*l}).
     *
     * <p>This is <b>not</b> the same number as {@link LiquidationCalculator#maintenanceMarginRequired},
     * which is the entry-price-based {@code Q*P0*l} used in the spec's derivation of
     * {@code liquidation_price} and in {@link LiquidationCalculator#marginRatio} (doc section
     * 2.2) — the two coincide only when {@code liq_price == P0}. ({@code payout_on_liquidation}
     * itself is a flat 0 under product decision Р-7, {@code docs/product/decisions.md}, and does
     * not use either figure.) Comparing equity against the wrong (entry-based) figure here
     * previously produced a spurious failure; this test compares against the dynamic,
     * liq-price-based figure the derivation actually establishes.
     */
    @ParameterizedTest
    @ValueSource(strings = {"83451.00", "50000.00", "11574.80", "121615.00"})
    void equityAtLiquidationPriceEqualsDynamicMaintenanceMarginAtThatPrice(String entryPriceStr) {
        BigDecimal entryPrice = new BigDecimal(entryPriceStr);
        for (int leverage = Leverage.MIN; leverage <= Leverage.MAX; leverage++) {
            Leverage lev = new Leverage(leverage);
            for (Side side : Side.values()) {
                BigDecimal liqPrice = LiquidationCalculator.liquidationPrice(SIZE, entryPrice, lev, side);
                BigDecimal equity = LiquidationCalculator.equityAt(SIZE, entryPrice, lev, side, liqPrice);
                BigDecimal dynamicMaintenanceMarginAtLiqPrice = maintenanceMarginAtPrice(SIZE, liqPrice);
                assertThat(equity.subtract(dynamicMaintenanceMarginAtLiqPrice).abs())
                        .as("equity at liqPx == Q*liqPx*l, side=%s leverage=%d", side, leverage)
                        .isLessThanOrEqualTo(ROUNDING_TOLERANCE);
            }
        }
    }

    /** {@code Q*price*l} (same rate constant as {@link LiquidationCalculator}), rounded 2dp HALF_UP. */
    private static BigDecimal maintenanceMarginAtPrice(BigDecimal size, BigDecimal price) {
        return size.multiply(price)
                .multiply(LiquidationCalculator.MAINTENANCE_MARGIN_RATE)
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Test
    void leverageBelowMinimumIsRejected() {
        assertThatIllegalArgument(() -> new Leverage(1));
        assertThatIllegalArgument(() -> new Leverage(0));
        assertThatIllegalArgument(() -> new Leverage(-5));
    }

    @Test
    void leverageAboveMaximumIsRejected() {
        assertThatIllegalArgument(() -> new Leverage(41));
        assertThatIllegalArgument(() -> new Leverage(100));
    }

    @Test
    void leverageAboveTierMaxForNotionalIsRejected() {
        // BTC tier1 (doc section 1.4): notional > 150,000,000 USDC caps leverage at 20, not 40.
        BigDecimal aboveTier1Threshold = new BigDecimal("200000000.00").setScale(2, RoundingMode.HALF_UP);
        assertThat(Leverage.forNotional(20, aboveTier1Threshold).value()).isEqualTo(20);
        assertThatIllegalArgument(() -> Leverage.forNotional(21, aboveTier1Threshold));
        assertThatIllegalArgument(() -> Leverage.forNotional(40, aboveTier1Threshold));
    }

    @Test
    void leverageWithinTier0NotionalAllowsFullRange() {
        BigDecimal typicalDemoNotional = new BigDecimal("8345.10");
        assertThat(Leverage.forNotional(40, typicalDemoNotional).value()).isEqualTo(40);
        assertThatIllegalArgument(() -> Leverage.forNotional(41, typicalDemoNotional));
    }

    private static void assertThatIllegalArgument(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        org.assertj.core.api.Assertions.assertThatThrownBy(callable)
                .isInstanceOf(IllegalArgumentException.class);
    }
}
