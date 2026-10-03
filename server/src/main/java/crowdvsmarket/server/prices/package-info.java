/**
 * Live prices of the listed markets (docs/research/markets_architecture.md §3.2, mode=live only).
 *
 * <pre>
 *   PriceFeedService  --@Scheduled, SKIP-->  PriceSource.poll(feed)      HyperliquidPriceSource (2 feeds, REST)
 *        |                                                               PolymarketPriceSource (REST)
 *        |                                                               CoinbasePriceSource, KrakenPriceSource,
 *        |                                                               HyperliquidSpotPriceSource:
 *        |                                                                 the newest quote of a WebSocket stream kept up
 *        |                                                                 by a StreamSupervisor on a virtual thread; REST
 *        |                                                                 of the same venue for a market whose quote is stale
 *        v                                                               SyntheticPriceSource
 *   TickStore.append(feed, atMs, prices)  --> TickRing per instrument (memory) + one price_snapshots row
 *        ^        ^                           ticks of four kinds: LIVE (a poll's answer), HISTORY, CARRIED, PIN
 *        |        | seed: the recent past of a    |
 *        |        | market, paced by Hyperliquid's v
 *        |     HistorySeeder  <-- catalogs         LiveMarketPrices (MarketPrices):  latest / series / resolve / soFar
 *        |        ^ RunningWindows (where the      |       --> RoundPath (≤ 30 candles)
 *        |        | running windows began)         |  a hole in a round window --> PriceSource.historyOf(...)
 *        | restore at start / prune every 10 min   |  once per cooldown; what it may fill depends on the history's kind;
 *        |                                         |  candles bridge a gap between two live ticks: GapBridge
 *   HyperliquidLiveState / PolymarketLiveState: what a poll tells the catalog (delisted, token missing —
 *                                               as consecutive successful answers)
 *   RoundAlignmentCheck: at start-up, refuses round boundaries that are not on a whole minute
 * </pre>
 *
 * <p><b>Instruments</b> ({@link crowdvsmarket.server.prices.Instruments}, Р-76): a market that has a backup exchange is
 * priced on two venues, each with a ring of its own — the market's id is the primary's key, {@code <id>~<backup>} the
 * backup's ({@code spot_BTC~kraken}); polling, snapshot rows, seeding, pins, healing and staleness work per instrument.
 * Which venue a round runs on is decided once, at its open ({@code RoundVenues}), and it settles only from that venue's ring.
 * A round that cannot be priced there waits — and holds its series — until the scheduler lets it go: cancelled with a refund
 * {@code herd.prices.venue-release-after-ms} after its end when the market's other venue has a live tick fresh now
 * ({@link crowdvsmarket.server.prices.MarketPrices#otherVenueLive}, Р-77), else an hour after its end (Р-23).
 *
 * <p>The fixed contract is {@link crowdvsmarket.server.prices.MarketPrices} and its four records;
 * everything else here is replaceable. Nothing in this package runs with {@code herd.mode=replay}.
 * Settings: {@link crowdvsmarket.server.prices.PricesConfig}.
 *
 * <p>A redeploy leaves a gap in the ticks of a running round. For a source whose history is trade prices
 * (Hyperliquid's candles against its marks, Coinbase's, Kraken's and Hyperliquid spot's against their mids) {@code GapBridge}
 * bridges the gap with the lows, highs and closes of the candles — all or nothing, when every minute of it traded, the
 * live ticks around it lie inside their own minute's range (within the feed's own tolerance, {@code max-basis}), and it
 * is not longer than {@code herd.prices.hyperliquid.max-heal-ms}; otherwise the round waits and is refunded an hour
 * after its end, as it always was.
 *
 * <p>Only a poll makes a quote: history, a quiet asset's carried price and the boundary pin of a settled round
 * live in the ring (a price is looked up there) but are never what {@code latest()} answers, and a pin is not
 * on a chart either; the time a settlement says its price was seen is that of the real tick behind a copy.
 *
 * <p>Locks: nothing here ever calls the market registry, whose listener takes the game engine's lock;
 * {@code latest}, {@code series} and {@code soFar} (called by the game service under the engine's read
 * lock) only copy slices out of the tick rings, one ring monitor at a time for one array copy; the
 * network (a poll, a history request) is never asked for under any lock.
 */
package crowdvsmarket.server.prices;
