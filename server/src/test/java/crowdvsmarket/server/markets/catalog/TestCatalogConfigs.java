package crowdvsmarket.server.markets.catalog;

/**
 * Configurations of the catalogs for tests of the Hyperliquid <i>perpetuals</i>: the six crypto perpetuals and the xyz
 * lists, as {@link CatalogConfig#defaults()} had them before the crypto markets moved to Coinbase and Hyperliquid's
 * spot market (the default of {@code herd.markets.crypto} is empty now).
 */
final class TestCatalogConfigs {

    private TestCatalogConfigs() {
    }

    /** Real sources, the six crypto perpetuals named, everything else as {@link CatalogConfig#defaults()}. */
    static CatalogConfig withCryptoPerpetuals() {
        CatalogConfig d = CatalogConfig.defaults();
        return new CatalogConfig(d.liveSources(), d.syntheticSource(), CatalogConfig.parseNames(CatalogConfig.LEGACY_CRYPTO),
                d.stocks(), d.indices(), d.commodities(), d.polymarketEnabled(), d.politicsCount(), d.sportsCount(),
                d.oddsMaxLeverage(), d.inPlayFirst(), d.gameCooldownMs(), d.coinbase(), d.hyperliquidSpot(),
                d.spotMaxLeverage());
    }
}
