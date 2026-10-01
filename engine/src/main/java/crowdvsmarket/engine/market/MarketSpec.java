package crowdvsmarket.engine.market;

import crowdvsmarket.engine.margin.Leverage;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.regex.Pattern;

/**
 * The risk parameters of one tradable market (pair): its id, its leverage cap and its maintenance
 * margin rate {@code l} (docs/research/hl_liquidation_math.md section 2.2). The engine started as
 * a single-market (BTC) calculator; Р-69 (category panels, then pairs, then a bet with leverage
 * inside a pair — the Р-59/Р-61 lobby of pairs, extended) makes the two things that used to be
 * BTC constants — the leverage cap and {@code l} — per-market values carried by this record.
 * Round ids become market-aware too, see {@code RoundWindow#roundId()}.
 *
 * <p>The rate follows Hyperliquid's tier-0 rule (doc section 1.4,
 * {@code maintenance_margin_rate = (initial margin rate at maximum leverage) / 2}, i.e.
 * {@code l = 1/(2*maxLeverage)}), which {@link #of} computes exactly the way {@code
 * MarginTable#build} does; {@link #BTC} therefore carries the very same 0.0125 as
 * {@code MarginTable.BTC.tier(0)} and {@code LiquidationCalculator.MAINTENANCE_MARGIN_RATE}. Only
 * tier 0 exists for every market, as for BTC (doc section 2.1).
 *
 * <p><b>Validation:</b> {@code id} is {@code [A-Za-z0-9_]{1,32}}, {@code maxLeverage} is in
 * {@code [Leverage.MIN, Leverage.MAX]}, and the rate is in {@code (0, 0.5)} and below
 * {@code 1/maxLeverage}; anything else, a {@code null} included, is an
 * {@link IllegalArgumentException}. The last bound is what keeps a liquidation price on the right
 * side of the entry: at leverage {@code L} the long's is {@code entry * (1 - 1/L) / (1 - l)}, below
 * the entry exactly when {@code l < 1/L} (the short's, {@code entry * (1 + 1/L) / (1 + l)}, is
 * above it under the same condition), and {@code 1/maxLeverage} is the tightest of those over every
 * leverage the market allows. {@link #of} always satisfies it, {@code l = 1/(2*maxLeverage)}; only a
 * spec built by hand or read from a config can break it, and such a market would liquidate every
 * position on its first candle, so it fails fast instead.
 *
 * <p><b>Price scale:</b> a market has no price-scale field. Prices handed to the engine are
 * whatever scale-2 number the caller supplies (a "lot price"); the server scales a raw price by a
 * power of ten before it reaches the engine, so {@code Candle}, {@code PositionSizing} and the
 * liquidation formulas stay exactly as they were for BTC (see engine/README.md, "Markets").
 */
public record MarketSpec(String id, int maxLeverage, BigDecimal maintenanceMarginRate) {

    // Declared before BTC on purpose: BTC's initializer runs the compact constructor, which reads
    // these two (static fields initialize in textual order).
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,32}");
    private static final BigDecimal RATE_UPPER_BOUND = new BigDecimal("0.5");

    /** The legacy market id. A {@code RoundWindow} of this market keeps the pre-markets round id. */
    public static final String BTC_ID = "BTC";

    /** Hyperliquid BTC tier 0: 40x, {@code l = 1/80 = 0.0125} (doc section 1.4). */
    public static final MarketSpec BTC = MarketSpec.of(BTC_ID, 40);

    public MarketSpec {
        requireValidId(id);
        requireMaxLeverage(maxLeverage);
        if (maintenanceMarginRate == null || maintenanceMarginRate.signum() <= 0
                || maintenanceMarginRate.compareTo(RATE_UPPER_BOUND) >= 0) {
            throw new IllegalArgumentException(
                    "maintenanceMarginRate must be in (0, " + RATE_UPPER_BOUND + "), got " + maintenanceMarginRate);
        }
        if (maintenanceMarginRate.multiply(BigDecimal.valueOf(maxLeverage)).compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("maintenanceMarginRate must be below 1/maxLeverage (1/" + maxLeverage
                    + "), or a liquidation price falls on the wrong side of the entry, got " + maintenanceMarginRate);
        }
    }

    /**
     * A market with Hyperliquid's tier-0 maintenance margin rate for {@code maxLeverage}:
     * {@code l = 1/(2*maxLeverage)} (doc section 1.4), 34 significant digits, the same expression
     * {@code MarginTable#build} evaluates.
     */
    public static MarketSpec of(String id, int maxLeverage) {
        requireMaxLeverage(maxLeverage); // before dividing: 0 would be an ArithmeticException, not a bad argument
        BigDecimal rate = BigDecimal.ONE.divide(BigDecimal.valueOf(2L * maxLeverage), MathContext.DECIMAL128);
        return new MarketSpec(id, maxLeverage, rate);
    }

    /**
     * Returns {@code id} when it is a legal market id, {@code [A-Za-z0-9_]{1,32}}. The id is
     * embedded in round ids ({@code "<market>-<code>-<startMs>"}), URLs and storage keys, so it can
     * never contain the {@code '-'} the round-id parser splits on.
     *
     * @throws IllegalArgumentException if {@code id} is {@code null} or not {@code [A-Za-z0-9_]{1,32}}
     */
    public static String requireValidId(String id) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "market id must match [A-Za-z0-9_]{1,32}, got " + (id == null ? "null" : "\"" + id + "\""));
        }
        return id;
    }

    private static void requireMaxLeverage(int maxLeverage) {
        if (maxLeverage < Leverage.MIN || maxLeverage > Leverage.MAX) {
            throw new IllegalArgumentException(
                    "maxLeverage must be in [" + Leverage.MIN + ", " + Leverage.MAX + "], got " + maxLeverage);
        }
    }

    /**
     * Validated leverage for this market: {@code value} must be in {@code [Leverage.MIN, maxLeverage]}.
     * ({@code new Leverage(value)} alone only enforces the absolute 2..40 range.)
     */
    public Leverage leverage(int value) {
        if (value < Leverage.MIN || value > maxLeverage) {
            throw new IllegalArgumentException(
                    "leverage must be in [" + Leverage.MIN + ", " + maxLeverage + "] for " + id + ", got " + value);
        }
        return new Leverage(value);
    }

    /** {@code value} pulled into {@code [Leverage.MIN, maxLeverage]} — e.g. the crowd account's weighted leverage (Р-14). */
    public int clampLeverage(int value) {
        return Math.max(Leverage.MIN, Math.min(maxLeverage, value));
    }
}
