package crowdvsmarket.server.prices;

import crowdvsmarket.server.config.GameMode;

/**
 * The {@code herd.mode} / {@code herd.prices.*} settings the live price pipeline reads
 * (docs/research/markets_architecture.md §3.2, §10), as one immutable value. Built from
 * {@code @ConfigProperty} by {@link PricesConfigProducer}; every key has a default there, so none
 * of them needs a line in {@code application.properties} (the environment variable of a key is its
 * upper-cased name with dots and dashes turned into underscores: {@code herd.prices.source} is
 * {@code HERD_PRICES_SOURCE}).
 *
 * <p>The staleness and gap limits are the architecture's numbers (§3.2 rules 3 and 5): a tick older
 * than {@code staleMs} no longer says what the price is, and two neighbouring ticks of a round
 * window more than {@code maxGapMs} apart are a hole to fill from the source's history. Hyperliquid
 * is polled every 3 s (dex {@code xyz}: 6 s), so 15 s / 20 s are five and three missed polls; the
 * 30 s gap is ten. Polymarket is polled every 3 s as well, but its CLOB midpoint only changes with
 * the book, so its limits are looser (30 s / 90 s). The synthetic walk ticks every second.
 *
 * @param liveMode          {@code herd.mode=live}; in replay mode none of the pipeline runs
 * @param source            {@code herd.prices.source}: real sources or the deterministic walk
 * @param dailyRoundMs      length of the DAILY series round; the tick buffer covers twice that
 */
public record PricesConfig(
        boolean liveMode,
        SourceKind source,
        long dailyRoundMs,
        String hyperliquidUrl,
        String polymarketClobUrl,
        String polymarketGammaUrl,
        String polymarketDataUrl,
        long hyperliquidStaleMs,
        long hyperliquidXyzStaleMs,
        long hyperliquidMaxGapMs,
        long polymarketStaleMs,
        long polymarketMaxGapMs,
        long syntheticStaleMs,
        long syntheticMaxGapMs,
        long backfillCooldownMs,
        int connectTimeoutMs,
        int readTimeoutMs) {

    /** {@code herd.prices.source}: {@code live} polls Hyperliquid and Polymarket,
     *  {@code synthetic} is the offline walk for CI, e2e and work without a network (Т-12). */
    public enum SourceKind {
        LIVE, SYNTHETIC;

        public static SourceKind parse(String raw) {
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("herd.prices.source must be live or synthetic, got '" + raw + "'", e);
            }
        }
    }

    // Defaults live here, as compile-time constants, so the @ConfigProperty annotations in the
    // producer, the @RegisterRestClient base URIs and defaults() cannot drift apart.
    public static final String DEFAULT_MODE = "replay";
    public static final String DEFAULT_SOURCE = "live";
    public static final String DEFAULT_DAILY_SEC = "900";
    public static final String DEFAULT_HYPERLIQUID_URL = "https://api.hyperliquid.xyz";
    public static final String DEFAULT_POLYMARKET_CLOB_URL = "https://clob.polymarket.com";
    public static final String DEFAULT_POLYMARKET_GAMMA_URL = "https://gamma-api.polymarket.com";
    public static final String DEFAULT_POLYMARKET_DATA_URL = "https://data-api.polymarket.com";
    public static final String DEFAULT_HYPERLIQUID_STALE_MS = "15000";
    public static final String DEFAULT_HYPERLIQUID_XYZ_STALE_MS = "20000";
    public static final String DEFAULT_HYPERLIQUID_MAX_GAP_MS = "30000";
    public static final String DEFAULT_POLYMARKET_STALE_MS = "30000";
    public static final String DEFAULT_POLYMARKET_MAX_GAP_MS = "90000";
    public static final String DEFAULT_SYNTHETIC_STALE_MS = "5000";
    public static final String DEFAULT_SYNTHETIC_MAX_GAP_MS = "10000";
    public static final String DEFAULT_BACKFILL_COOLDOWN_MS = "60000";
    /** Connect 3 s, read 5 s: {@code resolve} blocks on these (markets_architecture.md §3.2). */
    public static final String DEFAULT_CONNECT_TIMEOUT_MS = "3000";
    public static final String DEFAULT_READ_TIMEOUT_MS = "5000";

    /** The tick buffer keeps 10 minutes more than two DAILY rounds (§3.2 rule 2). */
    public static final long RETENTION_EXTRA_MS = 600_000L;

    /** The defaults above in live mode with the stand's 15-minute DAILY round — for tests. */
    public static PricesConfig defaults() {
        return new PricesConfig(true, SourceKind.LIVE, Long.parseLong(DEFAULT_DAILY_SEC) * 1000L,
                DEFAULT_HYPERLIQUID_URL, DEFAULT_POLYMARKET_CLOB_URL, DEFAULT_POLYMARKET_GAMMA_URL,
                DEFAULT_POLYMARKET_DATA_URL, Long.parseLong(DEFAULT_HYPERLIQUID_STALE_MS),
                Long.parseLong(DEFAULT_HYPERLIQUID_XYZ_STALE_MS), Long.parseLong(DEFAULT_HYPERLIQUID_MAX_GAP_MS),
                Long.parseLong(DEFAULT_POLYMARKET_STALE_MS), Long.parseLong(DEFAULT_POLYMARKET_MAX_GAP_MS),
                Long.parseLong(DEFAULT_SYNTHETIC_STALE_MS), Long.parseLong(DEFAULT_SYNTHETIC_MAX_GAP_MS),
                Long.parseLong(DEFAULT_BACKFILL_COOLDOWN_MS), Integer.parseInt(DEFAULT_CONNECT_TIMEOUT_MS),
                Integer.parseInt(DEFAULT_READ_TIMEOUT_MS));
    }

    /** The same settings with another mode and source. */
    public PricesConfig withMode(boolean liveMode, SourceKind source) {
        return new PricesConfig(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl,
                polymarketDataUrl, hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs,
                polymarketMaxGapMs, syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs,
                readTimeoutMs);
    }

    /** The same settings with other base URLs and timeouts — for a client talking to a local test server. */
    public PricesConfig withEndpoints(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        return new PricesConfig(liveMode, source, dailyRoundMs, baseUrl, baseUrl, baseUrl, baseUrl, hyperliquidStaleMs,
                hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs, polymarketMaxGapMs, syntheticStaleMs,
                syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs, readTimeoutMs);
    }

    /** {@code herd.mode} text → {@code liveMode}, with the same parser the rest of the server uses. */
    public static boolean isLiveMode(String rawMode) {
        return GameMode.parse(rawMode) == GameMode.LIVE;
    }

    /** Ring length and snapshot retention: two DAILY rounds plus 10 minutes. */
    public long retentionMs() {
        return 2 * dailyRoundMs + RETENTION_EXTRA_MS;
    }

    /** Hyperliquid and Polymarket are polled (mode=live, source=live). */
    public boolean liveSources() {
        return liveMode && source == SourceKind.LIVE;
    }

    /** The synthetic walk runs (mode=live, source=synthetic). */
    public boolean syntheticSource() {
        return liveMode && source == SourceKind.SYNTHETIC;
    }

    /** Some price source runs at all. */
    public boolean pipelineActive() {
        return liveMode;
    }
}
