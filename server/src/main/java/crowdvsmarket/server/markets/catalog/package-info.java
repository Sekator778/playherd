/**
 * Which markets exist (docs/research/markets_architecture.md §3.3, mode=live only): the catalogs list
 * markets into {@link crowdvsmarket.server.markets.MarketRegistry} and close them; the registry
 * notifies the game core. {@link crowdvsmarket.server.markets.catalog.HyperliquidCatalog} lists the
 * configured perpetuals from Hyperliquid's own parameters,
 * {@link crowdvsmarket.server.markets.catalog.PolymarketCatalog} keeps the configured number of
 * politics and sports odds markets and closes them when they stop being tradable,
 * {@link crowdvsmarket.server.markets.catalog.SyntheticCatalog} is the offline equivalent for
 * {@code herd.prices.source=synthetic}. Settings: {@link crowdvsmarket.server.markets.catalog.CatalogConfig}.
 */
package crowdvsmarket.server.markets.catalog;
