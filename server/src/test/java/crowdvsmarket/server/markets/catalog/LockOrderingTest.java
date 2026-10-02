package crowdvsmarket.server.markets.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketListener;
import crowdvsmarket.server.markets.MarketStatus;
import crowdvsmarket.server.prices.FakeApis;
import crowdvsmarket.server.prices.Fixtures;
import crowdvsmarket.server.prices.HistorySeeder;
import crowdvsmarket.server.prices.HyperliquidLiveState;
import crowdvsmarket.server.prices.LiveMarketPrices;
import crowdvsmarket.server.prices.PipelineTestSupport;
import crowdvsmarket.server.prices.PolymarketLiveState;
import crowdvsmarket.server.prices.PriceFeedService;
import crowdvsmarket.server.prices.PricePoint;
import crowdvsmarket.server.prices.PriceSource;
import crowdvsmarket.server.prices.PricesConfig;
import crowdvsmarket.server.prices.TickStore;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * G4, lock ordering. The market registry runs its listener on the thread that calls {@code list()}
 * or {@code close()}, and the game service's listener takes the engine's WRITE lock — while the game
 * service itself calls {@code latest()}, {@code soFar()} and {@code series()} under the engine's READ
 * lock. So no catalog may call {@code list()} / {@code close()} while holding anything that those
 * three (or the tick store behind them) take, and the poller, which feeds the same store, must never
 * wait for the engine lock either.
 *
 * <p>The engine lock is modelled by a {@link ReentrantReadWriteLock} taken by a fake listener in
 * {@code onListed} / {@code onClosed}; "the game service" is the test thread, inside a read for as
 * long as the test wants. A catalog that is parked in the listener (waiting for the write lock behind
 * that read) must not stop a poll, a reader, or a history merge — if it did hold a monitor of the
 * store or of its own that they need, one of the calls below would time out. The catalogs also say
 * so in a comment at every call site.
 */
class LockOrderingTest {

    private static final long NOW = Fixtures.NOW;

    /** The game service's listener: the engine's write lock for as long as it takes (here: a moment). */
    private static final class EngineListener implements MarketListener {

        final ReentrantReadWriteLock engine = new ReentrantReadWriteLock();
        final AtomicInteger listed = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public void onListed(Market market) {
            engine.writeLock().lock();
            try {
                listed.incrementAndGet();
            } finally {
                engine.writeLock().unlock();
            }
        }

        @Override
        public void onClosed(Market market, String reason) {
            engine.writeLock().lock();
            try {
                closed.incrementAndGet();
            } finally {
                engine.writeLock().unlock();
            }
        }
    }

    /** Hyperliquid-shaped and scripted: every poll prices every market at 84000, history is a point every 10 s. */
    private static final class ScriptedSource implements PriceSource {

        final AtomicInteger polls = new AtomicInteger();
        final AtomicInteger histories = new AtomicInteger();

        @Override
        public String name() {
            return "hyperliquid";
        }

        @Override
        public List<Feed> feeds() {
            return List.of(new Feed("hyperliquid", 15_000, 30_000, m -> "hyperliquid".equals(m.source())));
        }

        @Override
        public Map<String, Quote> poll(Feed feed, List<Market> markets) {
            polls.incrementAndGet();
            Map<String, Quote> out = new java.util.LinkedHashMap<>();
            for (Market market : markets) {
                out.put(market.id(), new Quote(new BigDecimal("84000"), null));
            }
            return out;
        }

        @Override
        public List<PricePoint> history(Market market, long fromMs, long toMs) {
            histories.incrementAndGet();
            List<PricePoint> out = new ArrayList<>();
            for (long at = fromMs; at <= toMs && out.size() < 200; at += 10_000L) {
                out.add(new PricePoint(at, new BigDecimal("84000")));
            }
            return out;
        }
    }

    private final AtomicLong now = new AtomicLong(NOW);
    private final EngineListener listener = new EngineListener();
    private FakeMarketRegistry registry;
    private TickStore store;
    private ScriptedSource source;
    private PriceFeedService feeds;
    private LiveMarketPrices prices;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        registry = new FakeMarketRegistry();
        registry.setListener(listener);
        store = Fixtures.tickStore(now::get);
        source = new ScriptedSource();
        feeds = PipelineTestSupport.feedService(List.of(source), store, registry, now::get);
        prices = PipelineTestSupport.livePrices(store, List.of(source), now::get);
        pool = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() {
        // never leave the model's lock held, whatever the test did
        pool.shutdownNow();
    }

    private Market btc() {
        return registry.byId("BTC").orElseThrow();
    }

    /** What the game service does under the engine's read lock, for every active market. */
    private void readLikeTheGameService() {
        RoundWindow window = new RoundWindow("BTC", Interval.HOURLY, NOW - 60_000L, NOW + 120_000L);
        for (Market market : registry.active()) {
            prices.latest(market.id());
            prices.series(market.id(), NOW - 900_000L, NOW + 60_000L);
            if (market.id().equals("BTC")) {
                prices.soFar(btc(), window, now.get());
            }
        }
    }

    /** The poller's work for a little while: a tick every 3 s of the clock. */
    private void pollABit(int times) {
        for (int i = 0; i < times; i++) {
            now.addAndGet(3_000L);
            PipelineTestSupport.poll(feeds, "hyperliquid");
        }
    }

    /** Three successful answers in a row, the first a minute ago and the newest three seconds ago: enough to close. */
    private static void reportDelistedThrice(HyperliquidLiveState state, String id) {
        state.reportDelisted(id, NOW - 60_000L);
        state.reportDelisted(id, NOW - 30_000L);
        state.reportDelisted(id, NOW - 3_000L);
    }

    private void waitUntilTheWriterIsParked() {
        await().atMost(Duration.ofSeconds(10)).until(() -> listener.engine.hasQueuedThreads());
    }

    // ------------------------------------------------------------------------------------ tests

    @Test
    void aClosingParkedInTheListenerDoesNotStopThePollsTheReadersOrAHistoryMerge() throws Exception {
        HyperliquidLiveState state = new HyperliquidLiveState();
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> body.has("dex") ? Fixtures.json("hyperliquid/meta_and_asset_ctxs_xyz.json")
                : Fixtures.json("hyperliquid/meta_and_asset_ctxs_main.json");
        new HyperliquidCatalog(registry, CatalogConfig.defaults(), () -> api, state, HistorySeeder.disabled(),
                () -> NOW).sync(NOW);
        reportDelistedThrice(state, "ETH");
        HyperliquidCatalog catalog = new HyperliquidCatalog(registry, CatalogConfig.defaults(), () -> api, state,
                HistorySeeder.disabled(), () -> NOW);

        Future<Integer> watch;
        listener.engine.readLock().lock(); // the game service is inside a read
        try {
            watch = pool.submit(() -> catalog.watch(NOW));
            waitUntilTheWriterIsParked(); // the catalog is now inside registry.close(), in the listener

            pool.submit(() -> pollABit(50)).get(10, TimeUnit.SECONDS); // the poller does not wait for the engine
            readLikeTheGameService(); // and the reader, holding the read lock, reads
            // a resolve (outside the engine lock, as the scheduler does it) and the running path
            prices.resolve(btc(), new RoundWindow("BTC", Interval.HOURLY, NOW + 3_000L, NOW + 60_000L));
            assertThat(prices.soFar(btc(), new RoundWindow("BTC", Interval.HOURLY, NOW + 60_000L, NOW + 240_000L),
                    now.get())).isPresent();

            assertThat(watch.isDone()).isFalse(); // still parked on the engine lock
            assertThat(listener.closed.get()).isZero();
        } finally {
            listener.engine.readLock().unlock();
        }

        assertThat(watch.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(listener.closed.get()).isEqualTo(1);
        assertThat(registry.byId("ETH").orElseThrow().isActive()).isFalse();
    }

    @Test
    void aListingParkedInTheListenerDoesNotStopThePollsOrTheReaders() throws Exception {
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> Fixtures.json("hyperliquid/meta_and_asset_ctxs_main.json");
        HyperliquidCatalog catalog = new HyperliquidCatalog(registry,
                new CatalogConfig(true, false, List.of("BTC", "ETH"), List.of(), List.of(), List.of(), true, 3, 3, 10, true),
                () -> api, new HyperliquidLiveState(), HistorySeeder.disabled(), () -> NOW);

        listener.engine.readLock().lock();
        try {
            Future<Integer> sync = pool.submit(() -> catalog.sync(NOW));
            waitUntilTheWriterIsParked(); // inside registry.list(), in the listener

            pool.submit(() -> pollABit(50)).get(10, TimeUnit.SECONDS);
            readLikeTheGameService();

            assertThat(sync.isDone()).isFalse();
        } finally {
            listener.engine.readLock().unlock();
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> listener.listed.get() == 1);
        assertThat(registry.byId("ETH")).isPresent();
    }

    @Test
    void aPolymarketClosingParkedInTheListenerDoesNotStopThePollsOrTheReaders() throws Exception {
        PolymarketLiveState state = new PolymarketLiveState();
        TickStore ticks = store;
        registry.add(new Market("PM1", MarketKind.ODDS, Category.POLITICS, "PM", "Will it?", "Yes", null, "polymarket",
                "tok1", null, 10, 4, 9, MarketStatus.ACTIVE, 0L, null, null, NOW + 60_000L)); // about to end
        PolymarketCatalog catalog = new PolymarketCatalog(registry, CatalogConfig.defaults(), PricesConfig.defaults(),
                () -> new FakeApis.Gamma(), () -> new FakeApis.Clob(), state, ticks, () -> NOW);

        listener.engine.readLock().lock();
        try {
            Future<Integer> watch = pool.submit(() -> catalog.watch(NOW));
            waitUntilTheWriterIsParked();

            pool.submit(() -> pollABit(50)).get(10, TimeUnit.SECONDS);
            readLikeTheGameService();
            assertThat(watch.isDone()).isFalse();
        } finally {
            listener.engine.readLock().unlock();
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> listener.closed.get() == 1);
        assertThat(registry.closeReasons).containsKey("PM1");
    }

    @Test
    void aSeedWaitingOnTheSourceAndACatalogWaitingOnTheEngineAndAReaderAllFinish() throws Exception {
        // everything at once, for a second: pollers, resolvers, readers under the read lock, catalogs that list
        // and close (taking the write lock in the listener), a seeder merging history — no deadlock, all progress
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> Fixtures.json("hyperliquid/meta_and_asset_ctxs_main.json");
        HyperliquidLiveState state = new HyperliquidLiveState();
        HistorySeeder seeder = PipelineTestSupport.seeder(List.of(source), store, now::get);
        List<Future<?>> work = new ArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger resolves = new AtomicInteger();
        AtomicInteger catalogRuns = new AtomicInteger();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_500);

        ExecutorService threads = Executors.newFixedThreadPool(4);
        try {
            work.add(threads.submit(() -> {
                while (System.nanoTime() < deadline) {
                    pollABit(1);
                }
            }));
            work.add(threads.submit(() -> { // the game service: reads under the read lock, one lock at a time
                while (System.nanoTime() < deadline) {
                    listener.engine.readLock().lock();
                    try {
                        readLikeTheGameService();
                        reads.incrementAndGet();
                    } finally {
                        listener.engine.readLock().unlock();
                    }
                }
            }));
            work.add(threads.submit(() -> { // the scheduler: resolves outside the lock, windows with holes (history)
                while (System.nanoTime() < deadline) {
                    try {
                        prices.resolve(btc(), new RoundWindow("BTC", Interval.HOURLY, NOW, NOW + 180_000L));
                    } catch (RuntimeException expected) {
                        // a hole nobody fills: PriceUnavailable, the scheduler retries — not what is tested here
                    }
                    resolves.incrementAndGet();
                }
            }));
            work.add(threads.submit(() -> { // the catalog: lists and closes under the registry, seeding what it lists
                HyperliquidCatalog catalog = new HyperliquidCatalog(registry, new CatalogConfig(true, false,
                        List.of("BTC", "ETH", "SOL", "DOGE"), List.of(), List.of(), List.of(), true, 3, 3, 10, true),
                        () -> api, state, seeder, () -> NOW);
                boolean reported = false;
                while (System.nanoTime() < deadline) {
                    catalog.sync(NOW);
                    if (!reported && listener.listed.get() == 3) {
                        reportDelistedThrice(state, "DOGE"); // once everything is listed
                        reported = true;
                    }
                    catalog.watch(NOW);
                    catalogRuns.incrementAndGet();
                }
            }));
            for (Future<?> f : work) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("a thread did not finish — deadlock? " + deadlocks(), e);
                }
            }
        } finally {
            threads.shutdownNow();
        }

        assertThat(source.polls.get()).isPositive();
        assertThat(reads.get()).isPositive();
        assertThat(resolves.get()).isPositive();
        assertThat(catalogRuns.get()).isPositive();
        assertThat(listener.listed.get()).isEqualTo(3); // ETH, SOL, DOGE
        assertThat(listener.closed.get()).isEqualTo(1); // DOGE, delisted
        assertThat(source.histories.get()).isPositive();
    }

    private static String deadlocks() {
        long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        return ids == null ? "none found by the JVM" : java.util.Arrays.toString(ids);
    }
}
