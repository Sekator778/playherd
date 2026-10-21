package crowdvsmarket.server.bench;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.JournalAsyncProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Bets per second through {@code GameService.placeBet}, in this process, on a real Postgres (the test database of the
 * server tests), for 1, 8 and 64 clients that each place bets one after the other (a closed loop: a client sends its next bet
 * when the answer to the last has come). The architecture plan's F6 measurement, before and after the core writer: the same
 * test runs on both builds. A bench, not a gate: it runs only with {@code -Dherd.bench.writer=true}
 * ({@code -Dherd.bench.clients=1,8,64}, {@code -Dherd.bench.seconds=10}). A closed loop shows the throughput at that number of
 * clients; it does not show where the server stops accepting (the open model of {@code tools/bench} does that).
 *
 * <p>Every client has its own player and replaces its bet on the same round again and again (a replacement is an event like
 * any bet), so the load is the same number of journal commits however long it runs. The prints start with
 * {@code WRITER-BENCH}.
 */
@QuarkusTest
@TestProfile(JournalAsyncProfile.class)
class WriterThroughputBenchTest extends LiveGameTestBase {

    @Test
    void betsPerSecondAtOneEightAndSixtyFourClients() throws Exception {
        assumeTrue(Boolean.getBoolean("herd.bench.writer"), "bench: run with -Dherd.bench.writer=true");
        int[] clientCounts = Arrays.stream(System.getProperty("herd.bench.clients", "1,8,64").split(","))
                .mapToInt(s -> Integer.parseInt(s.trim())).toArray();
        int seconds = Integer.getInteger("herd.bench.seconds", 10);
        int maxClients = Arrays.stream(clientCounts).max().orElse(1);

        Market market = list("T_WTB", MarketKind.PERP, Category.CRYPTO, "WTB", 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        List<String> players = new ArrayList<>();
        for (int i = 0; i < maxClients; i++) {
            players.add(signUp("wtb" + i));
        }

        System.out.println("WRITER-BENCH warm-up");
        run(players, 8, 3, market, betting);
        for (int clients : clientCounts) {
            run(players, clients, seconds, market, betting);
        }
    }

    private void run(List<String> players, int clients, int seconds, Market market, RoundWindow betting) throws Exception {
        long[][] latencies = new long[clients][];
        int[] accepted = new int[clients];
        int[] refused = new int[clients];
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch ready = new CountDownLatch(clients);
        CountDownLatch go = new CountDownLatch(1);
        Thread[] threads = new Thread[clients];
        for (int c = 0; c < clients; c++) {
            int me = c;
            threads[c] = new Thread(() -> {
                long[] mine = new long[1 << 16];
                int n = 0;
                int k = 0;
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                while (!stop.get()) {
                    String stake = (k++ % 2 == 0) ? "100.000" : "101.000";
                    long t0 = System.nanoTime();
                    try {
                        gameService.placeBet(players.get(me),
                                bet(betting, Enums.Direction.UP, 5, stake), betting.startMs() - 1);
                        accepted[me]++;
                    } catch (RuntimeException e) {
                        refused[me]++;
                    }
                    long dt = System.nanoTime() - t0;
                    if (n == mine.length) {
                        mine = Arrays.copyOf(mine, n * 2);
                    }
                    mine[n++] = dt;
                }
                latencies[me] = Arrays.copyOf(mine, n);
            }, "wtb-client-" + c);
            threads[c].start();
        }
        ready.await();
        long start = System.nanoTime();
        go.countDown();
        Thread.sleep(seconds * 1000L);
        stop.set(true);
        for (Thread t : threads) {
            t.join();
        }
        double elapsed = (System.nanoTime() - start) / 1e9;
        long[] all = Arrays.stream(latencies).flatMapToLong(Arrays::stream).sorted().toArray();
        int ok = Arrays.stream(accepted).sum();
        int no = Arrays.stream(refused).sum();
        System.out.printf("WRITER-BENCH clients=%d seconds=%.1f accepted=%d refused=%d bets_per_s=%.0f p50_ms=%.2f p99_ms=%.2f "
                        + "max_ms=%.2f%n", clients, elapsed, ok, no, ok / elapsed, ms(all, 0.50), ms(all, 0.99),
                all.length == 0 ? 0 : all[all.length - 1] / 1e6);
    }

    private static double ms(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[(int) Math.min(sorted.length - 1, Math.floor(q * sorted.length))] / 1e6;
    }
}
