package crowdvsmarket.server.prices;

/**
 * The {@code herd.prices.coinbase.*}, {@code herd.prices.kraken.*}, {@code herd.prices.hyperliquid.spot-*} and
 * {@code herd.prices.stream.*} settings: the three sources that keep their quotes from a WebSocket stream
 * ({@link CoinbasePriceSource}, {@link KrakenPriceSource}, {@link HyperliquidSpotPriceSource}) as one immutable value,
 * built from {@code @ConfigProperty} by {@link SpotPricesConfigProducer} (every key has its default there; the
 * environment variable of a key is its upper-cased name with dots and dashes turned into underscores). Apart from
 * {@link PricesConfig} because that record is the pipeline's older, REST-polled half and already has its constructors to
 * carry; the keys share its namespace.
 *
 * <p>The poll periods are no setting of this record: they are the scheduler expressions of {@link PriceFeedService}
 * ({@code herd.prices.coinbase.poll-every}, {@code herd.prices.kraken.poll-every},
 * {@code herd.prices.hyperliquid.spot-poll-every}, 2 s by default).
 *
 * @param coinbaseUrl      {@code herd.prices.coinbase.url}: Coinbase Exchange REST (tickers, candles)
 * @param coinbaseWsUrl    {@code herd.prices.coinbase.ws-url}: its WebSocket feed
 * @param hyperliquidWsUrl {@code herd.prices.hyperliquid.ws-url}: Hyperliquid's WebSocket
 * @param coinbase         the limits of the {@code coinbase} feed
 * @param hyperliquidSpot  the limits of the {@code hyperliquid:spot} feed
 * @param reconnectMaxMs   {@code herd.prices.stream.reconnect-max-ms}: a stream that cannot be had waits 1, 2, 4 …
 *                         seconds before it tries again, up to this
 * @param idleMs           {@code herd.prices.stream.idle-ms}: a stream nobody has polled for this long is closed, and
 *                         opened again by the next poll (no active market, no connection)
 * @param krakenUrl        {@code herd.prices.kraken.url}: Kraken REST (ticker, asset pairs, OHLC)
 * @param krakenWsUrl      {@code herd.prices.kraken.ws-url}: its WebSocket v2
 * @param kraken           the limits of the {@code kraken} feed
 */
public record SpotPricesConfig(
        String coinbaseUrl,
        String coinbaseWsUrl,
        String hyperliquidWsUrl,
        Limits coinbase,
        Limits hyperliquidSpot,
        long reconnectMaxMs,
        long idleMs,
        String krakenUrl,
        String krakenWsUrl,
        Limits kraken) {

    /**
     * What a streamed feed is held to.
     *
     * @param staleMs   a tick older than this at a boundary no longer says what the price is ({@link PriceSource.Feed})
     * @param maxGapMs  two neighbouring ticks of a round window further apart than this are a hole
     * @param maxBasis  how far a live tick may lie outside the range of its own minute's candle, relative to the tick,
     *                  for the candles to bridge a gap between two live ticks ({@link GapBridge}); 0.0015 is 15 bp
     * @param freshMs   a quote from the stream is handed out by a poll while it is at most this old; an older one (the
     *                  market has not traded, the stream is down) is read again over REST. For a stream that pushes every
     *                  change of the quote (Kraken's {@code bbo}) it is how long the stream may have said nothing at
     *                  all, a heartbeat included, for its newest quote still to be the current one
     * @param silenceMs a stream that has sent nothing at all for this long is taken for dead and reconnected
     */
    public record Limits(long staleMs, long maxGapMs, double maxBasis, long freshMs, long silenceMs) {

        public Limits {
            if (staleMs <= 0 || maxGapMs <= 0 || freshMs <= 0 || silenceMs <= 0) {
                throw new IllegalArgumentException("stale-ms, max-gap-ms, fresh-ms and silence-ms must be positive, got "
                        + staleMs + ", " + maxGapMs + ", " + freshMs + ", " + silenceMs);
            }
            if (Double.isNaN(maxBasis) || Double.isInfinite(maxBasis) || maxBasis < 0) {
                throw new IllegalArgumentException("max-basis must be a relative distance of at least 0, got "
                        + maxBasis);
            }
        }
    }

    public SpotPricesConfig {
        if (reconnectMaxMs < 1_000L) {
            throw new IllegalArgumentException("herd.prices.stream.reconnect-max-ms must be at least 1000, got "
                    + reconnectMaxMs);
        }
        if (idleMs <= 0) {
            throw new IllegalArgumentException("herd.prices.stream.idle-ms must be positive, got " + idleMs);
        }
    }

    /** The settings before Kraken, with its defaults. */
    public SpotPricesConfig(String coinbaseUrl, String coinbaseWsUrl, String hyperliquidWsUrl, Limits coinbase,
                            Limits hyperliquidSpot, long reconnectMaxMs, long idleMs) {
        this(coinbaseUrl, coinbaseWsUrl, hyperliquidWsUrl, coinbase, hyperliquidSpot, reconnectMaxMs, idleMs,
                DEFAULT_KRAKEN_URL, DEFAULT_KRAKEN_WS_URL, defaultKraken());
    }

    // Defaults live here, as compile-time constants, so the @ConfigProperty annotations of the producer, the
    // @RegisterRestClient base URI and defaults() cannot drift apart.
    public static final String DEFAULT_COINBASE_URL = "https://api.exchange.coinbase.com";
    public static final String DEFAULT_COINBASE_WS_URL = "wss://ws-feed.exchange.coinbase.com";
    public static final String DEFAULT_HYPERLIQUID_WS_URL = "wss://api.hyperliquid.xyz/ws";
    public static final String DEFAULT_KRAKEN_URL = "https://api.kraken.com";
    public static final String DEFAULT_KRAKEN_WS_URL = "wss://ws.kraken.com/v2";
    /** Fifteen seconds and 30 s, as for Hyperliquid: seven polls of 2 s missed, fifteen. */
    public static final String DEFAULT_STALE_MS = "15000";
    public static final String DEFAULT_MAX_GAP_MS = "30000";
    /**
     * 15 bp like Hyperliquid's, an eighth of the distance at which a 40x position is liquidated. Measured 2026-10-03:
     * the mid of the best bid and ask of 1 510 Coinbase ticker messages (BTC, ETH, SOL, XRP, DOGE, 6 minutes) lay at
     * most 1.62 bp outside the range of its own minute's candle (99th percentile 0.42 bp); of 5 533 Kraken
     * {@code bbo} updates (the same five coins, 8 minutes, 12:07–12:15 UTC) at most 4.19 bp (DOGE; 99th percentile
     * 2.84 bp); Hyperliquid spot: not measured (UNVERIFIED), the perpetuals' tolerance is the one taken.
     */
    public static final String DEFAULT_MAX_BASIS = "0.0015";
    /**
     * Coinbase's ticker message comes with a trade: 172 a minute for BTC-USD, 6 for DOGE-USD, whose longest silence in
     * the 6-minute sample was 53 s. A quote older than 5 s is read again over REST, which is what DOGE does most of the
     * time and BTC never.
     */
    public static final String DEFAULT_COINBASE_FRESH_MS = "5000";
    /** Coinbase's heartbeat channel sends once a second per product (longest gap seen 1.16 s): ten seconds of nothing is a dead stream. */
    public static final String DEFAULT_COINBASE_SILENCE_MS = "10000";
    /** Hyperliquid's {@code allMids} comes every 5.0 s (4.85–5.21 s over 74 messages): a message may be 5.2 s old when a poll asks, and one may be lost. */
    public static final String DEFAULT_HYPERLIQUID_SPOT_FRESH_MS = "8000";
    public static final String DEFAULT_HYPERLIQUID_SPOT_SILENCE_MS = "15000";
    /**
     * Kraken's {@code ticker} with {@code event_trigger = "bbo"} pushes every change of the best bid or offer, so the
     * newest quote of a pair is the current one for as long as the connection is heard from: its heartbeat comes every
     * second (0.66–1.34 s over 478 messages, 2026-10-03). Five seconds without any message and the poll reads REST.
     */
    public static final String DEFAULT_KRAKEN_FRESH_MS = "5000";
    public static final String DEFAULT_KRAKEN_SILENCE_MS = "10000";
    public static final String DEFAULT_RECONNECT_MAX_MS = "60000";
    public static final String DEFAULT_IDLE_MS = "60000";

    public static SpotPricesConfig defaults() {
        return new SpotPricesConfig(DEFAULT_COINBASE_URL, DEFAULT_COINBASE_WS_URL, DEFAULT_HYPERLIQUID_WS_URL,
                new Limits(Long.parseLong(DEFAULT_STALE_MS), Long.parseLong(DEFAULT_MAX_GAP_MS),
                        Double.parseDouble(DEFAULT_MAX_BASIS), Long.parseLong(DEFAULT_COINBASE_FRESH_MS),
                        Long.parseLong(DEFAULT_COINBASE_SILENCE_MS)),
                new Limits(Long.parseLong(DEFAULT_STALE_MS), Long.parseLong(DEFAULT_MAX_GAP_MS),
                        Double.parseDouble(DEFAULT_MAX_BASIS), Long.parseLong(DEFAULT_HYPERLIQUID_SPOT_FRESH_MS),
                        Long.parseLong(DEFAULT_HYPERLIQUID_SPOT_SILENCE_MS)),
                Long.parseLong(DEFAULT_RECONNECT_MAX_MS), Long.parseLong(DEFAULT_IDLE_MS), DEFAULT_KRAKEN_URL,
                DEFAULT_KRAKEN_WS_URL, defaultKraken());
    }

    private static Limits defaultKraken() {
        return new Limits(Long.parseLong(DEFAULT_STALE_MS), Long.parseLong(DEFAULT_MAX_GAP_MS),
                Double.parseDouble(DEFAULT_MAX_BASIS), Long.parseLong(DEFAULT_KRAKEN_FRESH_MS),
                Long.parseLong(DEFAULT_KRAKEN_SILENCE_MS));
    }

    /** The same settings with other base URLs — for a client talking to a local test server. */
    public SpotPricesConfig withEndpoints(String coinbaseUrl, String coinbaseWsUrl, String hyperliquidWsUrl) {
        return new SpotPricesConfig(coinbaseUrl, coinbaseWsUrl, hyperliquidWsUrl, coinbase, hyperliquidSpot,
                reconnectMaxMs, idleMs, krakenUrl, krakenWsUrl, kraken);
    }

    /** The same settings with Kraken at other addresses. */
    public SpotPricesConfig withKrakenEndpoints(String krakenUrl, String krakenWsUrl) {
        return new SpotPricesConfig(coinbaseUrl, coinbaseWsUrl, hyperliquidWsUrl, coinbase, hyperliquidSpot,
                reconnectMaxMs, idleMs, krakenUrl, krakenWsUrl, kraken);
    }

    public SpotPricesConfig withCoinbase(Limits limits) {
        return new SpotPricesConfig(coinbaseUrl, coinbaseWsUrl, hyperliquidWsUrl, limits, hyperliquidSpot,
                reconnectMaxMs, idleMs, krakenUrl, krakenWsUrl, kraken);
    }

    public SpotPricesConfig withHyperliquidSpot(Limits limits) {
        return new SpotPricesConfig(coinbaseUrl, coinbaseWsUrl, hyperliquidWsUrl, coinbase, limits, reconnectMaxMs,
                idleMs, krakenUrl, krakenWsUrl, kraken);
    }

    public SpotPricesConfig withKraken(Limits limits) {
        return new SpotPricesConfig(coinbaseUrl, coinbaseWsUrl, hyperliquidWsUrl, coinbase, hyperliquidSpot,
                reconnectMaxMs, idleMs, krakenUrl, krakenWsUrl, limits);
    }

    public SpotPricesConfig withIdleMs(long idleMs) {
        return new SpotPricesConfig(coinbaseUrl, coinbaseWsUrl, hyperliquidWsUrl, coinbase, hyperliquidSpot,
                reconnectMaxMs, idleMs, krakenUrl, krakenWsUrl, kraken);
    }
}
