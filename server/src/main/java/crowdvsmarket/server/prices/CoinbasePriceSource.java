package crowdvsmarket.server.prices;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import crowdvsmarket.server.markets.Market;
import io.quarkus.arc.Unremovable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Coinbase Exchange spot, USD products ({@code BTC-USD} …): the mid of the best bid and ask, straight from the
 * exchange (docs/research/primary_sources.md §1, §2.3; the formats below were read live 2026-10-03).
 *
 * <p><b>Live prices come over a WebSocket</b> — {@code wss://ws-feed.exchange.coinbase.com}, subscribed with
 * {@code {"type":"subscribe","product_ids":[…],"channels":["ticker","heartbeat"]}} — on a virtual thread of
 * {@link StreamSupervisor}, which connects, resubscribes and backs off by itself. Every {@code ticker} message carries
 * {@code best_bid}, {@code best_ask} (and the last trade's {@code price}, {@code time}, and {@code open_24h}); this
 * source keeps the newest one of each product in memory. The {@code heartbeat} channel (a message a second per
 * product, longest gap seen 1.16 s) is what tells a quiet product from a dead connection: ticker messages come with
 * trades only — 172 a minute for BTC-USD, 6 for DOGE-USD, whose longest silence in the 6-minute sample was 53 s
 * (measured 2026-10-03, 11:08–11:15 UTC, from Germany) — so only silence of <i>everything</i> reconnects.
 *
 * <p><b>A poll is answered from memory</b> ({@link #poll}): the tick of a market is
 * {@code (best bid + best ask) / 2} of its newest ticker message while that is at most
 * {@code herd.prices.coinbase.fresh-ms} old (5 s). No network on a normal poll. A product whose newest quote is older —
 * it has not traded (DOGE-USD most of the time), or the stream is down or silent — is read once over REST,
 * {@code GET /products/{id}/ticker} (also best bid and ask), all such products at once on virtual threads: one REST
 * read of the <i>same</i> venue, never another venue's price. The rate limit is 10 requests a second per IP, a full
 * outage costs 2.5 a second. A message that cannot be a quote (a missing side, a crossed book) is dropped, and a poll
 * fails — so that the feed service says so — only when not one market got a price.
 * {@code change24h} is {@code (mid − open_24h) / open_24h}, the open from the newest ticker message (kept through a
 * stream outage: it is a figure of the last day).
 *
 * <p><b>Subscriptions follow the markets.</b> The first poll with an active market starts the stream and subscribes to
 * its product; a market listed later is subscribed on the open connection, and after a reconnect everything wanted is
 * subscribed at once. With no active market nothing is polled, and a stream nobody has polled for
 * {@code herd.prices.stream.idle-ms} is closed.
 *
 * <p><b>History is the 1-minute candles</b> ({@code GET /products/{id}/candles?granularity=60&start=&end=}: rows
 * {@code [time, low, high, open, close, volume]}, newest first, at most 300 a call — a longer range is read in
 * pages). They are trade prices and the ticks are mids, so the history is {@link HistoryKind#TRADE_PRICES} and
 * {@link CandleHistory}, {@link GapBridge} and the quiet rule treat it exactly as they treat Hyperliquid's: the past
 * of the first poll, a bridge over a gap between two live ticks (within this feed's own
 * {@code herd.prices.coinbase.max-basis}, 15 bp: measured, the mids of 1 510 ticker messages lay at most 1.62 bp
 * outside the range of their own minute's candle), and evidence. A minute without trades has no row at all (there are
 * no fillers, unlike Hyperliquid's), so a row is a minute that traded; the candle of the minute in progress is among
 * the rows and is skipped until it is closed ({@code T < now}) — measured, a closed candle did not change from
 * 0.18 s after its minute ended (2 boundaries, BTC-USD and DOGE-USD). The last trade before a range the candles do not
 * reach (a product quiet for more than the 30 minutes asked for) is read from the REST ticker's {@code price} and
 * {@code time}. The weight of a history call is 0: the 10 requests a second are not paced by {@link HistorySeeder}
 * beyond its one second between two seeds.
 */
@ApplicationScoped
@Unremovable
public class CoinbasePriceSource implements PriceSource {

    private static final Logger LOG = Logger.getLogger(CoinbasePriceSource.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String NAME = "coinbase";
    public static final String FEED = "coinbase";

    /** The most candles one request answers. */
    static final int MAX_CANDLES = 300;
    static final long CANDLE_MS = CandleHistory.CANDLE_MS;
    /** A history request starts this long before its range, as Hyperliquid's does: the last trade of a quiet product and the candle of the left tick of a gap. */
    static final long LOOKBACK_MS = 30 * 60_000L;
    /** The REST reads of one poll are given this long altogether (the poll period is 2 s, the HTTP read timeout 5 s). */
    static final long REST_BUDGET_MS = 8_000L;
    /** Candles are asked for in at most this many pages: 8 × 300 minutes, more than the 24 h + 30 min a seed asks for. */
    static final int MAX_PAGES = 8;

    /** The newest quote of a product: the best bid and ask of a ticker message and when it arrived (our clock). */
    record Tick(BigDecimal bid, BigDecimal ask, long rxMs) {

        BigDecimal mid() {
            return bid.add(ask).divide(BigDecimal.valueOf(2));
        }
    }

    private final Supplier<CoinbaseExchangeApi> api;
    private final SpotPricesConfig spot;
    private final WebSocketTransport transport;
    private final LongSupplier clock;
    private final boolean threaded;
    private final ThrottledLog warnings;
    private final ConcurrentHashMap<String, Tick> latest = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigDecimal> open24h = new ConcurrentHashMap<>();
    /** The products the markets asked for, and the ones the open connection has been told to send. */
    private final Set<String> wanted = ConcurrentHashMap.newKeySet();
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private volatile int lastPollRestReads;
    private StreamSupervisor supervisor; // created by the first poll, guarded by this

    @Inject
    public CoinbasePriceSource(PriceHttpClients clients, SpotPricesConfig spot, WebSocketTransport transport) {
        this(clients.coinbase(), spot, transport, System::currentTimeMillis, true);
    }

    /** {@code threaded = false}: the stream has no thread of its own, the test steps it ({@link #supervisor()}). */
    CoinbasePriceSource(Supplier<CoinbaseExchangeApi> api, SpotPricesConfig spot, WebSocketTransport transport,
                        LongSupplier clock, boolean threaded) {
        this.api = api;
        this.spot = spot;
        this.transport = transport;
        this.clock = clock;
        this.threaded = threaded;
        this.warnings = new ThrottledLog(LOG, clock, 60_000L);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<Feed> feeds() {
        SpotPricesConfig.Limits limits = spot.coinbase();
        return List.of(new Feed(FEED, limits.staleMs(), limits.maxGapMs(), m -> NAME.equals(m.source()),
                limits.maxBasis()));
    }

    // ------------------------------------------------------------------------------------------
    // The stream
    // ------------------------------------------------------------------------------------------

    /** The stream supervisor, created on first use. */
    synchronized StreamSupervisor supervisor() {
        if (supervisor == null) {
            supervisor = new StreamSupervisor(new CoinbaseStream(), transport, clock, spot.coinbase().silenceMs(),
                    spot.reconnectMaxMs(), spot.idleMs(), threaded);
        }
        return supervisor;
    }

    @PreDestroy
    synchronized void shutdown() {
        if (supervisor != null) {
            supervisor.close();
        }
    }

    private final class CoinbaseStream implements StreamSupervisor.Stream {

        @Override
        public String name() {
            return "coinbase";
        }

        @Override
        public URI uri() {
            return URI.create(spot.coinbaseWsUrl());
        }

        @Override
        public List<String> subscribeMessages() {
            Set<String> products = new TreeSet<>(wanted);
            subscribed.clear();
            subscribed.addAll(products);
            return products.isEmpty() ? List.of() : List.of(subscribeMessage(products));
        }

        @Override
        public boolean isData(String text) {
            return isDataMessage(text);
        }

        @Override
        public void onMessage(String text, long rxMs) {
            CoinbasePriceSource.this.onMessage(text, rxMs);
        }
    }

    /**
     * Whether a message is data of a subscribed channel: a {@code ticker} message, a price. The acknowledgement of the
     * subscription ({@code subscriptions}), a heartbeat and an error are not — a connection that has delivered only those has
     * not shown that it works ({@link StreamSupervisor}).
     */
    static boolean isDataMessage(String text) {
        try {
            return "ticker".equals(JSON.readTree(text).path("type").asText(""));
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /** {@code {"type":"subscribe","product_ids":[…],"channels":["ticker","heartbeat"]}}. */
    static String subscribeMessage(Set<String> products) {
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("type", "subscribe");
        ArrayNode ids = body.putArray("product_ids");
        products.forEach(ids::add);
        body.putArray("channels").add("ticker").add("heartbeat");
        return body.toString();
    }

    /**
     * One message of the stream: a {@code ticker} updates its product's quote; {@code heartbeat} and the
     * {@code subscriptions} acknowledgement need nothing (the supervisor counts them as life); an {@code error} is
     * logged. Anything that is not JSON is dropped with a warning.
     */
    void onMessage(String text, long rxMs) {
        JsonNode message;
        try {
            message = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            warnings.failure("message", "a message of the Coinbase stream is not JSON: " + abbreviate(text), null);
            return;
        }
        switch (message.path("type").asText("")) {
            case "ticker" -> onTicker(message, rxMs);
            case "error" -> warnings.failure("error", "Coinbase answered with an error: " + abbreviate(text), null);
            default -> {
                // heartbeat, subscriptions, and whatever a later version of the feed adds
            }
        }
    }

    private void onTicker(JsonNode ticker, long rxMs) {
        String product = ticker.path("product_id").asText("");
        BigDecimal bid = Dec.of(ticker.path("best_bid"));
        BigDecimal ask = Dec.of(ticker.path("best_ask"));
        if (product.isEmpty() || !isBook(bid, ask)) {
            warnings.failure("ticker " + product, "dropped a ticker message of " + product
                    + " that has no usable best bid and ask: " + abbreviate(ticker.toString()), null);
            return;
        }
        latest.put(product, new Tick(bid, ask, rxMs));
        BigDecimal open = Dec.of(ticker.path("open_24h"));
        if (open != null && open.signum() > 0) {
            open24h.put(product, open);
        }
    }

    /** A bid and an ask that make a book: both positive, the ask not below the bid. */
    static boolean isBook(BigDecimal bid, BigDecimal ask) {
        return bid != null && ask != null && bid.signum() > 0 && ask.signum() > 0 && ask.compareTo(bid) >= 0;
    }

    // ------------------------------------------------------------------------------------------
    // Poll
    // ------------------------------------------------------------------------------------------

    /**
     * The tick of every market: from the stream's newest quote while it is fresh, else from one REST read of the
     * same venue. Throws only when no market got a price at all, and a REST read failed.
     */
    @Override
    public Map<String, Quote> poll(Feed feed, List<Market> markets) throws IOException {
        if (markets.isEmpty()) {
            return Map.of();
        }
        long now = clock.getAsLong();
        for (Market market : markets) {
            wanted.add(market.sourceRef());
        }
        StreamSupervisor stream = supervisor();
        stream.want(now);
        subscribeNew(stream);

        Map<String, Quote> out = new LinkedHashMap<>();
        List<Market> stale = new ArrayList<>();
        long freshMs = spot.coinbase().freshMs();
        for (Market market : markets) {
            Tick tick = latest.get(market.sourceRef());
            if (tick != null && now - tick.rxMs() <= freshMs) {
                out.put(market.id(), quote(market, tick.mid()));
            } else {
                stale.add(market);
            }
        }
        lastPollRestReads = stale.size();
        if (!stale.isEmpty()) {
            readTickers(stale, out);
        }
        return out;
    }

    @Override
    public FeedStatus status(Feed feed, long nowMs) {
        return StreamSupervisor.status(feed, NAME, supervisor().health(nowMs), nowMs, spot.coinbase().freshMs(),
                lastPollRestReads);
    }

    /** Products a market asked for that the open connection was not told about: subscribed now, no reconnect. */
    private void subscribeNew(StreamSupervisor stream) {
        Set<String> missing = new TreeSet<>(wanted);
        missing.removeAll(subscribed);
        if (!missing.isEmpty() && stream.connected() && stream.send(subscribeMessage(missing))) {
            subscribed.addAll(missing);
        }
    }

    private Quote quote(Market market, BigDecimal mid) {
        return new Quote(mid, HyperliquidPriceSource.relativeChange(mid, open24h.get(market.sourceRef())));
    }

    /**
     * One REST ticker per market that has no fresh quote, on virtual threads, the whole of them given
     * {@link #REST_BUDGET_MS}; the mids go into {@code out}. A market whose read failed (or whose book is no book) gets
     * no tick this time. Throws when {@code out} is still empty and a read failed.
     */
    private void readTickers(List<Market> markets, Map<String, Quote> out) throws IOException {
        List<Callable<BigDecimal>> reads = new ArrayList<>();
        for (Market market : markets) {
            reads.add(() -> midOf(api.get().ticker(market.sourceRef())));
        }
        List<Future<BigDecimal>> answers;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            answers = executor.invokeAll(reads, REST_BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while reading the Coinbase tickers", e);
        }
        Exception failure = null;
        for (int i = 0; i < markets.size(); i++) {
            Market market = markets.get(i);
            String key = "rest " + market.sourceRef();
            try {
                BigDecimal mid = answers.get(i).get();
                if (mid == null) {
                    warnings.failure(key, "the ticker of " + market.sourceRef() + " has no usable bid and ask", null);
                    continue;
                }
                warnings.success(key, "the REST ticker of " + market.sourceRef());
                out.put(market.id(), quote(market, mid));
            } catch (Exception e) { // an ExecutionException, or a read that was cancelled for taking too long
                failure = e;
                warnings.failure(key, "could not read the ticker of " + market.sourceRef()
                        + " (no stream quote either)", e);
            }
        }
        if (out.isEmpty() && failure != null) {
            throw new IOException("no Coinbase price for any market: " + failure, failure);
        }
    }

    /** The mid of a REST ticker's {@code bid} and {@code ask}, or null when they are no book (the catalog prices a market by it too). */
    public static BigDecimal midOf(JsonNode ticker) {
        BigDecimal bid = Dec.of(ticker.path("bid"));
        BigDecimal ask = Dec.of(ticker.path("ask"));
        return isBook(bid, ask) ? new Tick(bid, ask, 0L).mid() : null;
    }

    // ------------------------------------------------------------------------------------------
    // History
    // ------------------------------------------------------------------------------------------

    @Override
    public HistoryKind historyKind() {
        return HistoryKind.TRADE_PRICES;
    }

    @Override
    public List<PricePoint> history(Market market, long fromMs, long toMs) throws IOException {
        return historyOf(market, fromMs, toMs).points();
    }

    @Override
    public History historyOf(Market market, long fromMs, long toMs, long... boundariesMs) throws IOException {
        // up to toMs - 1: the candle that starts at toMs holds trades after it (CandleHistory: boundary convention)
        List<CandleHistory.Candle> candles = candles(market.sourceRef(), fromMs - LOOKBACK_MS, toMs - 1);
        History answer = CandleHistory.answer(candles, fromMs, toMs, clock.getAsLong(), boundariesMs);
        if (answer.lastTrade().isPresent()) {
            return answer;
        }
        // no trade in the 30 minutes before the range: the REST ticker has the last trade whenever it was
        return new History(answer.points(), lastTradeBefore(market, fromMs), answer.bars());
    }

    /** The price and time of the product's last trade when it happened before {@code fromMs}: evidence that it has not traded since. */
    private Optional<PricePoint> lastTradeBefore(Market market, long fromMs) {
        try {
            JsonNode ticker = api.get().ticker(market.sourceRef());
            BigDecimal price = Dec.of(ticker.path("price"));
            Instant time = Instant.parse(ticker.path("time").asText(""));
            if (price != null && price.signum() > 0 && time.toEpochMilli() < fromMs) {
                return Optional.of(new PricePoint(time.toEpochMilli(), price));
            }
        } catch (RuntimeException e) { // a failed read, a ticker that is not one: no evidence, the hole stays a hole
            warnings.failure("last trade " + market.sourceRef(), "could not read the last trade of "
                    + market.sourceRef(), e);
        }
        return Optional.empty();
    }

    /**
     * The candles of {@code [startMs, endMs]} (whole minutes: a start is floored and an end too), in pages of at most
     * {@value #MAX_CANDLES}; whatever is not a whole row is left out, and so is a row with no volume. Sorted by time.
     */
    List<CandleHistory.Candle> candles(String product, long startMs, long endMs) throws IOException {
        long first = Math.floorDiv(startMs, CANDLE_MS) * CANDLE_MS;
        long last = Math.floorDiv(endMs, CANDLE_MS) * CANDLE_MS;
        Map<Long, CandleHistory.Candle> byTime = new LinkedHashMap<>();
        long cursor = first;
        for (int page = 0; page < MAX_PAGES && cursor <= last; page++) {
            long pageEnd = Math.min(last, cursor + (MAX_CANDLES - 1) * CANDLE_MS);
            JsonNode rows = api.get().candles(product, (int) (CANDLE_MS / 1000L), Instant.ofEpochMilli(cursor).toString(),
                    Instant.ofEpochMilli(pageEnd).toString());
            for (CandleHistory.Candle candle : candles(rows)) {
                byTime.putIfAbsent(candle.t(), candle);
            }
            cursor = pageEnd + CANDLE_MS;
        }
        return CandleHistory.sorted(new ArrayList<>(byTime.values()));
    }

    /** {@code [[time, low, high, open, close, volume], …]} → candles; a row that is not whole or did not trade is left out. */
    static List<CandleHistory.Candle> candles(JsonNode rows) {
        List<CandleHistory.Candle> out = new ArrayList<>();
        if (rows == null || !rows.isArray()) {
            return out;
        }
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 6 || !row.get(0).isNumber()) {
                continue;
            }
            long t = row.get(0).asLong() * 1000L;
            BigDecimal low = Dec.of(row.get(1));
            BigDecimal high = Dec.of(row.get(2));
            BigDecimal open = Dec.of(row.get(3));
            BigDecimal close = Dec.of(row.get(4));
            BigDecimal volume = Dec.of(row.get(5));
            if (low == null || high == null || open == null || close == null || volume == null || volume.signum() <= 0) {
                continue;
            }
            out.add(new CandleHistory.Candle(t, t + CANDLE_MS - 1, open, high, low, close, -1L));
        }
        return out;
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
