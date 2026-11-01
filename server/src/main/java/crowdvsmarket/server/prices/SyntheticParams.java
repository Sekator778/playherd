package crowdvsmarket.server.prices;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The built-in parameters of the synthetic markets (herd.prices.source=synthetic,
 * docs/research/markets_architecture.md §2 T-12): leverage cap and start price per symbol, rounded
 * from Hyperliquid's real numbers of 2026-10-01 (hl_multi_asset_live.md §2, §7.3). Shared by
 * {@code SyntheticCatalog} (which lists the markets) and {@link SyntheticPriceSource} (which prices
 * them, including BTC, whose row is created by the V7 migration and has no start price of its own).
 */
public final class SyntheticParams {

    /** A synthetic PERP market's leverage cap and the price its walk starts from. */
    public record Param(int maxLeverage, BigDecimal start) {
    }

    /** What a symbol that is not in the table gets: 10x at 100. */
    public static final Param UNKNOWN = new Param(10, new BigDecimal("100"));

    private static final Map<String, Param> TABLE = Map.ofEntries(
            Map.entry("BTC", new Param(40, new BigDecimal("84000"))),
            Map.entry("ETH", new Param(25, new BigDecimal("2700"))),
            Map.entry("SOL", new Param(20, new BigDecimal("150"))),
            Map.entry("XRP", new Param(20, new BigDecimal("2.5"))),
            Map.entry("DOGE", new Param(10, new BigDecimal("0.095"))),
            Map.entry("HYPE", new Param(10, new BigDecimal("40"))),
            Map.entry("NVDA", new Param(20, new BigDecimal("180"))),
            Map.entry("TSLA", new Param(20, new BigDecimal("360"))),
            Map.entry("SP500", new Param(40, new BigDecimal("7600"))),
            Map.entry("XYZ100", new Param(30, new BigDecimal("28000"))),
            Map.entry("GOLD", new Param(25, new BigDecimal("4000"))),
            Map.entry("BRENTOIL", new Param(20, new BigDecimal("70"))));

    private SyntheticParams() {
    }

    /** The parameters of a symbol (the part of a coin name after the dex prefix), or {@link #UNKNOWN}. */
    public static Param forSymbol(String symbol) {
        return TABLE.getOrDefault(symbol, UNKNOWN);
    }

    public static boolean isKnown(String symbol) {
        return TABLE.containsKey(symbol);
    }
}
