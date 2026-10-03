package crowdvsmarket.server.prices;

import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketStatus;

/** {@link Market} values for the tests, built the way the catalogs and the V7 migration build them. */
public final class TestMarkets {

    private TestMarkets() {
    }

    /** BTC as the V7 migration creates it: lot exponent 0, 40x, oracle index 0. */
    public static Market btc() {
        return perp("BTC", "BTC", Category.CRYPTO, 40, 0, 0);
    }

    /** A Hyperliquid PERP; {@code coin} is the source name ({@code ETH}, {@code xyz:NVDA}). */
    public static Market perp(String id, String coin, String category, int maxLeverage, int lotExp, int oracleIndex) {
        int colon = coin.indexOf(':');
        String symbol = colon < 0 ? coin : coin.substring(colon + 1);
        return new Market(id, MarketKind.PERP, category, symbol, id, null, null, "hyperliquid", coin, null,
                maxLeverage, lotExp, oracleIndex, MarketStatus.ACTIVE, 0L, null, null, null);
    }

    public static Market hl(String id, String coin, int lotExp) {
        return perp(id, coin, Category.CRYPTO, 20, lotExp, 1);
    }

    /** A Coinbase market as the spot catalog lists it: {@code spot_BTC} priced by the product {@code BTC-USD}. */
    public static Market coinbase(String symbol, int lotExp, int oracleIndex) {
        return new Market("spot_" + symbol, MarketKind.PERP, Category.CRYPTO, symbol, symbol, null, null, "coinbase",
                symbol + "-USD", "{\"product\":\"" + symbol + "-USD\",\"quote\":\"USD\"}", 20, lotExp, oracleIndex,
                MarketStatus.ACTIVE, 0L, null, null, null);
    }

    /** A Hyperliquid spot market as the spot catalog lists it: {@code spot_HYPE} priced by the pair {@code @107}. */
    public static Market hlSpot(String symbol, String pair, int lotExp, int oracleIndex) {
        return new Market("spot_" + symbol, MarketKind.PERP, Category.CRYPTO, symbol, symbol, null, null, "hyperliquid",
                pair, "{\"spot\":true,\"pair\":\"" + symbol + "/USDC\"}", 10, lotExp, oracleIndex, MarketStatus.ACTIVE,
                0L, null, null, null);
    }

    /** A Coinbase market with Kraken behind it, as the spot catalog lists BTC, ETH, SOL, XRP and DOGE: {@code spot_BTC}. */
    public static Market coinbaseWithKraken(String symbol, int lotExp, int oracleIndex) {
        return withBackup(coinbase(symbol, lotExp, oracleIndex), "kraken", symbol + "/USD");
    }

    /** The Hyperliquid spot market with Coinbase behind it, as the spot catalog lists HYPE: {@code spot_HYPE}. */
    public static Market hlSpotWithCoinbase(String symbol, String pair, int lotExp, int oracleIndex) {
        return withBackup(hlSpot(symbol, pair, lotExp, oracleIndex), "coinbase", symbol + "-USD");
    }

    /** {@code market} with the backup exchange recorded in its {@code sourceMeta}, the way the catalog records it. */
    public static Market withBackup(Market market, String backupSource, String backupRef) {
        String base = market.sourceMeta();
        String meta = (base == null || base.equals("{}") ? "{" : base.substring(0, base.length() - 1) + ",")
                + "\"backup\":{\"source\":\"" + backupSource + "\",\"ref\":\"" + backupRef + "\"}}";
        return new Market(market.id(), market.kind(), market.category(), market.symbol(), market.title(),
                market.subtitle(), market.iconUrl(), market.source(), market.sourceRef(), meta, market.maxLeverage(),
                market.lotExp(), market.oracleIndex(), market.status(), market.listedAtMs(), market.closedAtMs(),
                market.closeReason(), market.endsAtMs());
    }

    /** A Polymarket ODDS market priced by {@code token}. */
    public static Market odds(String id, String token) {
        return new Market(id, MarketKind.ODDS, Category.POLITICS, "PM", "Will it?", "Yes", null, "polymarket", token,
                null, 10, Market.ODDS_LOT_EXP, 7, MarketStatus.ACTIVE, 0L, null, null, null);
    }

    /** A synthetic market of the given kind and symbol; {@code sourceMeta} may carry {@code {"start":"…"}}. */
    public static Market synthetic(String id, MarketKind kind, String symbol, int lotExp, String sourceMeta) {
        return new Market(id, kind, kind == MarketKind.ODDS ? Category.POLITICS : Category.CRYPTO, symbol, id, null,
                null, "synthetic", id, sourceMeta, 20, lotExp, 3, MarketStatus.ACTIVE, 0L, null, null, null);
    }
}
