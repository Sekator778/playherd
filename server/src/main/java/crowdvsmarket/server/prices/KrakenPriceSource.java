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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.jboss.logging.Logger;

/**
 * Kraken spot, USD pairs ({@code BTC/USD} …): the mid of the best bid and ask, straight from the exchange — the
 * <b>backup venue</b> of BTC, ETH, SOL, XRP and DOGE (Р-76: a round runs entirely on one exchange; Coinbase is their
 * primary, Kraken answers when Coinbase cannot). The formats below were read live 2026-10-03
 * (docs/research/primary_sources.md §2.3).
 *
 * <p><b>Live prices come over a WebSocket</b> — {@code wss://ws.kraken.com/v2}, subscribed with
 * {@code {"method":"subscribe","params":{"channel":"ticker","symbol":["BTC/USD",…],"event_trigger":"bbo"}}} — on a
 * virtual thread of {@link StreamSupervisor}. The default {@code event_trigger} is {@code trades}; this source asks for
 * {@code bbo}, an update on <i>every change of the best bid or offer</i>, because the tick is the mid of those two. Each
 * update carries {@code bid}, {@code ask}, {@code last}, {@code change} (the 24-hour change in price) and the rest of the
 * ticker; the snapshot that answers a subscription is the same message with {@code type = snapshot}. The
 * {@code heartbeat} channel (a message a second, 0.66–1.34 s over 478 messages) is on without being asked for.
 * Measured 2026-10-03, 12:07–12:15 UTC, from Germany, 5 995 updates in 478 s: mean gap between two updates of a pair
 * 0.27–0.58 s (DOGE/USD 0.27, SOL/USD 0.34, BTC/USD 0.48, ETH/USD 0.49, XRP/USD 0.58), the longest 9.6 s for BTC/USD,
 * 11.0 s ETH/USD, 7.4 s SOL/USD, 18.8 s XRP/USD and 23.1 s DOGE/USD (the same pairs under {@code trades}: a mean gap
 * of 3.1 s for BTC/USD and 33 s for DOGE/USD in 75 s).
 *
 * <p><b>A quote that does not change is still the quote.</b> Under {@code bbo} an update comes whenever the best bid or
 * offer changes, so with the connection alive the newest quote of a pair is the current one however old it is. A poll is
 * therefore answered from memory ({@link #poll}) when the stream is open and has been heard from — any message, a heartbeat
 * included — within {@code herd.prices.kraken.fresh-ms} (5 s), and the quote is of the current connection (a reconnect
 * starts again from the snapshot that answers the new subscription) and at most {@value #QUOTE_MAX_AGE_MS} ms old (a
 * minute without a single change is confirmed over REST: a subscription that was silently dropped would look the same).
 * No network on a normal poll. Otherwise one {@code GET /0/public/Ticker?pair=…} reads the best bid and ask of every pair
 * that has no usable quote at once — the same venue, never another one's price. A message that cannot be a quote (a
 * missing side, a crossed book) is dropped, and a poll fails — so that the feed service says so — only when not one market
 * got a price.
 *
 * <p><b>Pair names.</b> The WebSocket v2 symbol is {@code BTC/USD}; the REST API answers a request that names a pair so
 * under that name, too (measured: {@code Ticker?pair=BTC/USD} is keyed {@code "BTC/USD"}, {@code pair=XBTUSD} is keyed
 * {@code "XXBTZUSD"}). The symbol is therefore used for both, and nothing is hard-coded about {@code XBT} or {@code XDG}.
 * Were an answer ever keyed by another name, the pair's names are looked up in {@code /0/public/AssetPairs}
 * ({@code altname}, {@code wsname}, and the key it answers under when asked by {@code altname}) and the answer is read
 * by those ({@link #aliasesOf}).
 *
 * <p>{@code change24h} is {@code (mid − p) / p} where {@code p = last − change} of the newest ticker message (the price a
 * day ago; kept through a stream outage: it is a figure of the last day).
 *
 * <p><b>History is the 1-minute candles</b> ({@code GET /0/public/OHLC?pair=…&interval=1&since=…}: rows
 * {@code [time, open, high, low, close, vwap, volume, count]}, the rows after {@code since}, at most 720 a call — read in
 * pages). They are trade prices and the ticks are mids, so the history is {@link HistoryKind#TRADE_PRICES} and
 * {@link CandleHistory}, {@link GapBridge} and the quiet rule treat it as they treat Coinbase's and Hyperliquid's. Two
 * things are this venue's: a minute without trades is a row with {@code count = 0} (a filler, not a price), and the last
 * row is the minute in progress — rows after {@code result.last}, the exchange's own marker of the last committed
 * candle, are never used, whatever the clock says. The weight of a history call is 0 ({@link HistorySeeder} spaces
 * seeds a second apart). The last trade before a range that holds none (a pair quiet for the 30 minutes asked for) is
 * not read: Kraken's ticker gives the last price but not its time, so the evidence is missing and a quiet hole at the
 * end of a window stays a hole — the conservative answer.
 *
 * <p><b>Measured basis</b> (the reason for {@code herd.prices.kraken.max-basis}, 15 bp): the mid of each of 5 533 updates
 * lay at most 4.19 bp outside the range of its own minute's candle (DOGE/USD; 99th percentile 2.84 bp over the five
 * pairs). Against Coinbase's mid, sampled once a second over the same eight minutes, Kraken's differed by 0.36–1.24 bp
 * on average per pair and at most 5.03 bp (DOGE/USD) — what a round that moves between the two venues opens at, off
 * the close of the round before it.
 */
@ApplicationScoped
@Unremovable
public class KrakenPriceSource implements PriceSource {

    private static final Logger LOG = Logger.getLogger(KrakenPriceSource.class);

    public static final String NAME = "kraken";
    public static final String FEED = "kraken";

    /** The most rows one OHLC call answers. */
    static final int MAX_ROWS = 720;
    static final long CANDLE_MS = CandleHistory.CANDLE_MS;
    /** A history request starts this long before its range, as the others' do: the candle of the left tick of a gap, a last trade. */
    static final long LOOKBACK_MS = 30 * 60_000L;
    /** OHLC is read in at most this many pages: 4 × 720 minutes, more than the 24 h + 30 min a seed asks for. */
    static final int MAX_PAGES = 4;
    /** A quote of the stream that has not changed for this long is confirmed over REST (class comment). */
    static final long QUOTE_MAX_AGE_MS = 60_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The newest quote of a pair: the best bid and ask of a ticker message, when it arrived (our clock) and on which connection. */
    record Tick(BigDecimal bid, BigDecimal ask, long rxMs, int epoch) {

        BigDecimal mid() {
            return bid.add(ask).divide(BigDecimal.valueOf(2));
        }
    }

    private final Supplier<KrakenApi> api;
    private final SpotPricesConfig spot;
    private final WebSocketTransport transport;
    private final LongSupplier clock;
    private final boolean threaded;
    private final ThrottledLog warnings;
    private final ConcurrentHashMap<String, Tick> latest = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BigDecimal> priceADayAgo = new ConcurrentHashMap<>();
    /** The pairs the markets asked for, and the ones the open connection has been told to send. */
    private final Set<String> wanted = ConcurrentHashMap.newKeySet();
    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    /** The names a pair's answers may carry, by symbol; learned from AssetPairs when a name is missing from an answer. */
    private final ConcurrentHashMap<String, Set<String>> aliases = new ConcurrentHashMap<>();
    /** Counts the subscriptions sent: a quote of an earlier connection is no quote of this one. */
    private volatile int epoch;
    private volatile long lastMessageMs = Long.MIN_VALUE / 2;
    private volatile int lastPollRestReads;
    private StreamSupervisor supervisor; // created by the first poll, guarded by this

    @Inject
    public KrakenPriceSource(PriceHttpClients clients, SpotPricesConfig spot, WebSocketTransport transport) {
        this(clients.kraken(), spot, transport, System::currentTimeMillis, true);
    }

    /** {@code threaded = false}: the stream has no thread of its own, the test steps it ({@link #supervisor()}). */
    KrakenPriceSource(Supplier<KrakenApi> api, SpotPricesConfig spot, WebSocketTransport transport, LongSupplier clock,
                      boolean threaded) {
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
        SpotPricesConfig.Limits limits = spot.kraken();
        return List.of(new Feed(FEED, limits.staleMs(), limits.maxGapMs(), m -> NAME.equals(m.source()),
                limits.maxBasis()));
    }

    // ------------------------------------------------------------------------------------------
    // The stream
    // ------------------------------------------------------------------------------------------

    /** The stream supervisor, created on first use. */
    synchronized StreamSupervisor supervisor() {
        if (supervisor == null) {
            supervisor = new StreamSupervisor(new KrakenStream(), transport, clock, spot.kraken().silenceMs(),
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

    private final class KrakenStream implements StreamSupervisor.Stream {

        @Override
        public String name() {
            return "kraken";
        }

        @Override
        public URI uri() {
            return URI.create(spot.krakenWsUrl());
        }

        @Override
        public List<String> subscribeMessages() {
            epoch++; // a new connection: the quotes of the last one are not this one's until its snapshot comes
            Set<String> symbols = new TreeSet<>(wanted);
            subscribed.clear();
            subscribed.addAll(symbols);
            return symbols.isEmpty() ? List.of() : List.of(subscribeMessage(symbols));
        }

        @Override
        public void onMessage(String text, long rxMs) {
            KrakenPriceSource.this.onMessage(text, rxMs);
        }
    }

    /** {@code {"method":"subscribe","params":{"channel":"ticker","symbol":[…],"event_trigger":"bbo"}}}. */
    static String subscribeMessage(Set<String> symbols) {
        ObjectNode params = JsonNodeFactory.instance.objectNode().put("channel", "ticker");
        ArrayNode list = params.putArray("symbol");
        symbols.forEach(list::add);
        params.put("event_trigger", "bbo");
        ObjectNode body = JsonNodeFactory.instance.objectNode().put("method", "subscribe");
        body.set("params", params);
        return body.toString();
    }

    /**
     * One message of the stream: a {@code ticker} snapshot or update changes the quote of its pair; {@code heartbeat},
     * {@code status} and the acknowledgement of a subscription need nothing (but are heard: they say the connection
     * lives); a refused subscription ({@code success = false}) is logged. Anything that is not JSON is dropped with a
     * warning.
     */
    void onMessage(String text, long rxMs) {
        JsonNode message;
        try {
            message = JSON.readTree(text);
        } catch (JsonProcessingException e) {
            warnings.failure("message", "a message of the Kraken stream is not JSON: " + abbreviate(text), null);
            return;
        }
        lastMessageMs = rxMs;
        if ("ticker".equals(message.path("channel").asText(""))) {
            for (JsonNode entry : message.path("data")) {
                onTicker(entry, rxMs);
            }
        } else if (message.path("success").isBoolean() && !message.path("success").asBoolean()) {
            warnings.failure("refused " + message.path("symbol").asText(""), "Kraken refused a request of the stream: "
                    + abbreviate(text), null);
        }
    }

    private void onTicker(JsonNode ticker, long rxMs) {
        String symbol = ticker.path("symbol").asText("");
        BigDecimal bid = Dec.of(ticker.path("bid"));
        BigDecimal ask = Dec.of(ticker.path("ask"));
        if (symbol.isEmpty() || !CoinbasePriceSource.isBook(bid, ask)) {
            warnings.failure("ticker " + symbol, "dropped a ticker message of " + symbol
                    + " that has no usable bid and ask: " + abbreviate(ticker.toString()), null);
            return;
        }
        latest.put(symbol, new Tick(bid, ask, rxMs, epoch));
        BigDecimal last = Dec.of(ticker.path("last"));
        BigDecimal change = Dec.of(ticker.path("change"));
        if (last != null && change != null) {
            BigDecimal then = last.subtract(change);
            if (then.signum() > 0) {
                priceADayAgo.put(symbol, then);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Poll
    // ------------------------------------------------------------------------------------------

    /**
     * The tick of every market: from the stream's newest quote while the stream is alive, else from one REST read of the
     * same venue for all that lack one. Throws only when no market got a price at all, and a REST read failed.
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
        boolean alive = stream.connected() && now - lastMessageMs <= spot.kraken().freshMs();
        int current = epoch;
        for (Market market : markets) {
            Tick tick = latest.get(market.sourceRef());
            if (alive && tick != null && tick.epoch() == current && now - tick.rxMs() <= QUOTE_MAX_AGE_MS) {
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

    /** Pairs a market asked for that the open connection was not told about: subscribed now, no reconnect. */
    private void subscribeNew(StreamSupervisor stream) {
        Set<String> missing = new TreeSet<>(wanted);
        missing.removeAll(subscribed);
        if (!missing.isEmpty() && stream.connected() && stream.send(subscribeMessage(missing))) {
            subscribed.addAll(missing);
        }
    }

    private Quote quote(Market market, BigDecimal mid) {
        return new Quote(mid, HyperliquidPriceSource.relativeChange(mid, priceADayAgo.get(market.sourceRef())));
    }

    /**
     * One REST ticker call for every market without a usable quote; the mids go into {@code out}. A market whose pair is
     * missing from the answer, or whose book is no book, gets no tick this time. Throws when {@code out} is still empty
     * and the call failed.
     */
    private void readTickers(List<Market> markets, Map<String, Quote> out) throws IOException {
        Set<String> symbols = new LinkedHashSet<>();
        for (Market market : markets) {
            symbols.add(market.sourceRef());
        }
        JsonNode result;
        try {
            result = result(api.get().ticker(String.join(",", symbols)), "Ticker");
        } catch (IOException | RuntimeException e) {
            warnings.failure("rest ticker", "could not read the Kraken ticker (no stream quote either)", e);
            if (out.isEmpty()) {
                throw new IOException("no Kraken price for any market: " + e, e);
            }
            return;
        }
        warnings.success("rest ticker", "the REST ticker of Kraken");
        for (Market market : markets) {
            String symbol = market.sourceRef();
            BigDecimal mid = midOf(entryOf(result, symbol));
            if (mid == null) {
                warnings.failure("rest " + symbol, "Kraken's ticker has no usable bid and ask for " + symbol
                        + ": market " + market.id() + " gets no price", null);
                continue;
            }
            warnings.success("rest " + symbol, "the REST ticker of " + symbol);
            out.put(market.id(), quote(market, mid));
        }
    }

    /** The mid of a REST ticker entry's bid {@code b[0]} and ask {@code a[0]}, or null when they are no book. */
    public static BigDecimal midOf(JsonNode entry) {
        if (entry == null) {
            return null;
        }
        BigDecimal bid = Dec.of(entry.path("b").path(0));
        BigDecimal ask = Dec.of(entry.path("a").path(0));
        return CoinbasePriceSource.isBook(bid, ask) ? new Tick(bid, ask, 0L, 0).mid() : null;
    }

    // ------------------------------------------------------------------------------------------
    // Pair names
    // ------------------------------------------------------------------------------------------

    /**
     * The {@code result} of a Kraken answer; an answer with an error (an {@code E…} entry of {@code error}: rate limit,
     * unknown pair, …) is an {@link IOException}. A {@code W…} entry is a warning and no failure.
     */
    static JsonNode result(JsonNode answer, String what) throws IOException {
        if (answer == null || !answer.isObject()) {
            throw new IOException("Kraken's " + what + " answer is not an object: " + abbreviate(String.valueOf(answer)));
        }
        for (JsonNode error : answer.path("error")) {
            if (error.asText("").startsWith("E")) {
                throw new IOException("Kraken's " + what + " answered with an error: " + error.asText());
            }
        }
        JsonNode result = answer.path("result");
        if (!result.isObject()) {
            throw new IOException("Kraken's " + what + " answer has no result: " + abbreviate(answer.toString()));
        }
        return result;
    }

    /**
     * The entry of a Ticker or OHLC {@code result} for {@code symbol}: under the symbol itself (the venue echoes the name a
     * request used), else under one of the names {@link #aliasesOf} finds. {@code null} when it is not there, or when
     * AssetPairs could not be read — the pair then simply gets no price this time.
     */
    JsonNode entryOf(JsonNode result, String symbol) {
        JsonNode direct = result.get(symbol);
        if (direct != null) {
            return direct;
        }
        try {
            for (String alias : aliasesOf(symbol)) {
                JsonNode entry = result.get(alias);
                if (entry != null) {
                    return entry;
                }
            }
        } catch (IOException | RuntimeException e) {
            warnings.failure("asset pairs " + symbol, "could not look up the names of " + symbol + " in AssetPairs", e);
        }
        return null;
    }

    /**
     * Every name a pair's answers may carry, learned from {@code /0/public/AssetPairs} on the first miss and kept:
     * the symbol, the {@code altname} and {@code wsname} of its entry, the key of that entry, and — asked once more by
     * {@code altname} — the pair's own key ({@code XXBTZUSD}), which is what a request by {@code altname} is answered
     * under. Nothing about a name is assumed: the venue says what it calls its pair.
     */
    Set<String> aliasesOf(String symbol) throws IOException {
        Set<String> known = aliases.get(symbol);
        if (known != null) {
            return known;
        }
        Set<String> names = new LinkedHashSet<>();
        names.add(symbol);
        JsonNode bySymbol = result(api.get().assetPairs(symbol), "AssetPairs");
        String altname = null;
        for (Map.Entry<String, JsonNode> pair : bySymbol.properties()) {
            names.add(pair.getKey());
            names.add(pair.getValue().path("wsname").asText(""));
            altname = pair.getValue().path("altname").asText("");
            names.add(altname);
        }
        if (altname != null && !altname.isEmpty() && !altname.equals(symbol)) {
            result(api.get().assetPairs(altname), "AssetPairs").fieldNames().forEachRemaining(names::add);
        }
        names.remove("");
        aliases.put(symbol, Set.copyOf(names));
        return aliases.get(symbol);
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
        List<CandleHistory.Candle> candles = candles(market.sourceRef(), fromMs - LOOKBACK_MS);
        return CandleHistory.answer(candles, fromMs, toMs, clock.getAsLong(), boundariesMs);
    }

    /**
     * The committed candles of {@code symbol} from the minute that contains {@code startMs} on (up to the last committed
     * one: Kraken has no end of range), in pages of at most {@value #MAX_ROWS} rows; whatever is not a whole row is left
     * out, and so is every row after {@code result.last}. Sorted by time.
     */
    List<CandleHistory.Candle> candles(String symbol, long startMs) throws IOException {
        long since = Math.floorDiv(startMs, CANDLE_MS) * (CANDLE_MS / 1000L) - 1; // the rows are those AFTER since
        Map<Long, CandleHistory.Candle> byTime = new LinkedHashMap<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode result = result(api.get().ohlc(symbol, 1, since), "OHLC");
            JsonNode rows = entryOf(result, symbol);
            if (rows == null || !rows.isArray()) {
                break; // nothing under the pair's names: no history (an unknown pair would have been an error)
            }
            long committed = result.path("last").asLong(Long.MAX_VALUE);
            long newest = since;
            for (CandleHistory.Candle candle : candles(rows, committed)) {
                byTime.putIfAbsent(candle.t(), candle);
            }
            for (JsonNode row : rows) {
                newest = Math.max(newest, row.path(0).asLong(newest));
            }
            if (rows.size() < MAX_ROWS || newest <= since) {
                break;
            }
            since = newest;
        }
        return CandleHistory.sorted(new ArrayList<>(byTime.values()));
    }

    /**
     * {@code [[time, open, high, low, close, vwap, volume, count], …]} → candles; a row that is not whole is left out, and
     * so is one after {@code committed} (the minute in progress). {@code count} is the number of trades: 0 makes a row a
     * filler.
     */
    static List<CandleHistory.Candle> candles(JsonNode rows, long committed) {
        List<CandleHistory.Candle> out = new ArrayList<>();
        if (rows == null || !rows.isArray()) {
            return out;
        }
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 8 || !row.get(0).isNumber()) {
                continue;
            }
            long seconds = row.get(0).asLong();
            if (seconds > committed) {
                continue;
            }
            long t = seconds * 1000L;
            BigDecimal open = Dec.of(row.get(1));
            BigDecimal high = Dec.of(row.get(2));
            BigDecimal low = Dec.of(row.get(3));
            BigDecimal close = Dec.of(row.get(4));
            if (open == null || high == null || low == null || close == null || !row.get(7).isIntegralNumber()) {
                continue;
            }
            out.add(new CandleHistory.Candle(t, t + CANDLE_MS - 1, open, high, low, close, row.get(7).asLong()));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------------------------------

    @Override
    public FeedStatus status(Feed feed, long nowMs) {
        return StreamSupervisor.status(feed, NAME, supervisor().health(nowMs), nowMs, spot.kraken().freshMs(),
                lastPollRestReads);
    }

    private static String abbreviate(String text) {
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
