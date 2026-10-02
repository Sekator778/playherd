package crowdvsmarket.server.prices;

import crowdvsmarket.server.markets.Market;
import io.quarkus.arc.Unremovable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * Gives a market its recent past the moment it is listed — and to every active market that has no tick
 * fresh at the start of a running round window (a start with no snapshots, a market whose rows were pruned,
 * a restart that straddled a round start, a first seed that failed) — so that the first running round has
 * an opening price, {@code soFar} works at once and the room's chart is not empty. A market listed in the
 * middle of a round window has no tick before the window's start — until the source's history is asked for
 * it — and its first round would otherwise wait, without a running path, until it settles by backfill.
 *
 * <p>The history is the source's own ({@link PriceSource#history}: Hyperliquid's 1-minute candles,
 * Polymarket's 60-second buckets, the synthetic walk) for the last
 * {@code max(15 minutes, the DAILY round)}, up to a day: a window of the longest series that is
 * running now started at most that long ago, and its opening price lies in that stretch. On the
 * stand — rounds of 3 and 15 minutes — that is 15 minutes. What of it is merged depends on what the history is
 * ({@link PriceSource.HistoryKind}): any hole of the ring for a source whose history is the same number as
 * its ticks ({@link TickStore#fillHoles}); only the past of the first live tick for Hyperliquid, whose candles are
 * trade prices ({@link TickStore#seedHistory}). Never over a tick, never at or before a boundary a round settled
 * on, without a snapshot row — a seed can never change a price a settled round used.
 *
 * <p><b>When.</b> Right after a catalog lists a market ({@link #seed}), and from every poll of a feed
 * ({@link #seedMissing}) for the markets that have no tick fresh at the start of a running window
 * ({@link RunningWindows}) — and, for a source whose history may only be the past of the first live tick, no live
 * tick at or before that start (history could not be merged otherwise). Each (market, running starts) is tried
 * once; a try that failed — or that left the market still without a price at the start it was about, the source
 * having nothing yet (Polymarket's buckets materialise minutes late) — is repeated after a minute and then after
 * twice as long as the last time, up to a quarter of an hour; one that succeeded is not repeated in that window.
 * A seed asks for the history of the last day or minutes and only ever adds.
 *
 * <p><b>Pacing: Hyperliquid's budget.</b> The limit is 1200 weight a minute per IP (hl_multi_asset_live.md §5.1).
 * Polling takes 600 (the default dex every 3 s is 20 × 20, {@code xyz} every 6 s is 10 × 20) and the catalog 40
 * (one {@code metaAndAssetCtxs} per dex a minute while something is unlisted). A seed is a
 * {@code candleSnapshot}: weight 20 plus 1 per 60 rows (the rounding is UNVERIFIED, counted up), and it asks
 * for the span plus the 30 minutes the source looks back. On the stand that is 45 rows, weight 21; with the
 * production durations (the DAILY round is a day) 1470 rows, weight 45. Twelve Hyperliquid markets seeded at once
 * cost 252 (stand) or 540 (production) in the first minute — on top of 640 that is 892 or 1180 of 1200, nothing
 * left for a settlement backfill (another 21). So the seeds are <b>staggered</b>: at most
 * {@link PricesConfig#hyperliquidSeedBudgetPerMinute()} (default 260, which is 900 − 640) of weight start in a
 * minute, one seed every {@code 60 s / floor(budget / weight)} — every 5 s on the stand (12 a minute, 252, a first
 * minute of 892) and every 12 s in production (5 a minute, 225, a first minute of 865; the twelve take two
 * minutes). A market whose seed waits has its first prices from its live polls, as it always had until the seed
 * lands. Other sources have no weight and are spaced {@link #OTHER_SPACING_MS} apart.
 *
 * <p>The request runs on a background thread of its own ({@code price-history-seed}, one at a time) and holds no
 * lock while it waits for the source: not the engine's, not the registry's, none that {@link MarketPrices#latest},
 * {@code series} or {@code soFar} take — the ring's monitor is taken per point by the merge, for the length of one
 * array insert, like a live poll. A failure (the source down, a bad answer) is a warning, throttled to one a minute
 * per market, and never an error: the market simply gets its first prices from the live polls, and the backfill at
 * settlement still covers a hole.
 *
 * <p>Nothing here runs outside {@code herd.mode=live}, and nothing runs unless something calls it: the
 * catalogs and the feed service do, both from scheduler jobs — with the scheduler off (a test of the
 * game core) the network is never touched.
 */
@ApplicationScoped
@Unremovable
public class HistorySeeder {

    private static final Logger LOG = Logger.getLogger(HistorySeeder.class);

    /** The least history a market is seeded with: the stand's DAILY round. */
    static final long MIN_SEED_MS = 15 * 60_000L;
    /** The most: a day, whatever the DAILY round is. */
    static final long MAX_SEED_MS = 24 * 3_600_000L;
    /** A failed seed is tried again this long after the failure, then after twice as long, and so on. */
    static final long RETRY_AFTER_MS = 60_000L;
    /** ... but never later than this. */
    static final long MAX_RETRY_MS = 15 * 60_000L;
    /** Seeds of a source with no request weight to count are this far apart. */
    static final long OTHER_SPACING_MS = 1_000L;

    /** Runs a task after a delay: the production one is a daemon thread, a test hands in its own. */
    @FunctionalInterface
    interface Scheduler {
        void schedule(Runnable task, long delayMs);
    }

    /** Where the last try for a market is: waiting in line (or running), over, or over and failed. */
    private enum State {
        QUEUED, DONE, FAILED
    }

    /** What the last try for a market was about (the running starts it was made for) and how it stands. */
    private record Attempt(String key, long atMs, int failures, State state) {
    }

    private final PriceSources sources;
    private final TickStore store;
    private final PricesConfig config;
    private final LongSupplier clock;
    private final Scheduler scheduler;
    private final LongFunction<long[]> runningStarts;
    private final boolean enabled;
    private final ThrottledLog warnings;
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> nextSlotMs = new ConcurrentHashMap<>();
    private ScheduledThreadPoolExecutor pool; // created by the first seed, guarded by this

    @Inject
    public HistorySeeder(PriceSources sources, TickStore store, PricesConfig config, RunningWindows windows) {
        this(sources, store, config, System::currentTimeMillis, null, windows::startsAt);
    }

    /** For tests: the given executor runs the seeds at once, in order (a direct one makes them synchronous); no calendar. */
    HistorySeeder(PriceSources sources, TickStore store, PricesConfig config, LongSupplier clock,
                  Consumer<Runnable> executor) {
        this(sources, store, config, clock, executor == null ? null : (task, delay) -> executor.accept(task),
                now -> new long[0]);
    }

    /** For tests: a scheduler that sees the delays, and the starts of the running windows at a time. */
    HistorySeeder(PriceSources sources, TickStore store, PricesConfig config, LongSupplier clock, Scheduler scheduler,
                  LongFunction<long[]> runningStarts) {
        this(sources, store, config, clock, scheduler, runningStarts, new ThrottledLog(LOG, clock, 60_000L));
    }

    HistorySeeder(PriceSources sources, TickStore store, PricesConfig config, LongSupplier clock, Scheduler scheduler,
                  LongFunction<long[]> runningStarts, ThrottledLog warnings) {
        this.sources = sources;
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.scheduler = scheduler == null ? this::runInBackground : scheduler;
        this.runningStarts = runningStarts;
        this.enabled = true;
        this.warnings = warnings;
    }

    private HistorySeeder(boolean enabled) {
        this.sources = null;
        this.store = null;
        this.config = null;
        this.clock = null;
        this.scheduler = null;
        this.runningStarts = null;
        this.enabled = enabled;
        this.warnings = null;
    }

    /** A seeder that does nothing — for the catalogs of a unit test that does not look at seeding. */
    public static HistorySeeder disabled() {
        return new HistorySeeder(false);
    }

    /** How far back a seed asks: {@code max(15 minutes, the DAILY round)}, at most a day. */
    long seedSpanMs() {
        return Math.min(MAX_SEED_MS, Math.max(MIN_SEED_MS, config.dailyRoundMs()));
    }

    /** Queues the seeding of a market that was just listed and returns at once. */
    public void seed(Market market) {
        if (!enabled || !config.pipelineActive()) {
            return;
        }
        PriceSources.Binding binding = sources.bindingFor(market).orElse(null);
        if (binding == null) {
            return;
        }
        long now = clock.getAsLong();
        attempts.put(market.id(), new Attempt(key(runningStarts.apply(now)), now, 0, State.QUEUED));
        queue(market, binding);
    }

    /**
     * Seeds every given market that has no tick fresh at the start of a window running now and has not been
     * tried for these starts (or whose failed try is due again); returns how many were queued. Cheap when
     * nothing is missing: it is called on every poll of a feed.
     */
    public int seedMissing(Collection<Market> markets) {
        if (!enabled || !config.pipelineActive() || markets.isEmpty()) {
            return 0;
        }
        long now = clock.getAsLong();
        long[] starts = runningStarts.apply(now);
        List<Market> due = new ArrayList<>();
        List<PriceSources.Binding> bindings = new ArrayList<>();
        for (Market market : markets) {
            PriceSources.Binding binding = sources.bindingFor(market).orElse(null);
            if (binding != null && needsSeed(market, binding, starts) && claim(market.id(), key(starts), now)) {
                due.add(market);
                bindings.add(binding);
            }
        }
        for (int i = 0; i < due.size(); i++) {
            queue(due.get(i), bindings.get(i));
        }
        return due.size();
    }

    /**
     * Whether a seed could give the market an opening price it lacks: no tick (of any kind: a pin is a price
     * too) is fresh at a running window's start — and for a source whose history is the past of the first live
     * tick only, no live tick lies at or before that start. Without a calendar (no starts), an empty ring.
     */
    private boolean needsSeed(Market market, PriceSources.Binding binding, long[] starts) {
        if (starts.length == 0) {
            return store.size(market.id()) == 0;
        }
        boolean tradePrices = binding.source().historyKind() == PriceSource.HistoryKind.TRADE_PRICES;
        for (long start : starts) {
            if (store.hasTickFreshAt(market.id(), start, binding.feed().staleMs())) {
                continue;
            }
            if (tradePrices && store.oldestLiveAt(market.id()).orElse(Long.MAX_VALUE) <= start) {
                continue; // its history may not go between live ticks: asking would only cost weight
            }
            return true;
        }
        return false;
    }

    /**
     * Takes this market's turn for these starts: the first time, a new window once the last seed is over, or a
     * failed try that is due. A seed that is still waiting in line (or running) is never doubled.
     */
    private boolean claim(String marketId, String key, long now) {
        boolean[] mine = {false};
        attempts.compute(marketId, (id, last) -> {
            if (last == null) {
                mine[0] = true;
                return new Attempt(key, now, 0, State.QUEUED);
            }
            if (last.state() == State.QUEUED) {
                return last;
            }
            if (!last.key().equals(key)) {
                mine[0] = true;
                return new Attempt(key, now, 0, State.QUEUED);
            }
            if (last.state() == State.FAILED && now - last.atMs() >= retryDelayMs(last.failures())) {
                mine[0] = true;
                return new Attempt(key, now, last.failures(), State.QUEUED);
            }
            return last;
        });
        return mine[0];
    }

    private static long retryDelayMs(int failures) {
        long delay = RETRY_AFTER_MS;
        for (int i = 1; i < failures && delay < MAX_RETRY_MS; i++) {
            delay *= 2;
        }
        return Math.min(delay, MAX_RETRY_MS);
    }

    private static String key(long[] starts) {
        return Arrays.toString(starts);
    }

    /** Puts the seed in line behind the earlier ones of its source, spaced by what a seed costs (class comment). */
    private void queue(Market market, PriceSources.Binding binding) {
        long now = clock.getAsLong();
        long spacing = spacingMs(binding.source().historyWeight(seedSpanMs()));
        long[] delay = {0};
        nextSlotMs.compute(binding.source().name(), (source, next) -> {
            long start = Math.max(now, next == null ? now : next);
            delay[0] = start - now;
            return start + spacing;
        });
        try {
            scheduler.schedule(() -> seedQuietly(market), delay[0]);
        } catch (RejectedExecutionException e) {
            LOG.debugf("price history seed of %s dropped: shutting down", market.id());
        }
    }

    /** One seed every {@code 60 s / floor(budget / weight)}: the budget is a weight a minute, a seed starts when the minute can pay for it. */
    long spacingMs(long weight) {
        if (weight <= 0) {
            return OTHER_SPACING_MS;
        }
        long perMinute = Math.max(1L, config.hyperliquidSeedBudgetPerMinute() / weight);
        return (60_000L + perMinute - 1) / perMinute;
    }

    private void seedQuietly(Market market) {
        String key = "seed " + market.id();
        try {
            seedNow(market);
            // a seed that brought what the market lacked is over; one that did not (the source had nothing yet)
            // is tried again later, after a minute and then after longer, like one that failed
            markDone(market.id(), !stillMissing(market));
            warnings.success(key, "price history seed of " + market.id());
        } catch (Exception e) {
            markDone(market.id(), false);
            warnings.failure(key, "could not seed the price history of " + market.id()
                    + " (its prices start with the live polls)", e);
        }
    }

    /** After a seed: does the market still lack a price at the start of a running window that a seed could give it? */
    private boolean stillMissing(Market market) {
        PriceSources.Binding binding = sources.bindingFor(market).orElse(null);
        return binding != null && needsSeed(market, binding, runningStarts.apply(clock.getAsLong()));
    }

    private void markDone(String marketId, boolean ok) {
        long now = clock.getAsLong();
        attempts.compute(marketId, (id, last) -> last == null
                ? new Attempt(key(new long[0]), now, ok ? 0 : 1, ok ? State.DONE : State.FAILED)
                : new Attempt(last.key(), now, ok ? 0 : last.failures() + 1, ok ? State.DONE : State.FAILED));
    }

    /**
     * Asks the market's source for the last {@link #seedSpanMs()} and merges it (see the class
     * comment); returns the number of points added. Blocks on HTTP with the pipeline's timeouts. The start of
     * every running window is handed to the source as a boundary it is to price itself.
     */
    int seedNow(Market market) throws Exception {
        PriceSources.Binding binding = sources.bindingFor(market).orElse(null);
        if (binding == null) {
            return 0;
        }
        long to = clock.getAsLong();
        long from = to - seedSpanMs();
        long[] starts = runningStarts.apply(to);
        PriceSource source = binding.source();
        PriceSource.History history = source.historyOf(market, from, to, starts); // no lock is held
        List<PricePoint> usable = new ArrayList<>(history.points().size());
        for (PricePoint p : history.points()) {
            if (LiveMarketPrices.usable(market, p)) {
                usable.add(p);
            }
        }
        int added = store.seedHistory(market.id(), binding.feed().id(), usable);
        if (source.historyKind() == PriceSource.HistoryKind.SAME_AS_LIVE) {
            added += store.fillHoles(market.id(), usable, binding.feed().maxGapMs());
        }
        LOG.infof("price history seeded for %s: %d point(s) from the source for the last %d min, %d added",
                market.id(), history.points().size(), seedSpanMs() / 60_000L, added);
        return added;
    }

    private synchronized void runInBackground(Runnable task, long delayMs) {
        if (pool == null) {
            pool = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "price-history-seed");
                t.setDaemon(true);
                return t;
            });
            pool.setRemoveOnCancelPolicy(true);
        }
        pool.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    synchronized void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
