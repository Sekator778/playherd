package crowdvsmarket.server.markets.catalog;

import java.util.Map;

/**
 * Card titles of the Hyperliquid markets the default configuration lists (docs/research/
 * markets_architecture.md §3.3); a symbol that is not in the table shows as its own ticker. The
 * symbol is the part of the coin name after the dex prefix ({@code xyz:NVDA} → {@code NVDA}).
 * "Nasdaq 100" for {@code XYZ100} is the label the task brief gives that index — not checked
 * against the xyz dex's own description (UNVERIFIED).
 */
final class MarketNames {

    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("BTC", "Bitcoin"),
            Map.entry("ETH", "Ethereum"),
            Map.entry("SOL", "Solana"),
            Map.entry("XRP", "XRP"),
            Map.entry("DOGE", "Dogecoin"),
            Map.entry("HYPE", "Hyperliquid"),
            Map.entry("NVDA", "NVIDIA"),
            Map.entry("TSLA", "Tesla"),
            Map.entry("SP500", "S&P 500"),
            Map.entry("XYZ100", "Nasdaq 100"),
            Map.entry("GOLD", "Gold"),
            Map.entry("BRENTOIL", "Brent Oil"));

    private MarketNames() {
    }

    static String title(String symbol) {
        return TITLES.getOrDefault(symbol, symbol);
    }

    /** The ticker of a coin name: the part after the dex prefix. */
    static String symbolOf(String coin) {
        int colon = coin.indexOf(':');
        return colon < 0 ? coin : coin.substring(colon + 1);
    }

    /** {@code xyz:NVDA} → {@code xyz_NVDA}: the market id (the round id's separator is {@code -}, so ids have none). */
    static String idOf(String coin) {
        return coin.replace(':', '_');
    }
}
