/**
 * Live prices of the listed markets (docs/research/markets_architecture.md §3.2, mode=live only).
 *
 * <pre>
 *   PriceFeedService  --@Scheduled, SKIP-->  PriceSource.poll(feed)      HyperliquidPriceSource (2 feeds)
 *        |                                                               PolymarketPriceSource
 *        v                                                               SyntheticPriceSource
 *   TickStore.append(feed, atMs, prices)  --> TickRing per market (memory) + one price_snapshots row
 *        ^                                                    |
 *        | restore at start / prune every 10 min              v
 *   LiveMarketPrices (MarketPrices):  latest / series / resolve / soFar   --> RoundPath (≤ 30 candles)
 *        |  a hole in a round window --> PriceSource.history(...) once per cooldown, merged into the hole
 * </pre>
 *
 * The fixed contract is {@link crowdvsmarket.server.prices.MarketPrices} and its four records;
 * everything else here is replaceable. Nothing in this package runs with {@code herd.mode=replay}.
 * Settings: {@link crowdvsmarket.server.prices.PricesConfig}.
 */
package crowdvsmarket.server.prices;
