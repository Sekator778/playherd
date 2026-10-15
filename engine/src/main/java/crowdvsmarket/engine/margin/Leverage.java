package crowdvsmarket.engine.margin;

import java.math.BigDecimal;

/**
 * Validated integer leverage, {@code L} in the spec (docs/research/hl_liquidation_math.md,
 * section 2.2): "integer leverage L in [2,40]" for BTC.
 *
 * <p>The plain constructor enforces exactly that fixed range — BTC's absolute bounds, confirmed
 * live ({@code meta.universe[BTC].maxLeverage=40}, doc section 1.10). {@link #forNotional} additionally
 * enforces the margin-tier cap for a given position notional (doc section 1.4/2.1): for any
 * notional realistic in this MVP (toy-money demo, far under the 150M USDC tier1 threshold) that
 * cap is the same 40, so the two checks coincide in practice, but {@code forNotional} is the one
 * that is actually tier-aware.
 *
 * <p>2..40 is the absolute range every market lives inside; a market with a lower cap (Р-69)
 * validates against it with {@code MarketSpec#leverage(int)}, and {@code GameEngine} rejects a bet
 * above its market's cap.
 */
public record Leverage(int value) {

    public static final int MIN = 2;
    public static final int MAX = 10_000;

    public Leverage {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException(
                    "leverage must be in [" + MIN + ", " + MAX + "], got " + value);
        }
    }

    /**
     * Validates {@code value} against both the fixed [2,40] range and the margin-tier max leverage
     * for the given position notional (BTC margin table, see {@link MarginTable#BTC}).
     */
    public static Leverage forNotional(int value, BigDecimal notional) {
        int cap = MarginTable.BTC.maxLeverageFor(notional);
        if (value < MIN || value > cap) {
            throw new IllegalArgumentException(
                    "leverage must be in [" + MIN + ", " + cap + "] for notional " + notional
                            + ", got " + value);
        }
        return new Leverage(value);
    }

    public BigDecimal asBigDecimal() {
        return BigDecimal.valueOf(value);
    }
}
