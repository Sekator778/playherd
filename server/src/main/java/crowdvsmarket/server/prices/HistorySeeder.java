package crowdvsmarket.server.prices;

import crowdvsmarket.server.markets.Market;
import io.quarkus.arc.Unremovable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * Gives a market its recent past the moment it is listed — and to every active market whose tick
 * ring is empty when its feed is polled (a start with no snapshots, a market whose rows were pruned,
 * one whose first seed failed) — so that the first running round has an opening price,
 * {@code soFar} works at once and the room's chart is not empty. A market listed in the middle of a
 * round window has no tick before the window's start — until the source's history is asked for it —
 * and its first round would otherwise wait, without a running path, until it settles by backfill.
 *
 * <p>The history is the source's own ({@link PriceSource#history}: Hyperliquid's 1-minute candles,
 * Polymarket's 60-second buckets, the synthetic walk) for the last
 * {@code max(15 minutes, the DAILY round)}, up to a day: a window of the longest series that is
 * running now started at most that long ago, and its opening price lies in that stretch. On the
 * stand — rounds of 3 and 15 minutes — that is 15 minutes. It is merged by
 * {@link TickStore#seedHistory}: only points older than every tick the ring already holds (the live
 * ticks are authoritative, so a poll that got in first is never covered), never over a tick, without
 * a snapshot row — and so it can never change a boundary price a settled round used (those lie at or
 * after the oldest tick).
 *
 * <p>The request runs on a background thread of its own ({@code price-history-seed}, one at a time,
 * so that a start-up with a dozen markets is a dozen sequential calls and not a burst against the
 * source's rate limit) and holds no lock while it waits for the source: not the engine's, not the
 * registry's, none that {@link MarketPrices#latest}, {@code series} or {@code soFar} take — the ring's
 * monitor is taken per point by the merge, for the length of one array insert, like a live poll.
 * A failure (the source down, a bad answer) is a warning, throttled to one a minute per market, and
 * never an error: the market simply gets its first prices from the live polls, the poll asks again
 * after {@link #RETRY_AFTER_MS} while its ring stays empty, and the backfill at settlement still
 * covers a hole.
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
    /** A market whose ring is still empty is seeded again, by the poll, this long after the last attempt. */
    static final long RETRY_AFTER_MS = 60_000L;

    private final PriceSources sources;
    private final TickStore store;
    private final PricesConfig config;
    private final LongSupplier clock;
    private final Consumer<Runnable> executor;
    private final boolean enabled;
    private final ThrottledLog warnings;
    private final ConcurrentHashMap<String, Long> lastAttemptMs = new ConcurrentHashMap<>();
    private ExecutorService pool; // created by the first seed, guarded by this

    @Inject
    public HistorySeeder(PriceSources sources, TickStore store, PricesConfig config) {
        this(sources, store, config, System::currentTimeMillis, null);
    }

    /** For tests: the given executor runs the seeds (a direct one makes them synchronous). */
    HistorySeeder(PriceSources sources, TickStore store, PricesConfig config, LongSupplier clock,
                  Consumer<Runnable> executor) {
        this(sources, store, config, clock, executor, new ThrottledLog(LOG, clock, 60_000L));
    }

    HistorySeeder(PriceSources sources, TickStore store, PricesConfig config, LongSupplier clock,
                  Consumer<Runnable> executor, ThrottledLog warnings) {
        this.sources = sources;
        this.store = store;
        this.config = config;
        this.clock = clock;
        this.executor = executor == null ? this::runInBackground : executor;
        this.enabled = true;
        this.warnings = warnings;
    }

    private HistorySeeder(boolean enabled) {
        this.sources = null;
        this.store = null;
        this.config = null;
        this.clock = null;
        this.executor = null;
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
        lastAttemptMs.put(market.id(), clock.getAsLong());
        queue(market);
    }

    /**
     * Seeds every given market whose ring holds no tick yet and that was not tried within the last
     * {@link #RETRY_AFTER_MS}; returns how many were queued. Cheap when nothing is empty: it is called
     * on every poll of a feed.
     */
    public int seedEmpty(Collection<Market> markets) {
        if (!enabled || !config.pipelineActive()) {
            return 0;
        }
        List<Market> due = new ArrayList<>();
        for (Market market : markets) {
            if (store.size(market.id()) == 0 && claim(market.id())) {
                due.add(market);
            }
        }
        due.forEach(this::queue);
        return due.size();
    }

    /** Takes this market's turn for an attempt, unless one was made within the retry period. */
    private boolean claim(String marketId) {
        long now = clock.getAsLong();
        boolean[] mine = {false};
        lastAttemptMs.compute(marketId, (id, last) -> {
            if (last == null || now - last >= RETRY_AFTER_MS) {
                mine[0] = true;
                return now;
            }
            return last;
        });
        return mine[0];
    }

    private void queue(Market market) {
        try {
            executor.accept(() -> seedQuietly(market));
        } catch (RejectedExecutionException e) {
            LOG.debugf("price history seed of %s dropped: shutting down", market.id());
        }
    }

    private void seedQuietly(Market market) {
        String key = "seed " + market.id();
        try {
            seedNow(market);
            warnings.success(key, "price history seed of " + market.id());
        } catch (Exception e) {
            warnings.failure(key, "could not seed the price history of " + market.id()
                    + " (its prices start with the live polls)", e);
        }
    }

    /**
     * Asks the market's source for the last {@link #seedSpanMs()} and merges it (see the class
     * comment); returns the number of points added. Blocks on HTTP with the pipeline's timeouts.
     */
    int seedNow(Market market) throws Exception {
        PriceSources.Binding binding = sources.bindingFor(market).orElse(null);
        if (binding == null) {
            return 0;
        }
        long to = clock.getAsLong();
        long from = to - seedSpanMs();
        List<PricePoint> history = binding.source().history(market, from, to); // no lock is held
        List<PricePoint> usable = new ArrayList<>(history.size());
        for (PricePoint p : history) {
            if (LiveMarketPrices.usable(market, p)) {
                usable.add(p);
            }
        }
        int added = store.seedHistory(market.id(), binding.feed().id(), usable);
        LOG.infof("price history seeded for %s: %d point(s) from the source for the last %d min, %d added",
                market.id(), history.size(), seedSpanMs() / 60_000L, added);
        return added;
    }

    private synchronized void runInBackground(Runnable task) {
        if (pool == null) {
            pool = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "price-history-seed");
                t.setDaemon(true);
                return t;
            });
        }
        pool.execute(task);
    }

    @PreDestroy
    synchronized void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
