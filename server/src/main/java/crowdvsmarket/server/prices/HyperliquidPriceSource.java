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
 * {@code LiveRoundPrices}, hl_multi_asset_live.md §4.2). Candles are trade prices, not mark prices —
 * good enough to fill a hole, which is what they are for.
 *
 * <p><b>Boundary convention of history: the price at time {@code t} is the last trade at or before
 * {@code t}</b>, the same as for a live tick ({@link LiveMarketPrices}: {@code P(t)} is the last tick
 * with {@code at <= t}). A candle {@code [t, T]} says: its close is the last trade up to {@code T}
 * (a point at {@code T = t + 59 999 ms}); its low and high happened some time inside the minute (two
 * shape points at {@code t + 20 s} and {@code t + 40 s}, the low first when the candle closed up, the
 * high first when it closed down — the liquidation check needs the extremes, not their exact time);
 * its open is the <i>first</i> trade at or after {@code t}, so it is <b>not</b> the price at
 * {@code t}: that is the previous candle's close, one millisecond earlier. Hence a candle that has a
 * predecessor contributes three points; only the first row of an answer, which has no predecessor to
 * take the price at its start from, also contributes its open. So a round that ends at the minute
 * {@code end} closes on the last trade of the minute before it, never on the first trade after it
 * (measured 2026-10-02: {@code endTime = end} returns the candle that starts at {@code end} as its
 * last row, {@code endTime = end − 1} stops at the minute before — hl_multi_asset_live.md §4.1);
 * {@link #history} therefore asks with {@code endTime = toMs − 1}, and a point at or after {@code toMs}
 * is never made from a candle's open. The same holds at {@code start}, where the open of the round
 * is the previous minute's close.
 *
 * <p><b>Quiet assets.</b> An asset with no trades has no candle rows at the tail of an answer — a
 * missing last minute is the normal state of a quiet one (hl_multi_asset_live.md §4.3), and the
 * builder-dex stocks and indices are quiet outside their exchanges' hours. So that a hole behind a
 * restart does not cancel a round of such a market, {@link #history} asks from 30 minutes before the
 * hole (to find the last trade) and, for a tail that is at least 2 minutes old and therefore final,
 * carries the last candle's close forward in points 10 s apart (inside the 15 s staleness limit),
 * as the research recommends (§10.4). A tail younger than 2 minutes is not carried: its candle may
 * still appear.
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
    static final long FIRST_EXTREME_OFFSET_MS = 20_000L;
    static final long SECOND_EXTREME_OFFSET_MS = 40_000L;
    /** A history request starts this long before its hole, to find the last trade of a quiet asset. */
    static final long LOOKBACK_MS = 30 * 60_000L;
    /** Spacing of the points that carry a quiet asset's last close forward: inside the 15 s staleness limit. */
    static final long CARRY_STEP_MS = 10_000L;
    /** A candle tail this much older than now is final: no trade will appear in it any more. */
    static final long SETTLED_AFTER_MS = 120_000L;

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
        Answer answer = read(api.get().info(metaAndAssetCtxsRequest(dex)), markets);
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
    public List<PricePoint> history(Market market, long fromMs, long toMs) {
        // endTime = toMs - 1: the candle that starts at toMs holds trades after it (class comment: boundary convention)
        JsonNode rows = api.get().info(candleSnapshotRequest(market.sourceRef(), fromMs - LOOKBACK_MS, toMs - 1));
        return historyPoints(rows, fromMs, toMs, clock.getAsLong());
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
     * The 1-minute candle rows as price points inside {@code [fromMs, toMs]}, oldest first, by the
     * boundary convention of the class comment: the close at {@code T}, the two extremes inside the
     * minute, and — for the first row only — the open. Rows are taken by their own {@code t} and
     * sorted by it; a row that is not whole (a missing or garbage field) is skipped. The first row's
     * open is not made into a point at or after {@code toMs}.
     */
    static List<PricePoint> candlePoints(JsonNode rows, long fromMs, long toMs) {
        List<PricePoint> out = new ArrayList<>();
        if (rows == null || !rows.isArray()) {
            return out;
        }
        List<Candle> candles = new ArrayList<>();
        for (JsonNode row : rows) {
            Candle candle = Candle.of(row);
            if (candle != null) {
                candles.add(candle);
            }
        }
        candles.sort((a, b) -> Long.compare(a.t(), b.t()));
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            boolean up = c.close().compareTo(c.open()) >= 0;
            if (i == 0 && c.t() < toMs) {
                add(out, c.t(), c.open(), fromMs, toMs); // no earlier candle to take the price at t from
            }
            add(out, c.t() + FIRST_EXTREME_OFFSET_MS, up ? c.low() : c.high(), fromMs, toMs);
            add(out, c.t() + SECOND_EXTREME_OFFSET_MS, up ? c.high() : c.low(), fromMs, toMs);
            add(out, c.closeAt(), c.close(), fromMs, toMs);
        }
        out.sort((a, b) -> Long.compare(a.atMs(), b.atMs()));
        return out;
    }

    /** One whole candle row. */
    private record Candle(long t, long closeAt, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {

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
            return new Candle(t, row.path("T").asLong(t + CANDLE_MS - 1), open, high, low, close);
        }
    }

    /**
     * {@link #candlePoints} plus the carry-forward of a quiet asset (see the class comment): when the
     * newest row closes more than {@link #CARRY_STEP_MS} before {@code toMs} and {@code toMs} itself
     * is final ({@link #SETTLED_AFTER_MS} before {@code nowMs}), the last close continues in points
     * {@link #CARRY_STEP_MS} apart up to {@code toMs}.
     */
    static List<PricePoint> historyPoints(JsonNode rows, long fromMs, long toMs, long nowMs) {
        List<PricePoint> points = candlePoints(rows, fromMs, toMs);
        if (rows == null || !rows.isArray() || toMs > nowMs - SETTLED_AFTER_MS) {
            return points;
        }
        long newestT = Long.MIN_VALUE;
        long closedAt = 0;
        BigDecimal lastClose = null;
        for (JsonNode row : rows) {
            Candle c = Candle.of(row);
            if (c != null && c.t() > newestT && c.close().signum() > 0) {
                newestT = c.t();
                closedAt = c.closeAt();
                lastClose = c.close();
            }
        }
        if (lastClose == null || closedAt >= toMs - CARRY_STEP_MS) {
            return points;
        }
        for (long at = Math.max(closedAt + CARRY_STEP_MS, fromMs); at <= toMs; at += CARRY_STEP_MS) {
            points.add(new PricePoint(at, lastClose));
        }
        points.sort((a, b) -> Long.compare(a.atMs(), b.atMs()));
        return points;
    }

    private static void add(List<PricePoint> out, long atMs, BigDecimal price, long fromMs, long toMs) {
        if (atMs >= fromMs && atMs <= toMs && price.signum() > 0) {
            out.add(new PricePoint(atMs, price));
        }
    }
}
