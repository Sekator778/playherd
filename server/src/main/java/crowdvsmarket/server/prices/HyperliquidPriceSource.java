package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import crowdvsmarket.server.markets.Market;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Hyperliquid perpetuals: the mark price of every listed PERP market from one {@code metaAndAssetCtxs}
 * answer per dex (docs/research/hl_multi_asset_live.md §3, §5, §10; markets_architecture.md §3.2).
 *
 * <p><b>Polling budget</b> (limit 1200 weight per minute per IP, {@code metaAndAssetCtxs} weighs 20):
 * the default dex every 3 s is 20 calls × 20 = 400 a minute, dex {@code xyz} every 6 s is 10 × 20 =
 * 200, together 600 — half the limit, the rest is for history ({@code candleSnapshot}: 20 plus 1 per
 * 60 rows) and the catalog. The schedules live on {@link PriceFeedService}.
 *
 * <p>Prices are read <b>by coin name</b> ({@link Market#sourceRef()}: {@code BTC}, {@code xyz:NVDA}),
 * never by index — {@code universe[i]} and {@code ctxs[i]} line up by position, but a position is
 * only stable for as long as the dex lists no new asset in front of it. A delisted asset
 * ({@code isDelisted}) has a frozen {@code markPx} and is skipped, so its market goes stale rather
 * than trading on a dead price; the poll tells {@link HyperliquidLiveState} about it, and the
 * catalog closes the market. {@code change24h} is {@code (markPx − prevDayPx) / prevDayPx}.
 *
 * <p><b>A bad answer fails the poll.</b> An HTTP 200 whose body is not {@code [meta, ctxs]} (an error
 * object, a changed wrapper, lists of different lengths) throws, so that the feed service reports the
 * failed poll in its throttled warning instead of counting an empty answer as a success while every
 * market of the dex quietly goes stale. A coin that is configured but absent from an otherwise valid
 * answer (a rename, say) costs only its own market the tick, and gets a warning of its own, once a
 * minute. A single garbage {@code markPx} costs only that market its tick, without a warning.
 *
 * <p><b>History</b> is the 1-minute {@code candleSnapshot}. The rows are read by their own time
 * {@code t} — not {@code response.get(0)}, which for the window {@code [at − 60 s, at + 60 s]} is the
 * candle of the minute <i>before</i> the one asked about (the bug of the old
 * {@code LiveRoundPrices}, hl_multi_asset_live.md §4.2).
 *
 * <p><b>Candles are trade prices, ticks are mark prices</b> (§3.4, §4.1, §10 p. 5): the mark is a median of
 * the oracle, the book and other exchanges, and a quiet asset's last trade can sit a percent away from it. So
 * the history is declared {@link HistoryKind#TRADE_PRICES} and {@link LiveMarketPrices} puts it to three uses.
 * <b>(1) The past of the first poll</b>: points older than every live tick of the market — what
 * {@link HistorySeeder} seeds a new market with, so that the first running round has an opening price and the
 * chart is not empty. <b>(2) A bridge over a gap between two live ticks</b>: {@link History#bars}, every closed
 * candle with trades of the answer, of which {@link GapBridge} makes the points of the gap — all or nothing, only
 * when every minute of the gap has such a candle and each live neighbour's mark lies inside the range its own
 * minute traded at (within {@code herd.prices.hyperliquid.max-basis}); that is what a redeploy needs, see
 * {@link LiveMarketPrices}. <b>(3) Evidence</b>: whether the asset traded inside a hole between live ticks (a trade
 * means it was not quiet) and its last trade before the hole ({@link History#lastTrade}), which is what lets a
 * quiet asset's last <i>live</i> price be carried across a hole. A trade price is never put into a hole next to
 * live ticks in any other way, and this source never carries a candle's close forward by itself — both used to,
 * and a flat mark settled as DOWN on the close of an old trade, and a path got a dip that the liquidation check
 * used.
 *
 * <p><b>What a candle gives.</b> Only a candle <i>with trades</i> ({@code n > 0}) holds a price: Hyperliquid's own
 * filler candles for quiet minutes ({@code n = 0}, {@code o = h = l = c =} the previous close, §4.3) say nothing
 * and are skipped, and a candle that is still open ({@code T} not yet past — its high and low change by the
 * second, and its {@code T} is in the future) is skipped as well, so that neither a seed nor a bridge quotes the
 * high of the minute in progress as a price.
 *
 * <p><b>Boundary convention: the price at time {@code t} is the last trade at or before {@code t}</b>, the same as
 * for a live tick ({@link LiveMarketPrices}: {@code P(t)} is the last tick with {@code at <= t}). A candle
 * {@code [t, T]} says: its close is the last trade up to {@code T} (a point at {@code T = t + 59 999 ms}); its low
 * and high happened some time inside the minute (two shape points at {@code t + 20 s} and {@code t + 40 s}, the
 * low first when the candle closed up, the high first when it closed down — a chart wants the extremes, not
 * their exact time, which nobody knows: {@link PriceSource.Bar#shapePoints}); its open is the <i>first</i> trade
 * at or after {@code t}, so it is <b>not</b> the price at {@code t}: that is the previous candle's close, one
 * millisecond earlier. Hence a candle that has a predecessor contributes three points; only the first row of an
 * answer, which has no predecessor to take the price at its start from, also contributes its open. A round that
 * ends at the minute {@code end} closes on the last trade of the minute before it, never on the first trade after
 * it (measured 2026-10-02: {@code endTime = end} returns the candle that starts at {@code end} as its last row,
 * {@code endTime = end − 1} stops at the minute before — hl_multi_asset_live.md §4.1); {@link #history}
 * therefore asks with {@code endTime = toMs − 1}. A bridge needs the candle that holds the live tick on the right
 * of the gap, which is asked for with {@code toMs} one millisecond past that tick.
 *
 * <p><b>The boundary price comes from the closed candle before the boundary, not from a shape point.</b> A shape
 * point is a candle's low or high at an invented time; a boundary price taken from one is a wick, and one that
 * falls between two shape points is stale. So {@link #historyOf} adds, for every boundary it is given, a point
 * at exactly that time with the close of the last candle with trades that closed at or before it — when that
 * was within one candle (60 s) of it; on a minute-aligned boundary that is the close one millisecond earlier.
 * Round boundaries are minute-aligned ({@link RoundAlignmentCheck} refuses to start otherwise).
 */
@ApplicationScoped
@Unremovable
public class HyperliquidPriceSource implements PriceSource {

    private static final Logger LOG = Logger.getLogger(HyperliquidPriceSource.class);

    public static final String NAME = "hyperliquid";
    public static final String FEED_MAIN = "hyperliquid";
    public static final String FEED_XYZ = "hyperliquid:xyz";
    /** The dexes this source polls; a market on any other dex would never get a tick. */
    public static final List<String> DEXES = List.of("", "xyz");

    static final long CANDLE_MS = 60_000L;
    /**
     * A history request starts this long before its range: to find the last trade of a quiet asset, and so that the
     * candle of the live tick on the left of a gap (which a bridge checks that tick against) is in the answer.
     */
    static final long LOOKBACK_MS = 30 * 60_000L;

    private final Supplier<HyperliquidInfoApi> api;
    private final PricesConfig config;
    private final HyperliquidLiveState state;
    private final LongSupplier clock;
    private final ThrottledLog warnings;

    @Inject
    public HyperliquidPriceSource(PriceHttpClients clients, PricesConfig config, HyperliquidLiveState state) {
        this(clients.hyperliquid(), config, state, System::currentTimeMillis);
    }

    HyperliquidPriceSource(Supplier<HyperliquidInfoApi> api, PricesConfig config, LongSupplier clock) {
        this(api, config, new HyperliquidLiveState(), clock);
    }

    HyperliquidPriceSource(Supplier<HyperliquidInfoApi> api, PricesConfig config, HyperliquidLiveState state,
                           LongSupplier clock) {
        this(api, config, state, clock, new ThrottledLog(LOG, clock, 60_000L));
    }

    HyperliquidPriceSource(Supplier<HyperliquidInfoApi> api, PricesConfig config, HyperliquidLiveState state,
                           LongSupplier clock, ThrottledLog warnings) {
        this.api = api;
        this.config = config;
        this.state = state;
        this.clock = clock;
        this.warnings = warnings;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Feed> feeds() {
        return List.of(
                new Feed(FEED_MAIN, config.hyperliquidStaleMs(), config.hyperliquidMaxGapMs(),
                        m -> NAME.equals(m.source()) && dexOf(m.sourceRef()).isEmpty()),
                new Feed(FEED_XYZ, config.hyperliquidXyzStaleMs(), config.hyperliquidMaxGapMs(),
                        m -> NAME.equals(m.source()) && "xyz".equals(dexOf(m.sourceRef()))));
    }

    /**
     * One poll of the dex of {@code feed}.
     *
     * @throws IllegalStateException when the answer is not {@code [meta, ctxs]} (see the class comment)
     */
    @Override
    public Map<String, Quote> poll(Feed feed, List<Market> markets) {
        String dex = FEED_XYZ.equals(feed.id()) ? "xyz" : "";
        Answer answer;
        try {
            answer = read(api.get().info(metaAndAssetCtxsRequest(dex)), markets);
        } catch (RuntimeException e) {
            for (Market market : markets) {
                state.reportPollFailed(market.id()); // an answer that never came is no evidence of a delisting
            }
            throw e;
        }
        long now = clock.getAsLong();
        for (Market market : answer.delisted()) {
            state.reportDelisted(market.id(), now);
        }
        for (Market market : answer.listed()) {
            state.reportListed(market.id());
        }
        String dexLabel = dex.isEmpty() ? "default" : dex;
        for (Market market : markets) {
            String key = "missing " + market.id();
            if (answer.absent().contains(market)) {
                warnings.failure(key, "Hyperliquid's " + dexLabel + " dex does not list the configured coin "
                        + market.sourceRef() + " (renamed? removed?): market " + market.id() + " gets no prices", null);
            } else {
                warnings.success(key, "coin " + market.sourceRef() + " in the answer of the " + dexLabel + " dex");
            }
        }
        return answer.quotes();
    }

    @Override
    public HistoryKind historyKind() {
        return HistoryKind.TRADE_PRICES;
    }

    /** {@code candleSnapshot} weighs 20 plus 1 per 60 rows (hl_multi_asset_live.md §5.1; the rounding is UNVERIFIED, so up). */
    @Override
    public int historyWeight(long spanMs) {
        long rows = (spanMs + LOOKBACK_MS + CANDLE_MS - 1) / CANDLE_MS;
        return 20 + (int) ((rows + 59) / 60);
    }

    @Override
    public List<PricePoint> history(Market market, long fromMs, long toMs) {
        return historyOf(market, fromMs, toMs).points();
    }

    @Override
    public History historyOf(Market market, long fromMs, long toMs, long... boundariesMs) {
        // endTime = toMs - 1: the candle that starts at toMs holds trades after it (class comment: boundary convention)
        JsonNode rows = api.get().info(candleSnapshotRequest(market.sourceRef(), fromMs - LOOKBACK_MS, toMs - 1));
        return historyAnswer(rows, fromMs, toMs, clock.getAsLong(), boundariesMs);
    }

    /** The dex of a coin name: {@code "xyz"} for {@code xyz:NVDA}, empty for {@code BTC}. */
    public static String dexOf(String coin) {
        int colon = coin.indexOf(':');
        return colon < 0 ? "" : coin.substring(0, colon);
    }

    /** {@code {"type":"metaAndAssetCtxs"}}, with {@code "dex"} for a builder dex. */
    public static ObjectNode metaAndAssetCtxsRequest(String dex) {
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("type", "metaAndAssetCtxs");
        if (!dex.isEmpty()) {
            body.put("dex", dex);
        }
        return body;
    }

    /** {@code {"type":"candleSnapshot","req":{"coin":…,"interval":"1m","startTime":…,"endTime":…}}}. */
    public static ObjectNode candleSnapshotRequest(String coin, long startMs, long endMs) {
        ObjectNode req = JsonNodeFactory.instance.objectNode().put("coin", coin).put("interval", "1m")
                .put("startTime", startMs).put("endTime", endMs);
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("type", "candleSnapshot");
        body.set("req", req);
        return body;
    }

    /**
     * What one {@code [meta, ctxs]} answer says about the requested markets: the quotes of those with
     * a usable {@code markPx}; which of them {@code isDelisted} (their price is frozen and skipped);
     * which are live (listed and not delisted, whether or not the price was usable); and which coins
     * the answer does not list at all.
     */
    record Answer(Map<String, Quote> quotes, List<Market> delisted, List<Market> listed, Set<Market> absent) {
    }

    /**
     * Reads {@code [meta, ctxs]}: {@code markPx} (and the 24-hour change) of each of {@code markets}
     * by its coin name. A market whose coin is not in the answer, is delisted, or has no usable
     * {@code markPx} is simply not in the result.
     *
     * @throws IllegalStateException when the answer is not {@code [meta, ctxs]} — an array of two,
     *                               the first with a {@code universe} array, the second an array of the
     *                               same length
     */
    public static Map<String, Quote> parse(JsonNode response, Collection<Market> markets) {
        return read(response, markets).quotes();
    }

    static Answer read(JsonNode response, Collection<Market> markets) {
        if (response == null || !response.isArray() || response.size() < 2) {
            throw new IllegalStateException("unexpected answer of metaAndAssetCtxs, not [meta, ctxs]: "
                    + abbreviate(response));
        }
        JsonNode universe = response.get(0).path("universe");
        JsonNode ctxs = response.get(1);
        if (!universe.isArray() || !ctxs.isArray() || universe.size() != ctxs.size()) {
            throw new IllegalStateException("unexpected answer of metaAndAssetCtxs: meta.universe has "
                    + (universe.isArray() ? universe.size() : "no") + " entries, ctxs " + (ctxs.isArray() ? ctxs.size()
                    : "is no array"));
        }
        Map<String, Integer> indexByName = new HashMap<>();
        Set<String> delistedNames = new HashSet<>();
        for (int i = 0; i < universe.size(); i++) {
            JsonNode asset = universe.get(i);
            String name = asset.path("name").asText("");
            if (name.isEmpty()) {
                continue;
            }
            if (asset.path("isDelisted").asBoolean(false)) {
                delistedNames.add(name);
            } else {
                indexByName.putIfAbsent(name, i);
            }
        }
        Map<String, Quote> quotes = new LinkedHashMap<>();
        List<Market> delisted = new ArrayList<>();
        List<Market> listed = new ArrayList<>();
        Set<Market> absent = new HashSet<>();
        for (Market market : markets) {
            Integer i = indexByName.get(market.sourceRef());
            if (i == null) {
                if (delistedNames.contains(market.sourceRef())) {
                    delisted.add(market);
                } else {
                    absent.add(market);
                }
                continue;
            }
            listed.add(market);
            JsonNode ctx = ctxs.get(i);
            BigDecimal mark = Dec.of(ctx.path("markPx"));
            if (mark == null || mark.signum() <= 0) {
                continue;
            }
            quotes.put(market.id(), new Quote(mark, relativeChange(mark, Dec.of(ctx.path("prevDayPx")))));
        }
        return new Answer(quotes, delisted, listed, absent);
    }

    private static String abbreviate(JsonNode node) {
        String text = String.valueOf(node);
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    /** {@code (now − then) / then}, or null when there is no usable earlier price. */
    static Double relativeChange(BigDecimal now, BigDecimal then) {
        if (now == null || then == null || then.signum() <= 0) {
            return null;
        }
        return now.subtract(then).divide(then, 8, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * What a {@code candleSnapshot} answer says about {@code [fromMs, toMs]}: the points of its closed candles
     * with trades ({@link #candlePoints}), a point at each boundary that the close of a candle before it can
     * price ({@link #boundaryPoint}), the last trade before the range ({@link #lastTradeBefore}), and those
     * candles themselves, all of the answer ({@link #bars}), for a bridge over a gap between live ticks.
     *
     * @param nowMs the clock: a candle whose {@code T} is not before it is still open and says nothing yet
     */
    static History historyAnswer(JsonNode rows, long fromMs, long toMs, long nowMs, long... boundariesMs) {
        List<Candle> candles = candles(rows);
        List<PricePoint> points = candlePoints(candles, fromMs, toMs, nowMs);
        for (long boundary : boundariesMs) {
            if (boundary < fromMs || boundary > toMs) {
                continue;
            }
            Optional<PricePoint> priced = boundaryPoint(candles, boundary, nowMs);
            if (priced.isPresent() && !priceAlreadyAt(points, boundary)) {
                points.add(priced.get());
            }
        }
        points.sort((a, b) -> Long.compare(a.atMs(), b.atMs()));
        return new History(points, lastTradeBefore(candles, fromMs, nowMs), bars(candles, nowMs));
    }

    /** The closed candles with trades of an answer as bars, oldest first: what a minute of the market traded at. */
    static List<PriceSource.Bar> bars(List<Candle> candles, long nowMs) {
        List<PriceSource.Bar> bars = new ArrayList<>();
        for (Candle c : candles) {
            if (c.closed(nowMs) && c.hasTrades()) {
                bars.add(c.toBar());
            }
        }
        return bars;
    }

    /**
     * On a minute-aligned boundary the close of the candle before it sits one millisecond earlier: that point
     * <i>is</i> the price at the boundary, with its own true time, and nothing is added (the settlement's
     * {@code sourceTs} stays the time of that close). Anywhere else the last point at or before the boundary is a
     * wick of the minute around it, or a close that is too old to be the price now: a point is added.
     */
    private static boolean priceAlreadyAt(List<PricePoint> points, long boundaryMs) {
        return points.stream().anyMatch(p -> p.atMs() >= boundaryMs - 1 && p.atMs() <= boundaryMs);
    }

    /**
     * The 1-minute candle rows as price points inside {@code [fromMs, toMs]}, oldest first, by the
     * boundary convention of the class comment: the close at {@code T}, the two extremes inside the
     * minute, and — for the first row only — the open. Rows are taken by their own {@code t} and
     * sorted by it; a row that is not whole (a missing or garbage field) and a filler without trades are
     * skipped. The first row's open is not made into a point at or after {@code toMs}. Every candle counts
     * as closed here; {@link #historyAnswer} knows the clock.
     */
    static List<PricePoint> candlePoints(JsonNode rows, long fromMs, long toMs) {
        return candlePoints(candles(rows), fromMs, toMs, Long.MAX_VALUE);
    }

    private static List<PricePoint> candlePoints(List<Candle> candles, long fromMs, long toMs, long nowMs) {
        List<PricePoint> out = new ArrayList<>();
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            if (!c.closed(nowMs) || !c.hasTrades()) {
                continue;
            }
            if (i == 0 && c.t() < toMs) {
                add(out, c.t(), c.open(), fromMs, toMs); // no earlier candle to take the price at t from
            }
            for (PricePoint p : c.toBar().shapePoints()) { // the extremes inside the minute, the close at its end
                add(out, p.atMs(), p.raw(), fromMs, toMs);
            }
        }
        out.sort((a, b) -> Long.compare(a.atMs(), b.atMs()));
        return out;
    }

    /**
     * The price at {@code boundaryMs} from the candle <i>before</i> it: the close of the newest closed candle
     * with trades that closed at or before the boundary, when that was at most one candle width earlier — "the
     * last trade at or before the boundary", known from the minute candles up to the minute the boundary falls
     * in. Empty when there is none that recent (a quiet asset has no price at the boundary).
     */
    static Optional<PricePoint> boundaryPoint(List<Candle> candles, long boundaryMs, long nowMs) {
        Candle best = null;
        for (Candle c : candles) {
            if (c.closed(nowMs) && c.hasTrades() && c.closeAt() <= boundaryMs && (best == null || c.t() > best.t())) {
                best = c;
            }
        }
        if (best == null || boundaryMs - best.closeAt() > CANDLE_MS) {
            return Optional.empty();
        }
        return Optional.of(new PricePoint(boundaryMs, best.close()));
    }

    /** The close of the newest closed candle with trades that closed before {@code fromMs}, at its closing time. */
    static Optional<PricePoint> lastTradeBefore(List<Candle> candles, long fromMs, long nowMs) {
        Candle best = null;
        for (Candle c : candles) {
            if (c.closed(nowMs) && c.hasTrades() && c.closeAt() < fromMs && (best == null || c.t() > best.t())) {
                best = c;
            }
        }
        return best == null ? Optional.empty() : Optional.of(new PricePoint(best.closeAt(), best.close()));
    }

    /** The whole rows of an answer, sorted by their own time; whatever is not a candle is left out. */
    static List<Candle> candles(JsonNode rows) {
        List<Candle> candles = new ArrayList<>();
        if (rows == null || !rows.isArray()) {
            return candles;
        }
        for (JsonNode row : rows) {
            Candle candle = Candle.of(row);
            if (candle != null) {
                candles.add(candle);
            }
        }
        candles.sort((a, b) -> Long.compare(a.t(), b.t()));
        return candles;
    }

    /**
     * One whole candle row. {@code trades} is the row's {@code n} (the number of trades), {@code -1} when the row
     * has none: only an explicit 0 makes a row a filler.
     */
    record Candle(long t, long closeAt, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                  long trades) {

        /** The row, or null when its time or any of its four prices is missing or garbage. */
        static Candle of(JsonNode row) {
            long t = row.path("t").asLong(Long.MIN_VALUE);
            BigDecimal open = Dec.of(row.path("o"));
            BigDecimal high = Dec.of(row.path("h"));
            BigDecimal low = Dec.of(row.path("l"));
            BigDecimal close = Dec.of(row.path("c"));
            if (t == Long.MIN_VALUE || open == null || high == null || low == null || close == null) {
                return null;
            }
            long closeAt = row.path("T").asLong(t + CANDLE_MS - 1);
            if (closeAt < t) {
                closeAt = t + CANDLE_MS - 1; // a candle cannot end before it starts
            }
            return new Candle(t, closeAt, open, high, low, close, row.path("n").asLong(-1L));
        }

        /** A minute with trades holds a price; a filler ({@code n = 0}) repeats the previous close. */
        boolean hasTrades() {
            return trades != 0;
        }

        PriceSource.Bar toBar() {
            return new PriceSource.Bar(t, closeAt, open, high, low, close);
        }

        /** {@code T} is past: no trade will change the row any more (hl_multi_asset_live.md §4.1: closed means T < now). */
        boolean closed(long nowMs) {
            return closeAt < nowMs;
        }
    }

    private static void add(List<PricePoint> out, long atMs, BigDecimal price, long fromMs, long toMs) {
        if (atMs >= fromMs && atMs <= toMs && price.signum() > 0) {
            out.add(new PricePoint(atMs, price));
        }
    }
}
