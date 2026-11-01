package crowdvsmarket.engine.margin;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Isolated-margin liquidation math (BTC by default, any market via an explicit rate), implementing
 * exactly docs/research/hl_liquidation_math.md section 2.2 ("Модель для нашего движка") — no more,
 * no less. Every method here is a direct translation of one of that section's formulas.
 *
 * <p><b>Scope (doc section 2.1, engine decision):</b> isolated margin only, tier0 only. The
 * maintenance margin rate {@code l} used throughout is a per-call input, independent of the
 * leverage the trader chose and independent of notional. The overloads without a rate use the BTC
 * constant {@link #MAINTENANCE_MARGIN_RATE} ({@code 0.0125}, BTC tier0, {@code 1/(2*40)}); the
 * overloads that take the rate explicitly serve every other market, whose rate is
 * {@code 1/(2*maxLeverage)} ({@code MarketSpec}, doc section 1.4, Р-69) — same formulas, same
 * rounding, and for BTC the same results bit for bit. Tier1 (notional {@literal >} 150M USDC) is
 * deliberately not implemented, since no demo position approaches that size (doc section 2.1,
 * section 6 open question).
 *
 * <p><b>Rounding (doc section 2.6):</b> prices/liq-price/OHLC and margin/PnL/payout are scale 2
 * (USD cents), BTC size is scale 5, all {@link RoundingMode#HALF_UP}. Intermediate divisions use
 * {@link MathContext#DECIMAL128}; rounding is applied only to each formula's final result.
 * {@code initial_margin} is rounded to 2dp <b>before</b> it is used inside
 * {@link #liquidationPrice}, because it stands in for the actually-posted (already-rounded)
 * collateral balance, not an idealized unrounded value (doc section 2.6, last row).
 *
 * <p>All methods are static: this class carries no state, matching its pure-function role in the
 * engine (one calculator for every market, the market-specific rate passed in — not one instance
 * per asset, see doc section 2.1 scope).
 */
public final class LiquidationCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** BTC tier0 maintenance margin rate {@code l = 1/(2*40) = 0.0125}, see class-level note. */
    public static final BigDecimal MAINTENANCE_MARGIN_RATE = MarginTable.BTC.tier(0).maintenanceMarginRate();

    private LiquidationCalculator() {
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, ROUNDING);
    }

    /** {@code initial_margin (isolated_margin) = round2( Q * P0 / L )}. */
    public static BigDecimal initialMargin(BigDecimal size, BigDecimal entryPrice, Leverage leverage) {
        BigDecimal raw = size.multiply(entryPrice).divide(leverage.asBigDecimal(), MC);
        return money(raw);
    }

    /** {@code maintenance_margin_required = round2( Q * P0 * l )} with the BTC rate {@link #MAINTENANCE_MARGIN_RATE}. */
    public static BigDecimal maintenanceMarginRequired(BigDecimal size, BigDecimal entryPrice) {
        return maintenanceMarginRequired(size, entryPrice, MAINTENANCE_MARGIN_RATE);
    }

    /** {@code maintenance_margin_required = round2( Q * P0 * l )} with an explicit rate {@code l} (Р-69, per-market). */
    public static BigDecimal maintenanceMarginRequired(
            BigDecimal size, BigDecimal entryPrice, BigDecimal maintenanceMarginRate) {
        BigDecimal raw = size.multiply(entryPrice).multiply(maintenanceMarginRate, MC);
        return money(raw);
    }

    /**
     * {@code liquidation_price = round2( (Q*P0 - side*isolated_margin) / (Q*(1 - l*side)) )},
     * using the already-2dp-rounded {@code isolated_margin} from {@link #initialMargin} (doc
     * section 2.6), with the BTC rate {@link #MAINTENANCE_MARGIN_RATE}.
     */
    public static BigDecimal liquidationPrice(
            BigDecimal size, BigDecimal entryPrice, Leverage leverage, Side side) {
        return liquidationPrice(size, entryPrice, leverage, side, MAINTENANCE_MARGIN_RATE);
    }

    /** {@link #liquidationPrice(BigDecimal, BigDecimal, Leverage, Side)} with an explicit rate {@code l} (Р-69, per-market). */
    public static BigDecimal liquidationPrice(
            BigDecimal size, BigDecimal entryPrice, Leverage leverage, Side side, BigDecimal maintenanceMarginRate) {
        BigDecimal isolatedMargin = initialMargin(size, entryPrice, leverage);
        BigDecimal sideMultiplier = side.multiplier();
        BigDecimal numerator = size.multiply(entryPrice).subtract(sideMultiplier.multiply(isolatedMargin));
        BigDecimal denominator = size.multiply(
                BigDecimal.ONE.subtract(maintenanceMarginRate.multiply(sideMultiplier)));
        return money(numerator.divide(denominator, MC));
    }

    /** {@code unrealized_pnl(P) = round2( side * Q * (P - P0) )}. */
    public static BigDecimal unrealizedPnl(
            BigDecimal size, BigDecimal entryPrice, Side side, BigDecimal price) {
        BigDecimal raw = side.multiplier().multiply(size).multiply(price.subtract(entryPrice), MC);
        return money(raw);
    }

    /**
     * Equity at {@code price}: {@code initial_margin + unrealized_pnl(price)}, unclamped. Not a
     * named formula in the spec by itself, but the quantity the doc's derivation of
     * {@code liquidation_price} (section 2.2) sets equal to {@code maintenance_margin_required} at
     * the liquidation price; exposed here because it is the natural building block for
     * {@link #payoutAtRoundClose} and for verifying that invariant in tests.
     */
    public static BigDecimal equityAt(
            BigDecimal size, BigDecimal entryPrice, Leverage leverage, Side side, BigDecimal price) {
        BigDecimal isolatedMargin = initialMargin(size, entryPrice, leverage);
        BigDecimal pnl = unrealizedPnl(size, entryPrice, side, price);
        return money(isolatedMargin.add(pnl));
    }

    /** {@code payout_at_round_close(P_close) = round2( max(0, isolated_margin + unrealized_pnl(P_close)) )}. */
    public static BigDecimal payoutAtRoundClose(
            BigDecimal size, BigDecimal entryPrice, Leverage leverage, Side side, BigDecimal closePrice) {
        BigDecimal equity = equityAt(size, entryPrice, leverage, side, closePrice);
        return equity.max(BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING));
    }

    /**
     * {@code payout_on_liquidation = 0}: on liquidation the player loses the entire stake (the
     * whole isolated margin) — product decision <b>Р-7</b> ({@code the decision log},
     * Р-7, 2026-09-25), which overrides the spec's earlier maintenance-margin-cushion proposal
     * (doc section 2.2). {@code size}/{@code entryPrice} are accepted only to keep the same call
     * shape as the other payout methods; the result does not depend on them. The liquidation
     * <b>price</b> itself is unaffected by Р-7 and still follows Hyperliquid's formula exactly
     * (doc section 1.6/2.2, {@link #liquidationPrice}).
     */
    public static BigDecimal payoutOnLiquidation(BigDecimal size, BigDecimal entryPrice) {
        return BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING);
    }

    /**
     * Economy stage A (Р-92, plan A1), replacing {@link #payoutOnLiquidation} for bets placed under the new rules: the
     * liquidated position is paid its remainder at the extreme of the candle on which the liquidation triggered —
     * {@code round2( max(0, isolated_margin + side * Q * (P_ext - P0)) )}, {@code P_ext} being that candle's low for a
     * long and its high for a short. A gap through the margin (the extreme beyond the point where the margin is gone)
     * pays 0. No penalty.
     */
    public static BigDecimal payoutAtCandleExtreme(
            BigDecimal size, BigDecimal entryPrice, Leverage leverage, Side side, BigDecimal extremePrice) {
        return payoutAtRoundClose(size, entryPrice, leverage, side, extremePrice);
    }

    /**
     * {@code equityAt(price) / maintenance_margin_required} — not itself a spec formula, a derived
     * ratio (1.0 exactly at the liquidation price) useful for display/diagnostics. BTC rate
     * {@link #MAINTENANCE_MARGIN_RATE}.
     */
    public static BigDecimal marginRatio(
            BigDecimal size, BigDecimal entryPrice, Leverage leverage, Side side, BigDecimal price) {
        return marginRatio(size, entryPrice, leverage, side, price, MAINTENANCE_MARGIN_RATE);
    }

    /** {@link #marginRatio(BigDecimal, BigDecimal, Leverage, Side, BigDecimal)} with an explicit rate {@code l} (Р-69, per-market). */
    public static BigDecimal marginRatio(
            BigDecimal size, BigDecimal entryPrice, Leverage leverage, Side side, BigDecimal price,
            BigDecimal maintenanceMarginRate) {
        BigDecimal equity = equityAt(size, entryPrice, leverage, side, price);
        BigDecimal maintenanceMargin = maintenanceMarginRequired(size, entryPrice, maintenanceMarginRate);
        return equity.divide(maintenanceMargin, MC);
    }
}
