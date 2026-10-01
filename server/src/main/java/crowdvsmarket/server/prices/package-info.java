/**
 * Live prices of the listed markets (docs/research/markets_architecture.md §3.2, mode=live only).
 *
 * <pre>
 *   PriceFeedService  --@Scheduled, SKIP-->  PriceSource.poll(feed)      HyperliquidPriceSource (2 feeds)
 *        |                                                               PolymarketPriceSource
 *        v                                                               SyntheticPriceSource
 *   TickStore.append(feed, atMs, prices)  --> TickRing per market (memory) + one price_snapshots row
 *        ^        ^                                           |
 *        |        | seedHistory: the last minutes of a        v
 *        |        | new market, in the background     LiveMarketPrices (MarketPrices):  latest / series / resolve / soFar
 *        |     HistorySeeder  <-- catalogs, start-up         |       --> RoundPath (≤ 30 candles)
 *        | restore at start / prune every 10 min             |  a hole in a round window --> PriceSource.history(...)
 *                                                            |  once per cooldown, merged into the hole
 *   HyperliquidLiveState / PolymarketLiveState: what a poll tells the catalog (delisted, token missing)
 * </pre>
 *
 * The fixed contract is {@link crowdvsmarket.server.prices.MarketPrices} and its four records;
 * everything else here is replaceable. Nothing in this package runs with {@code herd.mode=replay}.
 * Settings: {@link crowdvsmarket.server.prices.PricesConfig}.
 *
 * <p>Locks: nothing here ever calls the market registry, whose listener takes the game engine's lock;
 * {@code latest}, {@code series} and {@code soFar} (called by the game service under the engine's read
 * lock) only copy slices out of the tick rings, one ring monitor at a time for one array copy; the
 * network (a poll, a history request) is never asked for under any lock.
 */
package crowdvsmarket.server.prices;
