package crowdvsmarket.server.prices;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import crowdvsmarket.server.markets.Market;
import io.quarkus.arc.Unremovable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Hyperliquid <b>spot</b> pairs (HYPE/USDC, {@code @107}): the mid that Hyperliquid itself quotes for the pair. HYPE's
 * main spot liquidity is on Hyperliquid, so for HYPE it <i>is</i> the primary venue
 * (docs/research/primary_sources.md §2.3, from https://hyperliquid.gitbook.io/hyperliquid-docs/hypercore/oracle.md —
 * UNVERIFIED there for the venue's share). The market's {@code sourceRef} is the pair's name from Hyperliquid's spot
 * meta, {@code "@<index>"} — looked up by the catalog by the token names, never hard-coded
 * ({@link #pairName}).
 *
 * <p><b>Live prices come over the WebSocket</b>, {@code wss://api.hyperliquid.xyz/ws}, subscription
 * {@code {"method":"subscribe","subscription":{"type":"allMids"}}}, on a virtual thread of {@link StreamSupervisor}.
 * A message is {@code {"channel":"allMids","data":{"mids":{"@107":"87.8835",…}}}} — every perp, every spot pair and
 * every outcome market, 1 141 keys, 20 kB — and this source keeps the value of each pair it is asked for. Measured
 * 2026-10-03 (11:08–11:15 UTC, from Germany): <b>one message every 5.0 s</b> (4.85–5.21 s over 74 messages, the
 * first one 5 s after subscribing), the HYPE/USDC value changed between 43 of 73 consecutive messages. So a tick of
 * this feed moves every 5 s whatever the poll period is, and the freshness limit
 * ({@code herd.prices.hyperliquid.spot-fresh-ms}, 8 s) is longer than the cadence. (The same WebSocket has
 * {@code bbo} per coin, 2.75 messages a second for @107 — measured the same day, not used: the brief says
 * {@code allMids}.) A {@code {"method":"ping"}} goes out every 30 s, as the docs ask of a connection that may be quiet
 * (hl_multi_asset_live.md §6).
 *
 * <p><b>A poll is answered from memory</b> while the pair's value is at most {@code spot-fresh-ms} old. When the
 * stream is down or silent, one REST read of the same venue answers for every pair that is stale at once:
 * {@code POST /info {"type":"spotMetaAndAssetCtxs"}}, the {@code midPx} of the pair's entry in {@code ctxs}. The entry
 * is found by its {@code coin} field — <b>not by position</b>: measured 2026-10-03, {@code universe} lists 330 pairs
 * whose positions differ from their {@code index} (HYPE/USDC is the 106th, its {@code index} is 107), while {@code ctxs}
 * (907 entries, the spot pairs first) sits at the pair's {@code index}. The answer weighs 20 and is 305 kB, one read
 * a poll is a full outage's price. A poll fails only when no pair got a price.
 *
 * <p><b>{@code change24h}</b> is {@code (mid − prevDayPx) / prevDayPx}. {@code allMids} has no {@code prevDayPx}; the
 * context read for it is the same {@code spotMetaAndAssetCtxs}, made by a REST fallback or, with the stream healthy,
 * every 5 minutes in the background on a virtual thread (weight 20 each).
 *
 * <p><b>History</b> is the 1-minute {@code candleSnapshot} with {@code coin = "@107"} — the same call as the perpetuals'
 * ({@link HyperliquidPriceSource}), read by {@link CandleHistory}: closed candles with trades only, {@link HistoryKind#TRADE_PRICES}
 * (the ticks are mids), bridged only within this feed's {@code herd.prices.hyperliquid.spot-max-basis}. This source and
 * the perpetuals' share the name {@code hyperliquid}, hence {@link HistorySeeder}'s pace: one request budget.
 */
@ApplicationScoped
@Unremovable
public class HyperliquidSpotPriceSource implements PriceSource {

    private static final Logger LOG = Logger.getLogger(HyperliquidSpotPriceSource.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String NAME = HyperliquidPriceSource.NAME;
    public static final String FEED = "hyperliquid:spot";

    /** With the stream healthy the 24-hour reference price is read this often. */
    static final long CONTEXT_REFRESH_MS = 5 * 60_000L;
    static final String SUBSCRIBE_ALL_MIDS = "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"allMids\"}}";
    static final String PING = "{\"method\":\"ping\"}";

    /** The newest value of a pair and when it arrived (our clock). */
    record Mid(BigDecimal value, long rxMs) {
    }

    private final Supplier<HyperliquidInfoApi> api;
    private final SpotPricesConfig spot;
    private final WebSocketTransport transport;
    private final LongSupplier clock;
    private final Executor background;
    private final boolean threaded;
    private final ThrottledLog warnings;
    private final ConcurrentHashMap<String, Mid> latest = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigDecimal> prevDayPx = new ConcurrentHashMap<>();
    private final Set<String> wanted = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile long lastContextMs = Long.MIN_VALUE / 2;
    private StreamSupervisor supervisor; // created by the first poll, guarded by this
    private ExecutorService ownBackground; // the production executor of the background reads, guarded by this

    @Inject
    public HyperliquidSpotPriceSource(PriceHttpClients clients, SpotPricesConfig spot, WebSocketTransport transport) {
        this(clients.hyperliquid(), spot, transport, System::currentTimeMillis, null, true);
    }

    /**
     * {@code background}: where the periodic context read runs — {@code null}: a virtual thread of its own; a test
     * hands in {@code Runnable::run}. {@code threaded = false}: the stream has no thread, the test steps it.
     */
    HyperliquidSpotPriceSource(Supplier<HyperliquidInfoApi> api, SpotPricesConfig spot, WebSocketTransport transport,
                               LongSupplier clock, Executor background, boolean threaded) {
        this.api = api;
        this.spot = spot;
        this.transport = transport;
        this.clock = clock;
        this.background = background != null ? background : this::runInBackground;
        this.threaded = threaded;
        this.warnings = new ThrottledLog(LOG, clock, 60_000L);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Feed> feeds() {
        SpotPricesConfig.Limits limits = spot.hyperliquidSpot();
        return List.of(new Feed(FEED, limits.staleMs(), limits.maxGapMs(),
                m -> NAME.equals(m.source()) && HyperliquidPriceSource.isSpotRef(m.sourceRef()), limits.maxBasis()));
    }

    // ------------------------------------------------------------------------------------------
    // The stream
    // ------------------------------------------------------------------------------------------

    synchronized StreamSupervisor supervisor() {
        if (supervisor == null) {
            supervisor = new StreamSupervisor(new AllMidsStream(), transport, clock, spot.hyperliquidSpot().silenceMs(),
                    spot.reconnectMaxMs(), spot.idleMs(), threaded);
        }
        return supervisor;
    }

    @PreDestroy
    synchronized void shutdown() {
        if (supervisor != null) {
            supervisor.close();
        }
        if (ownBackground != null) {
            ownBackground.shutdownNow();
        }
    }

    private synchronized void runInBackground(Runnable task) {
        if (ownBackground == null) {
            ownBackground = Executors.newVirtualThreadPerTaskExecutor();
        }
        ownBackground.execute(task);
    }

    private final class AllMidsStream implements StreamSupervisor.Stream {

        @Override
        public String name() {
            return "hyperliquid-spot";
        }

        @Override
        public URI uri() {
            return URI.create(spot.hyperliquidWsUrl());
        }

        @Override
        public List<String> subscribeMessages() {
            return List.of(SUBSCRIBE_ALL_MIDS); // every coin comes with it: nothing to subscribe per pair
        }

        @Override
        public String keepAliveMessage() {
            return PING;
        }

        @Override
        public void onMessage(String text, long rxMs) {
            HyperliquidSpotPriceSource.this.onMessage(text, rxMs);
        }
    }

    /**
     * One message of the stream: {@code allMids} updates every wanted pair that is in it; {@code subscriptionResponse}
     * and {@code pong} need nothing (the supervisor counts them as life); an {@code error} is logged.
     */
    void onMessage(String text, long rxMs) {
        JsonNode message;
        try {
            message = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            warnings.failure("message", "a message of the Hyperliquid stream is not JSON: " + abbreviate(text), null);
            return;
        }
        switch (message.path("channel").asText("")) {
            case "allMids" -> {
                JsonNode mids = message.path("data").path("mids");
                for (String coin : wanted) {
                    BigDecimal value = Dec.of(mids.path(coin));
                    if (value != null && value.signum() > 0) {
                        latest.put(coin, new Mid(value, rxMs));
                    }
                }
            }
            case "error" -> warnings.failure("error", "Hyperliquid answered with an error: " + abbreviate(text), null);
            default -> {
                // subscriptionResponse, pong
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Poll
    // ------------------------------------------------------------------------------------------

    @Override
    public Map<String, Quote> poll(Feed feed, List<Market> markets) throws IOException {
        if (markets.isEmpty()) {
            return Map.of();
        }
        long now = clock.getAsLong();
        for (Market market : markets) {
            wanted.add(market.sourceRef());
        }
        supervisor().want(now);

        Map<String, Quote> out = new LinkedHashMap<>();
        List<Market> stale = new ArrayList<>();
        long freshMs = spot.hyperliquidSpot().freshMs();
        for (Market market : markets) {
            Mid mid = latest.get(market.sourceRef());
            if (mid != null && now - mid.rxMs() <= freshMs) {
                out.put(market.id(), quote(market, mid.value()));
            } else {
                stale.add(market);
            }
        }
        if (!stale.isEmpty()) {
            readContexts(stale, out, now);
        } else if (now - lastContextMs >= CONTEXT_REFRESH_MS && refreshing.compareAndSet(false, true)) {
            lastContextMs = now; // asked for: the next ask is five minutes away, whether this one works or not
            background.execute(this::refreshContexts);
        }
        return out;
    }

    private Quote quote(Market market, BigDecimal mid) {
        return new Quote(mid, HyperliquidPriceSource.relativeChange(mid, prevDayPx.get(market.sourceRef())));
    }

    /**
     * One REST read for every pair without a fresh value; their {@code midPx} goes into {@code out}. A failure costs
     * those pairs their tick and is only thrown when {@code out} is still empty.
     */
    private void readContexts(List<Market> markets, Map<String, Quote> out, long now) throws IOException {
        Map<String, JsonNode> ctxs;
        try {
            ctxs = contexts(api.get().info(spotMetaAndAssetCtxsRequest()));
        } catch (RuntimeException e) {
            warnings.failure("rest", "could not read the Hyperliquid spot contexts (no stream value either)", e);
            if (out.isEmpty()) {
                throw new IOException("no Hyperliquid spot price for any market: " + e, e);
            }
            return;
        }
        warnings.success("rest", "the REST read of the Hyperliquid spot contexts");
        lastContextMs = now;
        rememberPrevDay(ctxs);
        for (Market market : markets) {
            JsonNode ctx = ctxs.get(market.sourceRef());
            BigDecimal mid = ctx == null ? null : Dec.of(ctx.path("midPx"));
            if (mid == null || mid.signum() <= 0) {
                warnings.failure("mid " + market.sourceRef(), "Hyperliquid has no midPx for the spot pair "
                        + market.sourceRef() + " (empty book? renamed?): market " + market.id() + " gets no price", null);
                continue;
            }
            warnings.success("mid " + market.sourceRef(), "the midPx of " + market.sourceRef());
            out.put(market.id(), quote(market, mid));
        }
    }

    /** The periodic read of the reference prices, with the stream healthy; runs on a thread of its own. */
    private void refreshContexts() {
        try {
            rememberPrevDay(contexts(api.get().info(spotMetaAndAssetCtxsRequest())));
            warnings.success("context", "the periodic read of the Hyperliquid spot contexts");
        } catch (RuntimeException e) {
            warnings.failure("context", "could not read the 24-hour reference prices of the Hyperliquid spot pairs", e);
        } finally {
            refreshing.set(false);
        }
    }

    private void rememberPrevDay(Map<String, JsonNode> ctxs) {
        for (String coin : wanted) {
            JsonNode ctx = ctxs.get(coin);
            BigDecimal prev = ctx == null ? null : Dec.of(ctx.path("prevDayPx"));
            if (prev != null && prev.signum() > 0) {
                prevDayPx.put(coin, prev);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // The spot answers
    // ------------------------------------------------------------------------------------------

    /** {@code {"type":"spotMetaAndAssetCtxs"}}: {@code [spotMeta, ctxs]}, what the catalog and the REST fallback read. */
    public static JsonNode spotMetaAndAssetCtxsRequest() {
        return JsonNodeFactory.instance.objectNode().put("type", "spotMetaAndAssetCtxs");
    }

    /**
     * {@code [meta, ctxs]} → the ctx of each pair by its {@code coin} name ({@code "@107"}, {@code "PURR/USDC"}).
     * Position means nothing in the answer (class comment), so no position is used; entries without a {@code coin}
     * (there should be none) are left out.
     *
     * @throws IllegalStateException when the answer is not {@code [meta, ctxs]}
     */
    public static Map<String, JsonNode> contexts(JsonNode answer) {
        if (answer == null || !answer.isArray() || answer.size() < 2 || !answer.get(1).isArray()) {
            throw new IllegalStateException("unexpected answer of spotMetaAndAssetCtxs, not [meta, ctxs]: "
                    + abbreviate(String.valueOf(answer)));
        }
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode ctx : answer.get(1)) {
            String coin = ctx.path("coin").asText("");
            if (!coin.isEmpty()) {
                out.putIfAbsent(coin, ctx);
            }
        }
        return out;
    }

    /**
     * The name of the spot pair {@code base}/{@code quote} in the {@code meta} of a spot answer ({@code spotMeta}, or
     * the first element of {@code spotMetaAndAssetCtxs}): {@code "@107"} for HYPE/USDC. A pair is a {@code universe}
     * entry whose {@code tokens} are the {@code index}es of the tokens with those {@code name}s. Empty when there is no
     * such pair — and when there are several (two tokens of one name, or two pairs of the same tokens): which of them is
     * the venue's own is not for this lookup to guess.
     */
    public static Optional<String> pairName(JsonNode answer, String base, String quote) {
        JsonNode meta = answer != null && answer.isArray() ? answer.path(0) : answer;
        if (meta == null || !meta.path("tokens").isArray() || !meta.path("universe").isArray()) {
            return Optional.empty();
        }
        Set<Integer> bases = tokenIndexes(meta, base);
        Set<Integer> quotes = tokenIndexes(meta, quote);
        List<String> pairs = new ArrayList<>();
        for (JsonNode pair : meta.path("universe")) {
            JsonNode tokens = pair.path("tokens");
            if (tokens.isArray() && tokens.size() == 2 && bases.contains(tokens.get(0).asInt(-1))
                    && quotes.contains(tokens.get(1).asInt(-1)) && !pair.path("name").asText("").isEmpty()) {
                pairs.add(pair.path("name").asText());
            }
        }
        return pairs.size() == 1 ? Optional.of(pairs.get(0)) : Optional.empty();
    }

    private static Set<Integer> tokenIndexes(JsonNode meta, String name) {
        Set<Integer> out = new HashSet<>();
        for (JsonNode token : meta.path("tokens")) {
            if (name.equals(token.path("name").asText()) && token.path("index").isInt()) {
                out.add(token.path("index").asInt());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------------------------------

    @Override
    public HistoryKind historyKind() {
        return HistoryKind.TRADE_PRICES;
    }

    /** The same call, the same weight as a perpetual's ({@link HyperliquidPriceSource#historyWeight}). */
    @Override
    public int historyWeight(long spanMs) {
        return HyperliquidPriceSource.candleSnapshotWeight(spanMs);
    }

    @Override
    public List<PricePoint> history(Market market, long fromMs, long toMs) {
        return historyOf(market, fromMs, toMs).points();
    }

    @Override
    public History historyOf(Market market, long fromMs, long toMs, long... boundariesMs) {
        // endTime = toMs - 1: the candle that starts at toMs holds trades after it (HyperliquidPriceSource: boundary convention)
        JsonNode rows = api.get().info(HyperliquidPriceSource.candleSnapshotRequest(market.sourceRef(),
                fromMs - HyperliquidPriceSource.LOOKBACK_MS, toMs - 1));
        return HyperliquidPriceSource.historyAnswer(rows, fromMs, toMs, clock.getAsLong(), boundariesMs);
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
