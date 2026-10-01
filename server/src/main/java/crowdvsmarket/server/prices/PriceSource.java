package crowdvsmarket.server.prices;

import crowdvsmarket.server.markets.Market;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Where a market's ticks come from (docs/research/markets_architecture.md §3.2). A source owns one
 * or more {@link Feed feeds} — one request shape on one schedule, each with its own staleness and
 * gap limits — and knows how to fetch history for a hole ({@link #history}). {@link PriceFeedService}
 * polls the feeds of the active sources and hands the answers to {@link TickStore};
 * {@link LiveMarketPrices} asks the source for history when a round window has a hole.
 *
 * <p>Implementations: {@link HyperliquidPriceSource} (feeds {@code hyperliquid} and
 * {@code hyperliquid:xyz}), {@link PolymarketPriceSource}, {@link SyntheticPriceSource}. The first
 * two are active with {@code herd.prices.source=live}, the third with {@code synthetic}.
 */
public interface PriceSource {

    /**
     * One price read answered by a feed.
     *
     * @param change24h relative change vs 24 hours ago (0.0098 = +0.98%) when the source gave one
     */
    record Quote(BigDecimal raw, Double change24h) {
    }

    /**
     * A schedule's worth of prices: the id is what {@code price_snapshots.source} holds and what
     * the limits are looked up by.
     *
     * @param staleMs  a tick older than this at a boundary no longer says what the price is
     * @param maxGapMs two neighbouring ticks of a round window further apart than this are a hole
     * @param handles  which markets this feed prices
     */
    record Feed(String id, long staleMs, long maxGapMs, Predicate<Market> handles) {
    }

    /** The source's name: {@code hyperliquid}, {@code polymarket} or {@code synthetic}. */
    String name();

    List<Feed> feeds();

    /**
     * One poll of {@code feed} for {@code markets} (active markets the feed handles). Returns the
     * markets it could read; a market missing from the result simply gets no tick this time. Throws
     * on a transport or protocol failure — a failed poll is logged and costs one tick.
     */
    Map<String, Quote> poll(Feed feed, List<Market> markets) throws Exception;

    /**
     * Price history of {@code market} in {@code [fromMs, toMs]} (the source's own resolution),
     * oldest first, to fill a hole in a round window. May be empty; throws on failure. Blocks on
     * HTTP with the pipeline's timeouts.
     */
    List<PricePoint> history(Market market, long fromMs, long toMs) throws Exception;
}
