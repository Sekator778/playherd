package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotService;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.scheduler.OraclePublisherService;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsWithBotsProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Listings and closes arrive from the catalogs' scheduler threads at any moment, and the registry tells the
 * game ({@code GameService.onListed/onClosed}) on the thread that made the call, where the game takes the
 * engine write lock. Three locks meet there — the registry's, the engine's, and the bot service's monitor —
 * and the order is one way only: {@code BotService} monitor, then engine lock, then (only for a reload, which
 * a rebuild does under the engine lock) the registry's write lock; the registry's lock is NEVER held while the
 * listener runs. These tests put a listing, a closing, a bot tick and a rebuild behind a rebuild, behind a
 * settlement, and in a free-for-all, and require every one of them to finish — and the game to come out of
 * it consistent: what is in memory is what a restart gives. A deadlock shows as a timeout with the stacks of
 * the threads that were in it.
 */
@QuarkusTest
@TestProfile(LiveMarketsWithBotsProfile.class)
class MarketLifecycleConcurrencyTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;
    private static final long WAIT_SECONDS = 25;

    @Inject
    BotService botService;
    /** Silenced: after every tick it reads the spied repository on a thread of its own, and a stubbing that thread
     *  hits in its midst is taken by the wrong thread (Mockito's UnfinishedStubbingException). */
    @InjectMock
    OraclePublisherService oraclePublisher;
    @InjectSpy
    EventStore eventStoreSpy;
    @InjectSpy
    OracleRecordRepository oracleRecords;

    private final List<ExecutorService> pools = new ArrayList<>();
    /** Set when a test saw its threads deadlock: they hold the engine lock for good, so every later test of
     *  this class would hang in its first call — they fail at once instead. */
    private static volatile boolean engineLockLost;

    @BeforeEach
    void notAfterADeadlock() {
        assertThat(engineLockLost).as("an earlier test of this class deadlocked: the engine lock is lost").isFalse();
    }

    @AfterEach
    void cleanUp() {
        Mockito.reset(eventStoreSpy, oracleRecords);
        pools.forEach(ExecutorService::shutdownNow);
        pools.clear();
    }

    private ExecutorService pool(int threads) {
        ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
            Thread t = new Thread(runnable, "lifecycle-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        pools.add(pool);
        return pool;
    }

    /** The stacks of the threads this test started, and of any that the JVM says are deadlocked. */
    private static String threadDump() {
        StringBuilder out = new StringBuilder();
        long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        out.append("deadlocked threads: ").append(deadlocked == null ? "none" : deadlocked.length).append('\n');
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            if (info.getThreadName().startsWith("lifecycle-")) {
                out.append(info);
            }
        }
        return out.toString();
    }

    private static <T> T finish(Future<T> future, String what) throws Exception {
        try {
            return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            engineLockLost = true;
            String dump = threadDump();
            System.err.println(what + " did not finish in " + WAIT_SECONDS + " s — a deadlock?\n" + dump);
            throw new AssertionError(what + " did not finish in " + WAIT_SECONDS + " s — a deadlock?\n" + dump, e);
        }
    }

    private Map<String, String> balances() {
        Map<String, String> out = new HashMap<>();
        for (String id : hub.ledger().playerIds()) {
            out.put(id, hub.ledger().balance(id).toMoney().toPlainString());
        }
        return out;
    }

    private Map<String, String> crowdCapitals() {
        Map<String, String> out = new HashMap<>();
        for (String id : hub.marketIds()) {
            out.put(id, hub.crowdAccount(id).capital().toPlainString());
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Behind a rebuild
    // ------------------------------------------------------------------------------------------

    @Test
    void aListingAndAClosingThatArriveDuringARebuildQueueBehindItAndAreServedAfterIt() throws Exception {
        Market doomed = list("T_LC_DOOMED", MarketKind.PERP, Category.CRYPTO, "DMD", 10, 0);
        prices.setPrice(doomed, "50.00");
        // The market's own series, seeded by the listing at the window containing the real "now" — never read off
        // BTC's cursor, which another test of this class may have left hours ahead of the clock (it ticks the
        // scheduler with a later one): the test then depended on the order its siblings ran in.
        RoundWindow betting = roundProgress.bettingWindow(doomed.id(), HOURLY);
        String bettor = signUp("lcdoomed");
        gameService.placeBet(bettor, bet(betting, Enums.Direction.UP, 5, "400.000"), betting.startMs() - 1);

        CountDownLatch inRebuild = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {                                   // the rebuild holds the engine lock while it reads the log
            inRebuild.countDown();
            assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            return inv.callRealMethod();
        }).when(eventStoreSpy).replayAfter(anyLong(), any());
        ExecutorService pool = pool(3);
        Future<?> rebuilding = pool.submit(() -> rebuild.rebuild());
        assertThat(inRebuild.await(10, TimeUnit.SECONDS)).isTrue();

        // the registry's journal writes (F6) take their turn on the core writer: they wait for the rebuild, which holds the engine
        // lock, and are written, and announced to the listener, after it
        Future<Market> listing = pool.submit(() -> list("T_LC_LATE", MarketKind.PERP, Category.CRYPTO, "LTE", 10, 0));
        Future<?> closing = pool.submit(() -> registry.close(doomed.id(), "closed during a rebuild",
                System.currentTimeMillis()));
        Thread.sleep(500);
        assertThat(listing.isDone()).as("the listing waits for the writer's turn").isFalse();
        assertThat(closing.isDone()).isFalse();
        assertThat(registry.byId("T_LC_LATE")).as("and is not written yet").isEmpty();

        release.countDown();
        finish(rebuilding, "the rebuild");
        finish(listing, "the listing");
        finish(closing, "the closing");

        assertThat(rebuild.isReady()).isTrue();
        assertThat(hub.findMarket("T_LC_LATE")).isPresent();
        assertThat(roundProgress.isSeeded("T_LC_LATE", HOURLY)).isTrue();
        assertThat(roundProgress.isSeeded("T_LC_LATE", Interval.DAILY)).isTrue();
        assertThat(roundProgress.isSeeded(doomed.id(), HOURLY)).isFalse();
        Rounds.RoundDto cancelled = roundResults.findRoundDto(betting.roundId()).orElseThrow();
        assertThat(cancelled).isInstanceOf(Rounds.RoundCancelled.class);
        assertThat(((Rounds.RoundCancelled) cancelled).cancellation().reason())
                .isEqualTo("market closed: closed during a rebuild");
        assertThat(gameService.account(bettor).balance()).isEqualTo("10000.000");
    }

    // ------------------------------------------------------------------------------------------
    // Behind a settlement, a bot tick and a rebuild
    // ------------------------------------------------------------------------------------------

    @Test
    void aListingAClosingABotTickAndARebuildQueueBehindASettlementAndAllFinish() throws Exception {
        // the settlement that will hold the engine lock
        Market settling = list("T_LC_SETTLE", MarketKind.PERP, Category.CRYPTO, "STL", 25, 1);
        prices.setPrice(settling, "2500.000");
        RoundWindow running = roundProgress.next(settling.id(), HOURLY);   // the window containing the real "now"
        RoundWindow betting = roundProgress.bettingWindow(settling.id(), HOURLY);
        String bettor = signUp("lcsettle");
        gameService.placeBet(bettor, bet(betting, Enums.Direction.DOWN, 10, "300.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());
        prices.script(settling, betting, "2500.000", "2400.000", "2400.000", "2510.000");
        // a market that will close meanwhile, with a bet; and a price for every market the bots may bet on
        Market doomed = list("T_LC_DOOMED2", MarketKind.PERP, Category.CRYPTO, "DM2", 10, 0);
        prices.setPrice(doomed, "50.00");
        alignTo(doomed, HOURLY, running);
        RoundWindow doomedBetting = roundProgress.bettingWindow(doomed.id(), HOURLY);
        gameService.placeBet(bettor, bet(doomedBetting, Enums.Direction.UP, 5, "400.000"), doomedBetting.startMs() - 1);
        // a market whose series sits at a betting window no bot plan has ever covered (the bot service plans
        // each window once), so that a tick at the instant before it starts finds every bot's bet due
        Market target = list("T_LC_BOTS", MarketKind.PERP, Category.CRYPTO, "BTS", 10, 0);
        prices.setPrice(target, "10.00");
        long farStart = running.endMs() + 100_000L * 60_000L + (System.nanoTime() % 1_000L) * 60_000L;
        roundProgress.seed(target.id(), HOURLY, new RoundWindow(target.id(), HOURLY, farStart - 60_000L, farStart));
        long botNow = farStart - 1;

        CountDownLatch inSettlement = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {                                   // inside the settlement transaction: the engine lock is held
            inSettlement.countDown();
            assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            return inv.callRealMethod();
        }).when(eventStoreSpy).append(any(Connection.class), any(GameEvent.RoundResolved.class), anyLong());
        ExecutorService pool = pool(6);
        Future<?> settlement = pool.submit(() -> gameService.resolveRound(betting, betting.endMs()));
        assertThat(inSettlement.await(10, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Thread> botThread = new AtomicReference<>();
        Future<?> botTick = pool.submit(() -> {             // takes the bot service's monitor, then waits for the engine lock
            botThread.set(Thread.currentThread());
            botService.tick(botNow);
        });
        await().atMost(Duration.ofSeconds(10)).until(
                () -> botThread.get() != null && botThread.get().getState() == Thread.State.WAITING);
        Future<Market> listing = pool.submit(() -> list("T_LC_LATE2", MarketKind.PERP, Category.CRYPTO, "LT2", 10, 0));
        Future<?> closing = pool.submit(() -> registry.close(doomed.id(), "closed during a settlement",
                System.currentTimeMillis()));
        Future<?> rebuilding = pool.submit(() -> rebuild.rebuild());   // later needs the bot service's monitor
        Thread.sleep(500); // the listing and the closing wait for the writer's turn, behind the settlement
        assertThat(registry.byId("T_LC_LATE2")).as("not written yet").isEmpty();
        assertThat(settlement.isDone() || botTick.isDone() || listing.isDone() || closing.isDone()
                || rebuilding.isDone()).as("everyone waits for the settlement").isFalse();

        release.countDown();
        finish(settlement, "the settlement");
        finish(botTick, "the bot tick");
        finish(listing, "the listing");
        finish(closing, "the closing");
        finish(rebuilding, "the rebuild");

        assertThat(rebuild.isReady()).isTrue();
        assertThat(roundResults.exists(betting.roundId())).as("the settlement went through, once").isTrue();
        assertThat(hub.findMarket("T_LC_LATE2")).isPresent();
        assertThat(roundProgress.isSeeded(doomed.id(), HOURLY)).isFalse();
        assertThat(roundResults.findRoundDto(doomedBetting.roundId()).orElseThrow())
                .isInstanceOf(Rounds.RoundCancelled.class);
        // what is in memory is what a restart gives
        Map<String, String> balances = balances();
        Map<String, String> capitals = crowdCapitals();
        rebuild.rebuild();
        assertThat(balances()).isEqualTo(balances);
        assertThat(crowdCapitals()).isEqualTo(capitals);
    }

    // ------------------------------------------------------------------------------------------
    // All of it at once
    // ------------------------------------------------------------------------------------------

    @Test
    void listingsClosesSettlementsRebuildsBotsBettorsAndReadersAllAtOnceFinishAndLeaveAConsistentGame()
            throws Exception {
        prices.setPrice(registry.require("BTC"), "50000.00");
        int actors = 7;
        ExecutorService pool = pool(actors);
        AtomicBoolean stop = new AtomicBoolean();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        AtomicInteger listed = new AtomicInteger();
        AtomicInteger rebuilds = new AtomicInteger();
        AtomicInteger bets = new AtomicInteger();
        long start = System.currentTimeMillis();
        List<String> openMarkets = new CopyOnWriteArrayList<>();
        List<Future<?>> futures = new ArrayList<>();

        futures.add(pool.submit(() -> loop(stop, unexpected, () -> {          // the catalog: lists, and closes the older ones
            int n = listed.incrementAndGet();
            Market m = list("T_LC_ST" + n, MarketKind.PERP, Category.CRYPTO, "S" + (n % 100), 10, 0);
            prices.setPrice(m, "100.00");
            openMarkets.add(m.id());
            if (openMarkets.size() > 3) {
                registry.close(openMarkets.remove(0), "stress close", System.currentTimeMillis());
            }
            Thread.sleep(15);
        })));
        AtomicLong virtualNow = new AtomicLong(start);
        futures.add(pool.submit(() -> loop(stop, unexpected, () -> {              // the clock: settlements and catch-up
            scheduler.tick(virtualNow.addAndGet(20_000L));
        })));
        futures.add(pool.submit(() -> loop(stop, unexpected, () -> {              // the bots, in real time
            botService.tick(System.currentTimeMillis());
            Thread.sleep(5);
        })));
        futures.add(pool.submit(() -> loop(stop, unexpected, () -> {              // restarts
            rebuild.rebuild();
            rebuilds.incrementAndGet();
        })));
        futures.add(pool.submit(() -> {                                          // a player who bets on whatever is open
            String player = signUp("lcstress");
            loop(stop, unexpected, () -> {
                for (String marketId : List.copyOf(openMarkets)) {
                    try {
                        RoundWindow window = roundProgress.bettingWindow(marketId, HOURLY);
                        gameService.placeBet(player, bet(window, Enums.Direction.UP, 2, "100.000"),
                                window.startMs() - 1);
                        bets.incrementAndGet();
                    } catch (IllegalStateException | ApiException expected) {
                        // the series was closed or not seeded in between: a normal outcome here
                    }
                }
                Thread.sleep(5);
            });
        }));
        for (int reader = 0; reader < 2; reader++) {
            futures.add(pool.submit(() -> loop(stop, unexpected, () -> {
                gameService.home(null);
                gameService.ratings(null);
                for (String marketId : List.copyOf(openMarkets)) {
                    try {
                        gameService.roundsPair(Enums.Interval.HOURLY, marketId, null);
                        gameService.crowdAccount(marketId);
                    } catch (ApiException expected) {
                        // closed in between
                    }
                }
            })));
        }

        Thread.sleep(4_000L);
        stop.set(true);
        for (Future<?> future : futures) {
            finish(future, "an actor");
        }

        assertThat(unexpected).as("nothing but the domain's own exceptions: %s", unexpected)
                .allSatisfy(t -> assertThat(t).isInstanceOf(ApiException.class));
        assertThat(listed.get()).isGreaterThan(5);
        assertThat(rebuilds.get()).isGreaterThan(3);
        assertThat(rebuild.isReady()).isTrue();
        // every closed market has no series and no pending bet; and memory is what a restart gives
        for (Market market : registry.all()) {
            if (!market.isActive()) {
                assertThat(roundProgress.isSeeded(market.id(), HOURLY)).as("closed: " + market.id()).isFalse();
            }
        }
        Map<String, String> balances = balances();
        Map<String, String> capitals = crowdCapitals();
        rebuild.rebuild();
        assertThat(balances()).as("balances after a restart").isEqualTo(balances);
        assertThat(crowdCapitals()).as("crowd accounts after a restart").isEqualTo(capitals);
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    /** Runs {@code step} until {@code stop}; whatever it throws is recorded, and the loop goes on. */
    private static void loop(AtomicBoolean stop, List<Throwable> unexpected, Step step) {
        while (!stop.get()) {
            try {
                step.run();
            } catch (Throwable t) {
                unexpected.add(t);
            }
        }
    }
}
