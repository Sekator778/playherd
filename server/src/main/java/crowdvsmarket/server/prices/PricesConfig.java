package crowdvsmarket.server.prices;

import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.markets.Market;
import java.util.Set;

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
 * @param quietCarryMaxMs   {@code herd.prices.quiet-carry-max-ms}: the longest hole across which a quiet asset's
 *                          last live price is carried (see {@link LiveMarketPrices}); a longer one is no flat line
 * @param hyperliquidSeedBudgetPerMinute {@code herd.prices.hyperliquid.seed-budget-per-minute}: how much of
 *                          Hyperliquid's 1200 weight a minute {@link HistorySeeder} may spend on seeding in
 *                          one minute (the rest is polling, the catalog and settlement backfills)
 * @param requireMinuteAlignedRounds {@code herd.prices.require-minute-aligned-rounds}: with real sources the
 *                          server refuses to start unless every round boundary is on a whole minute
 *                          ({@link RoundAlignmentCheck}); {@code false} only warns
 * @param hyperliquidMaxBasis {@code herd.prices.hyperliquid.max-basis}: how far a live mark may lie outside the
 *                          range its own minute's candle traded at, relative to the mark, for the candles to bridge
 *                          a gap between two live ticks ({@link GapBridge}); default 0.0015 (15 bp)
 * @param hyperliquidMaxHealMs {@code herd.prices.hyperliquid.max-heal-ms}: the longest gap between two live ticks
 *                          that candles bridge; default 30 minutes; 0 switches bridging off
 * @param disabledVenues    {@code herd.prices.disabled-venues}: venues ({@code hyperliquid}, {@code coinbase},
 *                          {@code kraken}, {@code polymarket}) that give no price at all — no stream, no REST, no
 *                          catalog request — so that a stand can be restarted with a primary exchange off to prove that
 *                          the backup answers; empty by default
 * @param venueReleaseAfterMs {@code herd.prices.venue-release-after-ms}: how long after its end a round of a market with
 *                          a backup exchange that its own exchange cannot price waits, while the market's other exchange
 *                          is alive, before it is cancelled with a refund so that the series goes on (the venue release,
 *                          see {@code RoundSchedulerService}); default 2 minutes; 0 switches the release off — the round
 *                          then waits for its exchange for the hour of Р-23
 * @param releaseAfter      {@code herd.prices.<feed>.release-after-ms}: how long after its end a round that its price source
 *                          cannot price waits, on any market, before it is cancelled with a refund so that the series goes on
 *                          (Р-79, {@link ReleaseAfter}); the minutes are the source's own, 0 for a source switches the release
 *                          off for it — its rounds then wait for the hour of Р-23
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
        int readTimeoutMs,
        long quietCarryMaxMs,
        int hyperliquidSeedBudgetPerMinute,
        boolean requireMinuteAlignedRounds,
        double hyperliquidMaxBasis,
        long hyperliquidMaxHealMs,
        Set<String> disabledVenues,
        long venueReleaseAfterMs,
        ReleaseAfter releaseAfter) {

    /** The venues that {@code herd.prices.disabled-venues} may name: the sources of real prices. */
    public static final Set<String> VENUES = Set.of("hyperliquid", "coinbase", "kraken", "polymarket");

    /** {@code herd.prices.source}: {@code live} reads Hyperliquid, Coinbase, Kraken and Polymarket,
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

    /** The settings that existed before the quiet-carry cap, the seed budget and the alignment check, with their defaults for those. */
    public PricesConfig(boolean liveMode, SourceKind source, long dailyRoundMs, String hyperliquidUrl,
                        String polymarketClobUrl, String polymarketGammaUrl, String polymarketDataUrl,
                        long hyperliquidStaleMs, long hyperliquidXyzStaleMs, long hyperliquidMaxGapMs,
                        long polymarketStaleMs, long polymarketMaxGapMs, long syntheticStaleMs, long syntheticMaxGapMs,
                        long backfillCooldownMs, int connectTimeoutMs, int readTimeoutMs) {
        this(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl, polymarketDataUrl,
                hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs, polymarketMaxGapMs,
                syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs, readTimeoutMs,
                Long.parseLong(DEFAULT_QUIET_CARRY_MAX_MS), Integer.parseInt(DEFAULT_HYPERLIQUID_SEED_BUDGET),
                Boolean.parseBoolean(DEFAULT_REQUIRE_ALIGNED_ROUNDS));
    }

    /** The settings of before the bridge over a gap between live ticks (20 components), with its defaults. */
    public PricesConfig(boolean liveMode, SourceKind source, long dailyRoundMs, String hyperliquidUrl,
                        String polymarketClobUrl, String polymarketGammaUrl, String polymarketDataUrl,
                        long hyperliquidStaleMs, long hyperliquidXyzStaleMs, long hyperliquidMaxGapMs,
                        long polymarketStaleMs, long polymarketMaxGapMs, long syntheticStaleMs, long syntheticMaxGapMs,
                        long backfillCooldownMs, int connectTimeoutMs, int readTimeoutMs, long quietCarryMaxMs,
                        int hyperliquidSeedBudgetPerMinute, boolean requireMinuteAlignedRounds) {
        this(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl, polymarketDataUrl,
                hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs, polymarketMaxGapMs,
                syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs, readTimeoutMs,
                quietCarryMaxMs, hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds,
                Double.parseDouble(DEFAULT_HYPERLIQUID_MAX_BASIS), Long.parseLong(DEFAULT_HYPERLIQUID_MAX_HEAL_MS));
    }

    /** The settings of before the switch for venues (22 components), with none disabled. */
    public PricesConfig(boolean liveMode, SourceKind source, long dailyRoundMs, String hyperliquidUrl,
                        String polymarketClobUrl, String polymarketGammaUrl, String polymarketDataUrl,
                        long hyperliquidStaleMs, long hyperliquidXyzStaleMs, long hyperliquidMaxGapMs,
                        long polymarketStaleMs, long polymarketMaxGapMs, long syntheticStaleMs, long syntheticMaxGapMs,
                        long backfillCooldownMs, int connectTimeoutMs, int readTimeoutMs, long quietCarryMaxMs,
                        int hyperliquidSeedBudgetPerMinute, boolean requireMinuteAlignedRounds,
                        double hyperliquidMaxBasis, long hyperliquidMaxHealMs) {
        this(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl, polymarketDataUrl,
                hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs, polymarketMaxGapMs,
                syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs, readTimeoutMs,
                quietCarryMaxMs, hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds, hyperliquidMaxBasis,
                hyperliquidMaxHealMs, Set.of());
    }

    /** The settings of before the release of a round from a silent exchange (23 components), with its default. */
    public PricesConfig(boolean liveMode, SourceKind source, long dailyRoundMs, String hyperliquidUrl,
                        String polymarketClobUrl, String polymarketGammaUrl, String polymarketDataUrl,
                        long hyperliquidStaleMs, long hyperliquidXyzStaleMs, long hyperliquidMaxGapMs,
                        long polymarketStaleMs, long polymarketMaxGapMs, long syntheticStaleMs, long syntheticMaxGapMs,
                        long backfillCooldownMs, int connectTimeoutMs, int readTimeoutMs, long quietCarryMaxMs,
                        int hyperliquidSeedBudgetPerMinute, boolean requireMinuteAlignedRounds,
                        double hyperliquidMaxBasis, long hyperliquidMaxHealMs, Set<String> disabledVenues) {
        this(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl, polymarketDataUrl,
                hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs, polymarketMaxGapMs,
                syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs, readTimeoutMs,
                quietCarryMaxMs, hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds, hyperliquidMaxBasis,
                hyperliquidMaxHealMs, disabledVenues, Long.parseLong(DEFAULT_VENUE_RELEASE_AFTER_MS));
    }

    /** The settings of before the release of a round whose source gives no price (24 components), with its defaults. */
    public PricesConfig(boolean liveMode, SourceKind source, long dailyRoundMs, String hyperliquidUrl,
                        String polymarketClobUrl, String polymarketGammaUrl, String polymarketDataUrl,
                        long hyperliquidStaleMs, long hyperliquidXyzStaleMs, long hyperliquidMaxGapMs,
                        long polymarketStaleMs, long polymarketMaxGapMs, long syntheticStaleMs, long syntheticMaxGapMs,
                        long backfillCooldownMs, int connectTimeoutMs, int readTimeoutMs, long quietCarryMaxMs,
                        int hyperliquidSeedBudgetPerMinute, boolean requireMinuteAlignedRounds,
                        double hyperliquidMaxBasis, long hyperliquidMaxHealMs, Set<String> disabledVenues,
                        long venueReleaseAfterMs) {
        this(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl, polymarketDataUrl,
                hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs, polymarketMaxGapMs,
                syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs, readTimeoutMs,
                quietCarryMaxMs, hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds, hyperliquidMaxBasis,
                hyperliquidMaxHealMs, disabledVenues, venueReleaseAfterMs, ReleaseAfter.defaults());
    }

    public PricesConfig {
        for (String venue : disabledVenues) {
            if (!VENUES.contains(venue)) {
                throw new IllegalArgumentException("herd.prices.disabled-venues names '" + venue
                        + "', which is no venue: " + new java.util.TreeSet<>(VENUES));
            }
        }
        disabledVenues = Set.copyOf(disabledVenues);
        if (Double.isNaN(hyperliquidMaxBasis) || Double.isInfinite(hyperliquidMaxBasis) || hyperliquidMaxBasis < 0) {
            throw new IllegalArgumentException("herd.prices.hyperliquid.max-basis must be a relative distance of at "
                    + "least 0, got " + hyperliquidMaxBasis);
        }
        if (hyperliquidMaxHealMs < 0) {
            throw new IllegalArgumentException("herd.prices.hyperliquid.max-heal-ms must be at least 0 (0 switches "
                    + "bridging off), got " + hyperliquidMaxHealMs);
        }
        if (venueReleaseAfterMs < 0) {
            throw new IllegalArgumentException("herd.prices.venue-release-after-ms must be at least 0 (0 switches the "
                    + "release of a round from a silent exchange off), got " + venueReleaseAfterMs);
        }
    }

    /**
     * How long after its end a round that its price source cannot price waits, on any market, before it is cancelled with a refund so
     * that its series goes on (Р-79; the rounds of a series are settled in order, so a round without a price holds the series). One
     * wait per source, in milliseconds. {@code 0} switches the release off for a source: its rounds then wait for the hour of Р-23,
     * as before.
     *
     * @param hyperliquidMs     {@code herd.prices.hyperliquid.release-after-ms}: the perpetuals of both dexes, the feeds
     *                          {@code hyperliquid} and {@code hyperliquid:xyz}
     * @param hyperliquidSpotMs {@code herd.prices.hyperliquid.spot-release-after-ms}: the feed {@code hyperliquid:spot}
     * @param coinbaseMs        {@code herd.prices.coinbase.release-after-ms}
     * @param krakenMs          {@code herd.prices.kraken.release-after-ms}
     * @param polymarketMs      {@code herd.prices.polymarket.release-after-ms}
     * @param syntheticMs       {@code herd.prices.synthetic.release-after-ms}
     */
    public record ReleaseAfter(long hyperliquidMs, long hyperliquidSpotMs, long coinbaseMs, long krakenMs,
                               long polymarketMs, long syntheticMs) {

        public ReleaseAfter {
            require("herd.prices.hyperliquid.release-after-ms", hyperliquidMs);
            require("herd.prices.hyperliquid.spot-release-after-ms", hyperliquidSpotMs);
            require("herd.prices.coinbase.release-after-ms", coinbaseMs);
            require("herd.prices.kraken.release-after-ms", krakenMs);
            require("herd.prices.polymarket.release-after-ms", polymarketMs);
            require("herd.prices.synthetic.release-after-ms", syntheticMs);
        }

        private static void require(String key, long ms) {
            if (ms < 0) {
                throw new IllegalArgumentException(key + " must be at least 0 (0 switches the release of a round whose "
                        + "source gives no price off for that source), got " + ms);
            }
        }

        /** The defaults of {@link PricesConfig}: five minutes, fifteen for Polymarket. */
        public static ReleaseAfter defaults() {
            long fiveMinutes = Long.parseLong(DEFAULT_RELEASE_AFTER_MS);
            return new ReleaseAfter(fiveMinutes, fiveMinutes, fiveMinutes, fiveMinutes,
                    Long.parseLong(DEFAULT_POLYMARKET_RELEASE_AFTER_MS), fiveMinutes);
        }

        /** No source is let go from: every round that cannot be priced waits for the hour of Р-23. */
        public static ReleaseAfter off() {
            return new ReleaseAfter(0, 0, 0, 0, 0, 0);
        }

        /** The wait of the feed whose id the sources give ({@link PriceSource.Feed#id}); 0 — none — for a feed that is not known. */
        public long forFeed(String feedId) {
            return switch (feedId) {
                case HyperliquidPriceSource.FEED_MAIN, HyperliquidPriceSource.FEED_XYZ -> hyperliquidMs;
                case HyperliquidSpotPriceSource.FEED -> hyperliquidSpotMs;
                case CoinbasePriceSource.FEED -> coinbaseMs;
                case KrakenPriceSource.FEED -> krakenMs;
                case PolymarketPriceSource.FEED -> polymarketMs;
                case SyntheticPriceSource.FEED -> syntheticMs;
                default -> 0L;
            };
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
    /** Ten minutes: a flat line across a longer outage is more than a quiet asset (предложение, same as Polymarket's bucket carry). */
    public static final String DEFAULT_QUIET_CARRY_MAX_MS = "600000";
    /** 1200 a minute − 600 polling − 40 catalog = 560 spare; seeds may take 260 of it, so the first minute stays near 900 (see {@link HistorySeeder}). */
    public static final String DEFAULT_HYPERLIQUID_SEED_BUDGET = "260";
    public static final String DEFAULT_REQUIRE_ALIGNED_ROUNDS = "true";
    /**
     * 15 basis points (0.15%): three times the farthest a mark was seen from the range of its own minute's candle
     * (5.2 bp over 7 476 marks of the 12 default markets, hl_multi_asset_live.md §13.4), and an eighth of the
     * distance at which a 40x position is liquidated (about 1.25%).
     */
    public static final String DEFAULT_HYPERLIQUID_MAX_BASIS = "0.0015";
    /**
     * Half an hour: half of the shortest production round (the HOURLY one), so one gap never puts more than half of a
     * round on trade prices, and about the stretch the measurement behind the basis covers (41 minutes of the stand's
     * marks, hl_multi_asset_live.md §13.4).
     */
    public static final String DEFAULT_HYPERLIQUID_MAX_HEAL_MS = "1800000";
    /**
     * Two minutes — Alex's choice of 2026-10-03 among the ways to end the stall of a series behind a round whose exchange went
     * silent (docs/product/decisions.md, open question 8, variant б; recorded as Р-77): long enough for the first retries and
     * one backfill of the candles (a minute apart, {@link #DEFAULT_BACKFILL_COOLDOWN_MS}) to heal a short gap, short enough that
     * a series does not stand for the hour that Р-23 gives a round without a price.
     */
    public static final String DEFAULT_VENUE_RELEASE_AFTER_MS = "120000";
    /**
     * Five minutes — "about 5 minutes for the candle sources" of the analysis behind Р-79 (docs/product/decisions.md; variant A of
     * docs/research/series_blocking.md §3, a proposal and no measurement): longer than the first retries and one backfill of the
     * candles need to heal a short gap (a minute apart, {@link #DEFAULT_BACKFILL_COOLDOWN_MS}), far shorter than the hour of Р-23.
     * The wait of Hyperliquid, Coinbase, Kraken and the synthetic walk.
     */
    public static final String DEFAULT_RELEASE_AFTER_MS = "300000";
    /**
     * A quarter of an hour for Polymarket — "longer for Polymarket" of the same analysis, a proposal: its midpoint changes only with
     * the book, so its limits are the loosest of the feeds (30 s and 90 s).
     */
    public static final String DEFAULT_POLYMARKET_RELEASE_AFTER_MS = "900000";

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
                Integer.parseInt(DEFAULT_READ_TIMEOUT_MS), Long.parseLong(DEFAULT_QUIET_CARRY_MAX_MS),
                Integer.parseInt(DEFAULT_HYPERLIQUID_SEED_BUDGET), Boolean.parseBoolean(DEFAULT_REQUIRE_ALIGNED_ROUNDS),
                Double.parseDouble(DEFAULT_HYPERLIQUID_MAX_BASIS), Long.parseLong(DEFAULT_HYPERLIQUID_MAX_HEAL_MS));
    }

    /** The same settings with another mode and source. */
    public PricesConfig withMode(boolean liveMode, SourceKind source) {
        return new PricesConfig(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl,
                polymarketDataUrl, hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs,
                polymarketMaxGapMs, syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs,
                readTimeoutMs, quietCarryMaxMs, hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds,
                hyperliquidMaxBasis, hyperliquidMaxHealMs, disabledVenues, venueReleaseAfterMs, releaseAfter);
    }

    /** The same settings with other base URLs and timeouts — for a client talking to a local test server. */
    public PricesConfig withEndpoints(String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        return new PricesConfig(liveMode, source, dailyRoundMs, baseUrl, baseUrl, baseUrl, baseUrl, hyperliquidStaleMs,
                hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs, polymarketMaxGapMs, syntheticStaleMs,
                syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs, readTimeoutMs, quietCarryMaxMs,
                hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds, hyperliquidMaxBasis, hyperliquidMaxHealMs,
                disabledVenues, venueReleaseAfterMs, releaseAfter);
    }

    /** The same settings with another longest quiet carry. */
    public PricesConfig withQuietCarryMaxMs(long maxMs) {
        return new PricesConfig(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl,
                polymarketDataUrl, hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs,
                polymarketMaxGapMs, syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs,
                readTimeoutMs, maxMs, hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds, hyperliquidMaxBasis,
                hyperliquidMaxHealMs, disabledVenues, venueReleaseAfterMs, releaseAfter);
    }

    /** The same settings with another seed budget. */
    public PricesConfig withHyperliquidSeedBudget(int weightPerMinute) {
        return new PricesConfig(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl,
                polymarketDataUrl, hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs,
                polymarketMaxGapMs, syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs,
                readTimeoutMs, quietCarryMaxMs, weightPerMinute, requireMinuteAlignedRounds, hyperliquidMaxBasis,
                hyperliquidMaxHealMs, disabledVenues, venueReleaseAfterMs, releaseAfter);
    }

    /** The same settings with another daily round — for the budget arithmetic of a seed. */
    public PricesConfig withDailyRoundMs(long dailyMs) {
        return new PricesConfig(liveMode, source, dailyMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl,
                polymarketDataUrl, hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs,
                polymarketMaxGapMs, syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs,
                readTimeoutMs, quietCarryMaxMs, hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds,
                hyperliquidMaxBasis, hyperliquidMaxHealMs, disabledVenues, venueReleaseAfterMs, releaseAfter);
    }

    /** The same settings with another tolerance between a live mark and the candle of its minute. */
    public PricesConfig withHyperliquidMaxBasis(double maxBasis) {
        return new PricesConfig(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl,
                polymarketDataUrl, hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs,
                polymarketMaxGapMs, syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs,
                readTimeoutMs, quietCarryMaxMs, hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds, maxBasis,
                hyperliquidMaxHealMs, disabledVenues, venueReleaseAfterMs, releaseAfter);
    }

    /** The same settings with another longest gap that candles bridge (0: none). */
    public PricesConfig withHyperliquidMaxHealMs(long maxMs) {
        return new PricesConfig(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl,
                polymarketDataUrl, hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs,
                polymarketMaxGapMs, syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs,
                readTimeoutMs, quietCarryMaxMs, hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds,
                hyperliquidMaxBasis, maxMs, disabledVenues, venueReleaseAfterMs, releaseAfter);
    }

    /** The same settings with these venues switched off ({@code herd.prices.disabled-venues}). */
    public PricesConfig withDisabledVenues(Set<String> venues) {
        return new PricesConfig(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl,
                polymarketDataUrl, hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs,
                polymarketMaxGapMs, syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs,
                readTimeoutMs, quietCarryMaxMs, hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds,
                hyperliquidMaxBasis, hyperliquidMaxHealMs, venues, venueReleaseAfterMs, releaseAfter);
    }

    /** The same settings with another wait before a round is let go from a silent exchange (0: never). */
    public PricesConfig withVenueReleaseAfterMs(long afterMs) {
        return new PricesConfig(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl,
                polymarketDataUrl, hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs,
                polymarketMaxGapMs, syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs,
                readTimeoutMs, quietCarryMaxMs, hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds,
                hyperliquidMaxBasis, hyperliquidMaxHealMs, disabledVenues, afterMs, releaseAfter);
    }

    /** The same settings with other waits before a round that its source cannot price is let go (Р-79). */
    public PricesConfig withReleaseAfter(ReleaseAfter waits) {
        return new PricesConfig(liveMode, source, dailyRoundMs, hyperliquidUrl, polymarketClobUrl, polymarketGammaUrl,
                polymarketDataUrl, hyperliquidStaleMs, hyperliquidXyzStaleMs, hyperliquidMaxGapMs, polymarketStaleMs,
                polymarketMaxGapMs, syntheticStaleMs, syntheticMaxGapMs, backfillCooldownMs, connectTimeoutMs,
                readTimeoutMs, quietCarryMaxMs, hyperliquidSeedBudgetPerMinute, requireMinuteAlignedRounds,
                hyperliquidMaxBasis, hyperliquidMaxHealMs, disabledVenues, venueReleaseAfterMs, waits);
    }

    /**
     * How long after its end a round priced on {@code instrument} — a market, or the shadow of one ({@link Instruments}) — waits when
     * it cannot be priced, before it is cancelled with a refund (Р-79): the wait of the feed that prices the instrument
     * ({@link ReleaseAfter}), or of the synthetic walk when that prices everything ({@code herd.prices.source=synthetic}). 0 — never,
     * the hour of Р-23 — when the rule is off for the source, when no feed prices the instrument, and in replay mode, which has no
     * exchange to wait for.
     */
    public long releaseAfterMs(Market instrument) {
        if (!liveMode) {
            return 0L;
        }
        if (source == SourceKind.SYNTHETIC) {
            return releaseAfter.forFeed(SyntheticPriceSource.FEED);
        }
        return Instruments.feedOf(instrument).map(releaseAfter::forFeed).orElse(0L);
    }

    /** Whether the venue is in {@code herd.prices.disabled-venues}: it gives no price, over no channel. */
    public boolean venueDisabled(String venue) {
        return disabledVenues.contains(venue);
    }

    /** {@code herd.prices.disabled-venues} text → the set of venues, lower case; blanks and repeats dropped. */
    public static Set<String> parseVenues(String csv) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String venue = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (!venue.isEmpty()) {
                out.add(venue);
            }
        }
        return out;
    }

    /** {@code herd.mode} text → {@code liveMode}, with the same parser the rest of the server uses. */
    public static boolean isLiveMode(String rawMode) {
        return GameMode.parse(rawMode) == GameMode.LIVE;
    }

    /** Ring length and snapshot retention: two DAILY rounds plus 10 minutes. */
    public long retentionMs() {
        return 2 * dailyRoundMs + RETENTION_EXTRA_MS;
    }

    /** The real sources run — Hyperliquid, Coinbase, Kraken, Polymarket (mode=live, source=live). */
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
