package crowdvsmarket.server.bench;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.service.MarketViewService;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Reads per second while clients place bets, in this process, on a real Postgres (the architecture plan's F8 measurement,
 * before and after the read views): {@code clients} threads place bets one after the other (a closed loop, as in
 * {@link WriterThroughputBenchTest}) while {@code readers} threads read as fast as they can - a player's account, positions and
 * open bets, the round, the bet feed, the crowd overview, the crowd account. The prints start with {@code READ-BENCH}. A bench, not
 * a gate: it runs only with {@code -Dherd.bench.reads=true} ({@code -Dherd.bench.clients=64}, {@code -Dherd.bench.readers=8},
 * {@code -Dherd.bench.seconds=10}); it uses nothing that did not exist before the views, so the same file runs on both builds.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class ReadsUnderWriteBenchTest extends LiveGameTestBase {

    @Inject
    MarketViewService marketViews;

    @Test
    void readsPerSecondWhileTheClientsPlaceBets() throws Exception {
        assumeTrue(Boolean.getBoolean("herd.bench.reads"), "bench: run with -Dherd.bench.reads=true");
        int clients = Integer.getInteger("herd.bench.clients", 64);
        int readers = Integer.getInteger("herd.bench.readers", 8);
        int seconds = Integer.getInteger("herd.bench.seconds", 10);

        Market market = list("T_RUW", MarketKind.PERP, Category.CRYPTO, "RUW", 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        List<String> players = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            players.add(signUp("ruw" + i));
        }

        System.out.println("READ-BENCH warm-up");
        run(players, betting, market, Math.min(clients, 8), 2, 3);
        run(players, betting, market, 0, readers, seconds);   // the readers alone
        run(players, betting, market, clients, 0, seconds);   // the writers alone
        run(players, betting, market, clients, readers, seconds);
    }

    private void run(List<String> players, RoundWindow betting, Market market, int clients, int readers, int seconds)
            throws Exception {
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch ready = new CountDownLatch(clients + readers);
        CountDownLatch go = new CountDownLatch(1);
        int[] bets = new int[clients];
        long[][] latencies = new long[readers][];
        int[] reads = new int[readers];
        int[] failed = new int[readers];
        List<Thread> threads = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            int me = c;
            threads.add(new Thread(() -> {
                int k = 0;
                ready.countDown();
                await(go);
                while (!stop.get()) {
                    try {
                        gameService.placeBet(players.get(me),
                                bet(betting, Enums.Direction.UP, 5, (k++ % 2 == 0) ? "100.000" : "101.000"), betting.startMs() - 1);
                        bets[me]++;
                    } catch (RuntimeException e) {
                        // refused: not counted
                    }
                }
            }, "ruw-client-" + c));
        }
        for (int r = 0; r < readers; r++) {
            int me = r;
            threads.add(new Thread(() -> {
                long[] mine = new long[1 << 16];
                int n = 0;
                int k = me;
                ready.countDown();
                await(go);
                while (!stop.get()) {
                    String player = players.get(Math.floorMod(k++, players.size()));
                    long t0 = System.nanoTime();
                    try {
                        switch (k % 6) {
                            case 0 -> gameService.account(player);
                            case 1 -> marketViews.myPositions(player, System.currentTimeMillis());
                            case 2 -> gameService.round(betting.roundId(), player);
                            case 3 -> gameService.betFeed(betting.roundId(), null);
                            case 4 -> marketViews.crowdOverview();
                            default -> gameService.crowdAccount(market.id());
                        }
                        reads[me]++;
                    } catch (RuntimeException e) {
                        failed[me]++;
                    }
                    long dt = System.nanoTime() - t0;
                    if (n == mine.length) {
                        mine = Arrays.copyOf(mine, n * 2);
                    }
                    mine[n++] = dt;
                }
                latencies[me] = Arrays.copyOf(mine, n);
            }, "ruw-reader-" + r));
        }
        threads.forEach(Thread::start);
        ready.await();
        long start = System.nanoTime();
        go.countDown();
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        for (Thread t : threads) {
            t.join();
        }
        double elapsed = (System.nanoTime() - start) / 1e9;
        long[] all = Arrays.stream(latencies).filter(a -> a != null).flatMapToLong(Arrays::stream).sorted().toArray();
        int betTotal = Arrays.stream(bets).sum();
        int readTotal = Arrays.stream(reads).sum();
        System.out.printf("READ-BENCH clients=%d readers=%d seconds=%.1f bets=%d bets_per_s=%.0f reads=%d reads_per_s=%.0f "
                        + "failed_reads=%d read_p50_ms=%.3f read_p99_ms=%.3f read_max_ms=%.2f%n", clients, readers, elapsed,
                betTotal, betTotal / elapsed, readTotal, readTotal / elapsed, Arrays.stream(failed).sum(), ms(all, 0.50),
                ms(all, 0.99), all.length == 0 ? 0 : all[all.length - 1] / 1e6);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static double ms(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(q * sorted.length))] / 1e6;
    }
}
