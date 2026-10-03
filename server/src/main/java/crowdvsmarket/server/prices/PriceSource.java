package crowdvsmarket.server.prices;

import crowdvsmarket.server.markets.Market;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Where a market's ticks come from (docs/research/markets_architecture.md §3.2). A source owns one
 * or more {@link Feed feeds} — one request shape on one schedule, each with its own staleness and
 * gap limits — and knows how to fetch history for a hole ({@link #history}). {@link PriceFeedService}
 * polls the feeds of the active sources and hands the answers to {@link TickStore};
 * {@link LiveMarketPrices} asks the source for history when a round window has a hole.
 *
 * <p>Implementations: {@link HyperliquidPriceSource} (feeds {@code hyperliquid} and
 * {@code hyperliquid:xyz}), {@link HyperliquidSpotPriceSource} ({@code hyperliquid:spot}),
 * {@link CoinbasePriceSource} ({@code coinbase}), {@link PolymarketPriceSource}, {@link SyntheticPriceSource}. All
 * but the last are active with {@code herd.prices.source=live}, the last with {@code synthetic}. The Coinbase and the
 * Hyperliquid spot source keep the latest quote of each market from a WebSocket stream ({@link StreamSupervisor}) and
 * answer a poll from memory; only when the stream is down or silent do they read the same venue over REST.
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
     * @param maxBasis how far a live tick of this feed may lie outside the range of its own minute's candle, relative
     *                 to the tick, for the candles to bridge a gap between two live ticks ({@link GapBridge}); only a
     *                 feed of a {@link HistoryKind#TRADE_PRICES} source has a use for it. {@code null}: the tolerance of
     *                 Hyperliquid's perpetuals, {@code herd.prices.hyperliquid.max-basis} — each venue is measured on
     *                 its own, the tolerance is not a property of the pipeline.
     */
    record Feed(String id, long staleMs, long maxGapMs, Predicate<Market> handles, Double maxBasis) {

        public Feed(String id, long staleMs, long maxGapMs, Predicate<Market> handles) {
            this(id, staleMs, maxGapMs, handles, null);
        }
    }

    /**
     * What the numbers of {@link #history} are, compared with the ticks of {@link #poll}.
     * {@link #SAME_AS_LIVE}: the same quantity (Polymarket's midpoint buckets against its live midpoints) — a
     * history point may stand between two ticks. {@link #TRADE_PRICES}: another one (Hyperliquid's candles are
     * trade prices, its ticks are mark prices; Coinbase's and Hyperliquid spot's candles are trade prices, their ticks
     * are mids of the best bid and ask) — history is the past of the first tick, and it bridges a gap between
     * two live ticks only as a whole, when every bar of the gap is there and the live prices around it lie inside
     * their own bars ({@link LiveMarketPrices}, {@code GapBridge}); it is never put between two live ticks otherwise.
     */
    enum HistoryKind {
        SAME_AS_LIVE, TRADE_PRICES
    }

    /**
     * One closed bar of trade prices, with trades in it (a 1-minute candle): the span it covers, {@code startMs}
     * to {@code endMs} both inclusive (Hyperliquid: {@code t} and {@code T = t + 59 999}), and what traded in it.
     * Where inside the bar the extremes happened nobody knows, so a bar puts them a third and two thirds into its
     * span — the low first when it closed up, the high first when it closed down — and its close at its end:
     * consecutive bars then give points at most a third of a bar (20.001 s for a minute) apart, which is what
     * lets a gap that is fully covered satisfy the "no two neighbouring points further apart than the maximum gap"
     * rule and a missing bar break it ({@code GapBridge}).
     */
    record Bar(long startMs, long endMs, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {

        long widthMs() {
            return endMs - startMs + 1;
        }

        boolean contains(long atMs) {
            return atMs >= startMs && atMs <= endMs;
        }

        /** The bar closed at or above where it opened: its low came before its high. */
        boolean closedUp() {
            return close.compareTo(open) >= 0;
        }

        long firstExtremeAtMs() {
            return startMs + widthMs() / 3;
        }

        long secondExtremeAtMs() {
            return startMs + 2 * (widthMs() / 3);
        }

        BigDecimal firstExtreme() {
            return closedUp() ? low : high;
        }

        BigDecimal secondExtreme() {
            return closedUp() ? high : low;
        }

        /** The points a bar stands for: its two extremes inside its span and its close at its end, oldest first. */
        List<PricePoint> shapePoints() {
            return List.of(new PricePoint(firstExtremeAtMs(), firstExtreme()),
                    new PricePoint(secondExtremeAtMs(), secondExtreme()), new PricePoint(endMs, close));
        }
    }

    /**
     * An answer to a history request: the points, and what the source can say besides them.
     *
     * @param lastTrade the price of the last trade the source knows of before the requested range, with the time
     *                  it happened — evidence for "the asset has not traded since" (empty for a source with no
     *                  notion of a trade)
     * @param bars      for a source whose history is {@link HistoryKind#TRADE_PRICES}: every closed bar with trades
     *                  of the answer (it starts earlier than the range asked for, see the source), oldest first —
     *                  what {@code GapBridge} bridges a gap between two live ticks with; empty otherwise
     */
    record History(List<PricePoint> points, Optional<PricePoint> lastTrade, List<Bar> bars) {

        public History(List<PricePoint> points, Optional<PricePoint> lastTrade) {
            this(points, lastTrade, List.of());
        }

        public static History of(List<PricePoint> points) {
            return new History(points, Optional.empty(), List.of());
        }
    }

    /**
     * The source's name, what a market's {@code source} says: {@code hyperliquid}, {@code coinbase}, {@code polymarket}
     * or {@code synthetic}. Sources of one venue share the name, and with it the pace of {@link HistorySeeder}'s
     * queue (Hyperliquid's perpetuals and its spot pairs draw on one request budget).
     */
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

    /** What kind of numbers {@link #history} returns, see {@link HistoryKind}. */
    default HistoryKind historyKind() {
        return HistoryKind.SAME_AS_LIVE;
    }

    /**
     * {@link #history} plus what else the source knows, for a caller that has round boundaries in the range:
     * a source that builds a boundary price in its own way (a candle source: the close of the candle before the
     * boundary, not a shape point of the candle that contains it) adds a point at exactly each of
     * {@code boundariesMs} it can price. The default is {@link #history} alone.
     */
    default History historyOf(Market market, long fromMs, long toMs, long... boundariesMs) throws Exception {
        return History.of(history(market, fromMs, toMs));
    }

    /**
     * The request weight of one {@link #history} call over {@code spanMs} (the source's own unit; 0 when the
     * source's limits do not matter), so that {@link HistorySeeder} can pace a start-up that seeds many markets.
     */
    default int historyWeight(long spanMs) {
        return 0;
    }
}
