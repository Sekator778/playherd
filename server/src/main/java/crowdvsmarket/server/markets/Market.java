package crowdvsmarket.server.markets;

import crowdvsmarket.engine.market.MarketSpec;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One market ("pair") — docs/research/markets_architecture.md §3.1. Immutable once listed (Т-10):
 * if the source changes a parameter, that is a new market.
 *
 * <p><b>Lot price (Т-3).</b> The engine computes prices at scale 2. A market's raw price is handed
 * to the engine as the price of a lot of {@code 10^lotExp} units ({@link #toEngine}); every price
 * that leaves the server is converted back ({@link #toRaw}) and carries {@link #priceDecimals()}
 * fractional digits. P&L and liquidation distances do not depend on the scale.
 *
 * @param id          {@code [A-Za-z0-9_]{1,32}}: "BTC", "ETH", "xyz_NVDA", "PM540816"
 * @param category    one of {@link Category#ORDER}'s ids
 * @param symbol      card ticker: "BTC", "NVDA", "GER"
 * @param title       "Bitcoin"; for {@code ODDS} the market question
 * @param subtitle    {@code ODDS}: the outcome the price refers to plus context; {@code PERP}: null
 * @param iconUrl     source image when there is one; null otherwise
 * @param source      hyperliquid | coinbase | polymarket | synthetic
 * @param sourceRef   Hyperliquid coin name ("BTC", "xyz:NVDA") or spot pair name ("@107": {@code sourceMeta} says
 *                    {@code "spot":true}), Coinbase product id ("BTC-USD"), or Polymarket CLOB token id
 * @param sourceMeta  JSON with whatever the source's catalog needs later (may be null)
 * @param economy     the economy-stage-A parameters (caps per round length, κ, r_m); a market built without them gets
 *                    {@link MarketEconomy#defaultsFor}
 * @param oracleIndex append-only index published on chain as the record's asset; the migration's BTC row is 0 (when
 *                    BTC's price moved to another source that row was closed and the new market has its own index)
 */
public record Market(
        String id,
        MarketKind kind,
        String category,
        String symbol,
        String title,
        String subtitle,
        String iconUrl,
        String source,
        String sourceRef,
        String sourceMeta,
        int maxLeverage,
        int lotExp,
        int oracleIndex,
        MarketStatus status,
        long listedAtMs,
        Long closedAtMs,
        String closeReason,
        Long endsAtMs,
        MarketEconomy economy) {

    /** A market with the default economy parameters of its kind and symbol ({@link MarketEconomy#defaultsFor}). */
    public Market(String id, MarketKind kind, String category, String symbol, String title, String subtitle,
                  String iconUrl, String source, String sourceRef, String sourceMeta, int maxLeverage, int lotExp,
                  int oracleIndex, MarketStatus status, long listedAtMs, Long closedAtMs, String closeReason,
                  Long endsAtMs) {
        this(id, kind, category, symbol, title, subtitle, iconUrl, source, sourceRef, sourceMeta, maxLeverage, lotExp,
                oracleIndex, status, listedAtMs, closedAtMs, closeReason, endsAtMs,
                MarketEconomy.defaultsFor(kind, symbol, maxLeverage));
    }

    public static final String BTC_ID = MarketSpec.BTC_ID;
    /** Lot exponent of every {@code ODDS} market: probability × 10^4, e.g. 0.5230 → 5230.00. */
    public static final int ODDS_LOT_EXP = 4;
    public static final int MAX_LOT_EXP = 8;

    public boolean isActive() {
        return status == MarketStatus.ACTIVE;
    }

    /**
     * The engine-side parameters: the market's single original cap and its rate (what every bet placed before the economy
     * change is still settled with) plus the stage-A caps per round length, κ and r_m of {@link #economy}.
     */
    public MarketSpec spec() {
        return MarketSpec.of(id, maxLeverage)
                .withEconomy(economy.capHourly(), economy.capDaily(), economy.kappa(), economy.herdFeeRate());
    }

    /** Fractional digits of every raw price string of this market. */
    public int priceDecimals() {
        return 2 + lotExp;
    }

    public BigDecimal toEngine(BigDecimal raw) {
        return raw.movePointRight(lotExp).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal toRaw(BigDecimal engine) {
        return engine.movePointLeft(lotExp).setScale(priceDecimals(), RoundingMode.HALF_UP);
    }

    public String rawString(BigDecimal engine) {
        return toRaw(engine).toPlainString();
    }

    /**
     * Т-3: {@code clamp(3 - floor(log10(raw)), 0, 8)} — the lot price lands in [1000, 10000) at
     * listing time, so 2 engine decimals keep at least 6 significant digits.
     */
    public static int lotExpFor(BigDecimal rawPrice) {
        if (rawPrice.signum() <= 0) {
            throw new IllegalArgumentException("price must be positive: " + rawPrice);
        }
        int floorLog10 = rawPrice.precision() - rawPrice.scale() - 1;
        return Math.max(0, Math.min(MAX_LOT_EXP, 3 - floorLog10));
    }

    public Market closed(long atMs, String reason) {
        return new Market(id, kind, category, symbol, title, subtitle, iconUrl, source, sourceRef, sourceMeta,
                maxLeverage, lotExp, oracleIndex, MarketStatus.CLOSED, listedAtMs, atMs, reason, endsAtMs, economy);
    }
}
