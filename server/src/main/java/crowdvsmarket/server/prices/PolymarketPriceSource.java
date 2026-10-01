package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import crowdvsmarket.server.markets.Market;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Polymarket ODDS markets: the live probability of each listed market's outcome 0 — the CLOB
 * midpoint of its token ({@link Market#sourceRef()}) — from ONE {@code POST /midpoints} per poll for
 * all listed tokens (docs/research/polymarket_data_api.md §3; markets_architecture.md §3.2). The
 * values come back as strings; the answer is not cached by Cloudflare and takes about 0.1 s.
 *
 * <p>A token that is missing from an answer that contains others is reported to
 * {@link PolymarketLiveState} (the catalog closes the market when it stays missing for 60 s). When
 * <i>none</i> of two or more requested tokens comes back that is taken for an outage of the CLOB
 * rather than for all those markets closing at once, and nothing is reported: a mass closing
 * cancels every round of every Polymarket market.
 *
 * <p><b>History</b> is the data API's 60-second buckets, {@code GET /v2/prices-history} (series of
 * order-book midpoints, §4.4); closed buckets there trail the present by about 5 minutes, but the
 * series ends with an exact tick, so a hole in a round that ended a minute ago may be filled only
 * minutes later — the scheduler's retry covers that. The grid is not gapless: a real 24-hour series
 * had 55 steps of 120 s and one of 329 s between its 1 436 points (polymarket_data_api.md §4.2), and
 * the bucket stamped exactly at a round's end may be the one that is missing. A midpoint series says
 * "the price is this until the next point", so {@link #historyPoints} carries each bucket's price
 * forward in steps of at most {@link #CARRY_STEP_MS} — to the next bucket when it is at most
 * {@link #MAX_CARRY_MS} away (a longer stretch stays a hole: ten minutes of silence is no flat line),
 * and to the end of the requested range when the last bucket is within one bucket width of it. The
 * carried points sit between two real ones with the older one's price, as a live tick would.
 * {@code change24h} is Gamma's absolute {@code oneDayPriceChange} (from {@link PolymarketLiveState})
 * turned into the change relative to the price a day ago ({@link #relativeChange}).
 */
@ApplicationScoped
@Unremovable
public class PolymarketPriceSource implements PriceSource {

    private static final Logger LOG = Logger.getLogger(PolymarketPriceSource.class);

    public static final String NAME = "polymarket";
    public static final String FEED = "polymarket";

    /** The data API serves an explicit window of at most 15 days. */
    static final long MAX_WINDOW_MS = 15L * 24 * 3_600_000L;
    static final int BUCKET_SECONDS = 60;
    static final long BUCKET_MS = BUCKET_SECONDS * 1_000L;
    /** The carried points of a history stretch sit at most this far apart (the feed's maximum gap is 90 s). */
    static final long CARRY_STEP_MS = 30_000L;
    /** Two buckets further apart than this (10 minutes; the longest gap seen in real series was 329 s) are not bridged. */
    static final long MAX_CARRY_MS = 600_000L;
    private static final int MAX_PAGES = 5;

    private final Supplier<PolymarketClobApi> clob;
    private final Supplier<PolymarketDataApi> data;
    private final PolymarketLiveState state;
    private final PricesConfig config;
    private final LongSupplier clock;

    @Inject
    public PolymarketPriceSource(PriceHttpClients clients, PolymarketLiveState state, PricesConfig config) {
        this(clients.clob(), clients.data(), state, config, System::currentTimeMillis);
    }

    PolymarketPriceSource(Supplier<PolymarketClobApi> clob, Supplier<PolymarketDataApi> data,
                          PolymarketLiveState state, PricesConfig config, LongSupplier clock) {
        this.clob = clob;
        this.data = data;
        this.state = state;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Feed> feeds() {
        return List.of(new Feed(FEED, config.polymarketStaleMs(), config.polymarketMaxGapMs(),
                m -> NAME.equals(m.source())));
    }

    @Override
    public Map<String, Quote> poll(Feed feed, List<Market> markets) throws IOException {
        ArrayNode body = JsonNodeFactory.instance.arrayNode();
        for (Market market : markets) {
            body.addObject().put("token_id", market.sourceRef());
        }
        JsonNode answer = clob.get().midpoints(body);
        if (answer == null || !answer.isObject()) {
            throw new IOException("unexpected answer of POST /midpoints: " + answer);
        }
        return parse(answer, markets);
    }

    /** The midpoints of {@code markets}, and the report of the tokens that were not in {@code answer}. */
    Map<String, Quote> parse(JsonNode answer, List<Market> markets) {
        long now = clock.getAsLong();
        Map<String, Quote> out = new LinkedHashMap<>();
        List<Market> missing = new ArrayList<>();
        for (Market market : markets) {
            JsonNode value = answer.get(market.sourceRef());
            if (value == null || value.isNull()) {
                missing.add(market);
                continue;
            }
            state.reportPresent(market.id());
            BigDecimal mid = Dec.of(value);
            if (mid == null || mid.signum() <= 0 || mid.compareTo(BigDecimal.ONE) > 0) {
                LOG.warnf("midpoint of %s is not a probability: %s", market.id(), value);
                continue;
            }
            out.put(market.id(), new Quote(mid, relativeChange(mid, state.oneDayChange(market.id()))));
        }
        if (!missing.isEmpty()) {
            boolean outage = markets.size() >= 2 && missing.size() == markets.size();
            if (outage) {
                LOG.warnf("POST /midpoints returned none of %d token(s): taken for an outage, no market reported missing",
                        markets.size());
            } else {
                for (Market market : missing) {
                    state.reportMissing(market.id(), now);
                }
            }
        }
        return out;
    }

    /**
     * The change relative to the price a day ago, {@code (now − then) / then}, from Gamma's absolute
     * one: {@code absoluteChange / (now − absoluteChange)}. This is exactly the number for which the
     * web's percentage-points formula {@code price − price / (1 + change24h)} gives the absolute
     * change back — {@code now − now·then/now = now − then} — to double precision (the division is
     * not rounded to a fixed number of places), so an odds card shows Gamma's own figure. Null when
     * the price a day ago would not be positive: no relative change exists then.
     */
    static Double relativeChange(BigDecimal now, Double absoluteChange) {
        if (absoluteChange == null) {
            return null;
        }
        BigDecimal delta = BigDecimal.valueOf(absoluteChange);
        BigDecimal then = now.subtract(delta);
        if (then.signum() <= 0) {
            return null;
        }
        return delta.divide(then, MathContext.DECIMAL128).doubleValue();
    }

    @Override
    public List<PricePoint> history(Market market, long fromMs, long toMs) {
        long start = Math.max(fromMs, toMs - MAX_WINDOW_MS);
        long startSeconds = Math.floorDiv(start, 1000L);
        long endSeconds = Math.floorDiv(toMs + 999L, 1000L);
        List<PricePoint> buckets = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode answer = data.get().pricesHistory(market.sourceRef(), startSeconds, endSeconds, BUCKET_SECONDS,
                    cursor);
            buckets.addAll(bucketPoints(answer, fromMs, toMs));
            JsonNode pagination = answer == null ? null : answer.path("pagination");
            if (pagination == null || !pagination.path("has_more").asBoolean(false)
                    || pagination.path("next_cursor").asText("").isEmpty()) {
                break;
            }
            cursor = pagination.path("next_cursor").asText();
        }
        buckets.sort((a, b) -> Long.compare(a.atMs(), b.atMs()));
        return carryForward(buckets, toMs); // once, over every page: "the last bucket" is the last of all of them
    }

    /**
     * {@code {"data":[{"timestamp":<s>,"price":0.525,…}]}} → the buckets inside {@code [fromMs, toMs]}
     * with each price carried forward over the stretch to the next bucket (and to {@code toMs} from
     * the last one), see the class comment.
     */
    static List<PricePoint> historyPoints(JsonNode answer, long fromMs, long toMs) {
        return carryForward(bucketPoints(answer, fromMs, toMs), toMs);
    }

    /** The rows of the answer as they are: one point per bucket (and the terminal tick), oldest first. */
    static List<PricePoint> bucketPoints(JsonNode answer, long fromMs, long toMs) {
        List<PricePoint> out = new ArrayList<>();
        if (answer == null) {
            return out;
        }
        for (JsonNode row : answer.path("data")) {
            long atMs = row.path("timestamp").asLong(Long.MIN_VALUE);
            BigDecimal price = Dec.of(row.path("price"));
            if (atMs == Long.MIN_VALUE || price == null) {
                continue;
            }
            atMs *= 1000L;
            if (atMs >= fromMs && atMs <= toMs && price.signum() > 0) {
                out.add(new PricePoint(atMs, price));
            }
        }
        out.sort((a, b) -> Long.compare(a.atMs(), b.atMs()));
        return out;
    }

    /**
     * Adds, between every two neighbouring points at most {@link #MAX_CARRY_MS} apart, points
     * {@link #CARRY_STEP_MS} apart that repeat the earlier one's price; and the same from the last
     * point up to {@code toMs} when it is within one bucket width of it. Longer stretches get nothing.
     */
    static List<PricePoint> carryForward(List<PricePoint> points, long toMs) {
        List<PricePoint> out = new ArrayList<>(points.size() * 2);
        for (int i = 0; i < points.size(); i++) {
            PricePoint p = points.get(i);
            out.add(p);
            boolean last = i == points.size() - 1;
            long until = last ? toMs : points.get(i + 1).atMs();
            long reach = last ? BUCKET_MS : MAX_CARRY_MS;
            if (until - p.atMs() > reach) {
                continue; // a stretch that stays a hole
            }
            for (long at = p.atMs() + CARRY_STEP_MS; last ? at <= until : at < until; at += CARRY_STEP_MS) {
                out.add(new PricePoint(at, p.raw()));
            }
        }
        return out;
    }
}
