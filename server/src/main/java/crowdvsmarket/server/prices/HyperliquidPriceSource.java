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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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
 * than trading on a dead price. {@code change24h} is {@code (markPx − prevDayPx) / prevDayPx}.
 *
 * <p><b>History</b> is the 1-minute {@code candleSnapshot}. The rows are read by their own time
 * {@code t} — not {@code response.get(0)}, which for the window {@code [at − 60 s, at + 60 s]} is the
 * candle of the minute <i>before</i> the one asked about (the bug of the old
 * {@code LiveRoundPrices}, hl_multi_asset_live.md §4.2). Each candle becomes four points: open at
 * {@code t}, its two extremes at {@code t + 20 s} and {@code t + 40 s} (the low first when the candle
 * closed up, the high first when it closed down), close at {@code T}; so a rebuilt hole has a point
 * every 20 s, inside the 30 s maximum gap. Candles are trade prices, not mark prices — good enough
 * to fill a hole, which is what they are for.
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
    private final LongSupplier clock;

    @Inject
    public HyperliquidPriceSource(PriceHttpClients clients, PricesConfig config) {
        this(clients.hyperliquid(), config, System::currentTimeMillis);
    }

    HyperliquidPriceSource(Supplier<HyperliquidInfoApi> api, PricesConfig config, LongSupplier clock) {
        this.api = api;
        this.config = config;
        this.clock = clock;
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

    @Override
    public Map<String, Quote> poll(Feed feed, List<Market> markets) {
        String dex = FEED_XYZ.equals(feed.id()) ? "xyz" : "";
        return parse(api.get().info(metaAndAssetCtxsRequest(dex)), markets);
    }

    @Override
    public List<PricePoint> history(Market market, long fromMs, long toMs) {
        JsonNode rows = api.get().info(candleSnapshotRequest(market.sourceRef(), fromMs - LOOKBACK_MS, toMs));
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
     * Reads {@code [meta, ctxs]}: {@code markPx} (and the 24-hour change) of each of {@code markets}
     * by its coin name. A market whose coin is not in the answer, is delisted, or has no usable
     * {@code markPx} is simply not in the result.
     */
    public static Map<String, Quote> parse(JsonNode response, Collection<Market> markets) {
        Map<String, Quote> out = new LinkedHashMap<>();
        if (response == null || !response.isArray() || response.size() < 2) {
            return out;
        }
        JsonNode universe = response.get(0).path("universe");
        JsonNode ctxs = response.get(1);
        Map<String, Integer> indexByName = new HashMap<>();
        int n = Math.min(universe.size(), ctxs.size());
        for (int i = 0; i < n; i++) {
            JsonNode asset = universe.get(i);
            String name = asset.path("name").asText("");
            if (!name.isEmpty() && !asset.path("isDelisted").asBoolean(false)) {
                indexByName.putIfAbsent(name, i);
            }
        }
        for (Market market : markets) {
            Integer i = indexByName.get(market.sourceRef());
            if (i == null) {
                continue;
            }
            JsonNode ctx = ctxs.get(i);
            BigDecimal mark = Dec.of(ctx.path("markPx"));
            if (mark == null || mark.signum() <= 0) {
                continue;
            }
            out.put(market.id(), new Quote(mark, relativeChange(mark, Dec.of(ctx.path("prevDayPx")))));
        }
        return out;
    }

    /** {@code (now − then) / then}, or null when there is no usable earlier price. */
    static Double relativeChange(BigDecimal now, BigDecimal then) {
        if (now == null || then == null || then.signum() <= 0) {
            return null;
        }
        return now.subtract(then).divide(then, 8, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * The 1-minute candle rows as price points inside {@code [fromMs, toMs]}, oldest first (see the
     * class comment for the four points of a candle). Rows are taken by their own {@code t}.
     */
    static List<PricePoint> candlePoints(JsonNode rows, long fromMs, long toMs) {
        List<PricePoint> out = new ArrayList<>();
        if (rows == null || !rows.isArray()) {
            return out;
        }
        for (JsonNode row : rows) {
            long t = row.path("t").asLong(Long.MIN_VALUE);
            BigDecimal open = Dec.of(row.path("o"));
            BigDecimal high = Dec.of(row.path("h"));
            BigDecimal low = Dec.of(row.path("l"));
            BigDecimal close = Dec.of(row.path("c"));
            if (t == Long.MIN_VALUE || open == null || high == null || low == null || close == null) {
                continue;
            }
            long closeAt = row.path("T").asLong(t + CANDLE_MS - 1);
            boolean up = close.compareTo(open) >= 0;
            add(out, t, open, fromMs, toMs);
            add(out, t + FIRST_EXTREME_OFFSET_MS, up ? low : high, fromMs, toMs);
            add(out, t + SECOND_EXTREME_OFFSET_MS, up ? high : low, fromMs, toMs);
            add(out, closeAt, close, fromMs, toMs);
        }
        out.sort((a, b) -> Long.compare(a.atMs(), b.atMs()));
        return out;
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
            long t = row.path("t").asLong(Long.MIN_VALUE);
            BigDecimal close = Dec.of(row.path("c"));
            boolean whole = Dec.of(row.path("o")) != null && Dec.of(row.path("h")) != null
                    && Dec.of(row.path("l")) != null;
            if (t > newestT && whole && close != null && close.signum() > 0) {
                newestT = t;
                closedAt = row.path("T").asLong(t + CANDLE_MS - 1);
                lastClose = close;
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
