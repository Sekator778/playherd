/**
 * Which markets exist (docs/research/markets_architecture.md §3.3, mode=live only): the catalogs list
 * markets into {@link crowdvsmarket.server.markets.MarketRegistry} and close them; the registry
 * notifies the game core. {@link crowdvsmarket.server.markets.catalog.HyperliquidCatalog} lists the
 * configured perpetuals from Hyperliquid's own parameters and closes the market of an asset the
 * exchange delisted,
 * {@link crowdvsmarket.server.markets.catalog.PolymarketCatalog} keeps the configured number of
 * politics and sports odds markets — in sports only the markets that say who wins a game, and none of a game
 * a market of which it closed within the last hours — and closes them when they stop being tradable,
 * {@link crowdvsmarket.server.markets.catalog.SyntheticCatalog} is the offline equivalent for
 * {@code herd.prices.source=synthetic}. Settings: {@link crowdvsmarket.server.markets.catalog.CatalogConfig}.
 *
 * <p>Every market a catalog lists is handed to the {@link crowdvsmarket.server.prices.HistorySeeder},
 * which fetches its last minutes of history in the background, paced by the source's rate limit. The reason a
 * catalog gives for closing a market is shown to the players as it stands — a plain sentence — and a market
 * is closed only on fresh evidence: several consecutive successful answers, not one answer and then silence.
 * An odds market's ticker (unique among the active markets) and subtitle are made by
 * {@link crowdvsmarket.server.markets.catalog.MarketLabels}. {@code registry.list} and
 * {@code registry.close} are always called without any lock held: the registry's listener takes the
 * game engine's write lock, which the game service holds in read mode while it reads prices.
 */
package crowdvsmarket.server.markets.catalog;
