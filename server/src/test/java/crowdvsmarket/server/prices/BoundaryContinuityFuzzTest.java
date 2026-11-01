package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * D-40 under everything the pipeline does to a ring. Two windows that meet at a boundary read the
 * same price there — {@code close(A) == open(B)} for the neighbours A = [S, S+W] and B = [S+W, S+2W];
 * {@code close(D) == close(B)} for D = [S−W, S+2W], a longer window that ends where B ends (the other
 * series of a market: the scheduler settles the DAILY series before the HOURLY one in every pass, and
 * their boundaries coincide); {@code open(E) == open(A)} for E = [S, S+3W], which starts where A does —
 * whenever both settle, whatever the outages in the ticks, whatever the source's history looks like
 * (nothing, a failure, sparse or dense points, points exactly at and one millisecond before the
 * boundaries), whether the price moves or stays flat (the quiet-asset carry), whatever the delay before
 * each window is settled, and in <b>every</b> order of settling. A thousand seeded scenarios times the
 * 24 orders of four windows, deterministic.
 */
class BoundaryContinuityFuzzTest {

    private static final long W = 180_000L;
    private static final long S = 1_790_883_600_000L;

    private static final RoundWindow A = new RoundWindow("BTC", Interval.HOURLY, S, S + W);
    private static final RoundWindow B = new RoundWindow("BTC", Interval.HOURLY, S + W, S + 2 * W);
    private static final RoundWindow D = new RoundWindow("BTC", Interval.DAILY, S - W, S + 2 * W);
    private static final RoundWindow E = new RoundWindow("BTC", Interval.DAILY, S, S + 3 * W);
    private static final List<RoundWindow> WINDOWS = List.of(A, B, D, E);
    private static final List<String> NAMES = List.of("A", "B", "D", "E");

    /** A source whose history is a fixed function of the scenario's seed, so a retry gets the same answer. */
    private static final class FuzzSource implements PriceSource {

        final long seed;
        final int mode;
        final long stepMs;
        final double dropRate;

        FuzzSource(long seed, Random random) {
            this.seed = seed;
            this.mode = random.nextInt(5); // 0 nothing, 1 failure, 2 grid, 3 grid + boundary points, 4 grid
            this.stepMs = new long[] {5_000L, 10_000L, 20_000L, 60_000L}[random.nextInt(4)];
            this.dropRate = random.nextDouble() * 0.5;
        }

        @Override
        public String name() {
            return "hyperliquid";
        }

        @Override
        public List<Feed> feeds() {
            return List.of(new Feed("hyperliquid", 15_000, 30_000, m -> true));
        }

        @Override
        public Map<String, Quote> poll(Feed feed, List<Market> markets) {
            throw new UnsupportedOperationException();
        }

        /** The source's price at {@code atMs}: a function of time and seed only. */
        private BigDecimal priceAt(long atMs) {
            long h = Long.rotateLeft((atMs / 1000) * 0x9E3779B97F4A7C15L ^ seed * 0xC2B2AE3D27D4EB4FL, 17);
            return BigDecimal.valueOf(95 + Math.floorMod(h, 1000L) / 100.0);
        }

        @Override
        public List<PricePoint> history(Market market, long fromMs, long toMs) throws IOException {
            if (mode == 1) {
                throw new IOException("source is down");
            }
            List<PricePoint> out = new ArrayList<>();
            if (mode == 0) {
                return out;
            }
            for (long at = (fromMs + stepMs - 1) / stepMs * stepMs; at <= toMs; at += stepMs) {
                long h = Long.rotateLeft(at * 0xBF58476D1CE4E5B9L ^ seed, 29);
                if (Math.floorMod(h, 1000L) / 1000.0 >= dropRate) {
                    out.add(new PricePoint(at, priceAt(at)));
                }
            }
            if (mode == 3) {
                for (long boundary : new long[] {S - W, S, S + W, S + 2 * W, S + 3 * W}) {
                    for (long at : new long[] {boundary - 1, boundary}) {
                        if (at >= fromMs && at <= toMs) {
                            out.add(new PricePoint(at, priceAt(at)));
                        }
                    }
                }
            }
            out.sort((a, b) -> Long.compare(a.atMs(), b.atMs()));
            return out;
        }
    }

    private record Scenario(long seed, List<long[]> outages, double flatRate, long[] delays) {
    }

    private static Scenario scenario(long seed) {
        Random random = new Random(seed);
        List<long[]> outages = new ArrayList<>();
        int count = random.nextInt(4);
        for (int i = 0; i < count; i++) {
            long from = S - W - 30_000L + (long) (random.nextDouble() * (4 * W + 90_000L));
            long length = 5_000L + (long) (random.nextDouble() * 195_000L);
            outages.add(new long[] {from, from + length});
        }
        if (random.nextBoolean()) {
            // the dangerous one: the feed dies within the freshness limit before a boundary and stays dead for
            // longer than the maximum gap, so the last tick is still fresh at the boundary (no hole for the round
            // that ends there) but another window that spans the boundary has a hole that reaches back over it
            long boundary = S + W * random.nextInt(3);
            long from = boundary - (long) (random.nextDouble() * 14_000L);
            outages.add(new long[] {from, from + 30_000L + (long) (random.nextDouble() * 90_000L)});
        }
        double flatRate = new double[] {0.0, 0.5, 1.0}[random.nextInt(3)];
        long[] choices = {1_000L, 20_000L, 70_000L, 200_000L};
        long[] delays = new long[WINDOWS.size()];
        for (int i = 0; i < delays.length; i++) {
            delays[i] = choices[random.nextInt(choices.length)];
        }
        return new Scenario(seed, outages, flatRate, delays);
    }

    /** Ticks every 3 s from before the first window to after the last, minus the outages. */
    private static void fill(TickStore store, Market market, Scenario sc) {
        Random random = new Random(sc.seed * 31 + 7);
        double price = 100;
        for (long at = S - W - 60_000L; at <= S + 3 * W + 120_000L; at += 3_000L) {
            if (random.nextDouble() >= sc.flatRate()) {
                price = Math.max(1, price + random.nextInt(21) - 10);
            }
            boolean inOutage = false;
            for (long[] outage : sc.outages()) {
                inOutage |= at >= outage[0] && at < outage[1];
            }
            if (!inOutage) {
                store.append("hyperliquid", at, Map.of(market.id(), BigDecimal.valueOf(price)));
            }
        }
    }

    /** Settles a window the way the scheduler does: retry every 61 s (the source is asked once a minute). */
    private static RoundPriceData settle(LiveMarketPrices prices, AtomicLong now, Market market, RoundWindow window,
                                         long delay) {
        now.set(Math.max(now.get(), window.endMs() + delay)); // time never goes backwards
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return prices.resolve(market, window);
            } catch (PriceUnavailableException e) {
                now.addAndGet(61_000L);
            }
        }
        return null;
    }

    private static void checkShape(RoundPriceData data, String what) {
        assertThat(data.path()).as(what).isNotEmpty().hasSizeLessThanOrEqualTo(RoundPath.MAX_CANDLES);
        assertThat(data.path().get(0).open()).as(what + " first candle opens at the open").isEqualByComparingTo(data.open());
        assertThat(data.path().get(data.path().size() - 1).close()).as(what + " last candle closes at the close")
                .isEqualByComparingTo(data.close());
        for (int i = 1; i < data.path().size(); i++) {
            Candle previous = data.path().get(i - 1);
            assertThat(data.path().get(i).open()).as(what + " candle " + i + " continues the one before")
                    .isEqualByComparingTo(previous.close());
        }
    }

    private static void permutations(List<Integer> prefix, List<Integer> rest, Consumer<List<Integer>> sink) {
        if (rest.isEmpty()) {
            sink.accept(prefix);
            return;
        }
        for (int i = 0; i < rest.size(); i++) {
            List<Integer> nextPrefix = new ArrayList<>(prefix);
            nextPrefix.add(rest.get(i));
            List<Integer> nextRest = new ArrayList<>(rest);
            nextRest.remove(i);
            permutations(nextPrefix, nextRest, sink);
        }
    }

    /** No snapshot table: the fuzz only needs the ring. */
    private static final SnapshotStore NO_SNAPSHOTS = new SnapshotStore() {
        @Override
        public void save(String source, long atMs, Map<String, BigDecimal> prices) {
        }

        @Override
        public int replaySince(long fromMs, Consumer<Snapshot> sink) {
            return 0;
        }

        @Override
        public int pruneBefore(long cutoffMs) {
            return 0;
        }
    };

    private static final int SEEDS = 1_000;

    @Test
    void windowsThatMeetAtABoundaryReadTheSamePriceThereInEveryOrderOfSettling() {
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(LiveMarketPrices.class.getName());
        java.util.logging.Level before = log.getLevel();
        log.setLevel(java.util.logging.Level.OFF); // tens of thousands of backfills: the log is not what is looked at
        try {
            everyOrder();
        } finally {
            log.setLevel(before);
        }
    }

    private void everyOrder() {
        Market market = TestMarkets.btc();
        List<List<Integer>> orders = new ArrayList<>();
        permutations(List.of(), List.of(0, 1, 2, 3), orders::add);
        assertThat(orders).hasSize(24);
        int allSettled = 0;
        int someUnavailable = 0;
        int checkedAB = 0;
        int checkedDB = 0;
        int checkedEA = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            Scenario sc = scenario(seed);
            for (List<Integer> order : orders) {
                AtomicLong now = new AtomicLong(S);
                TickStore store = new TickStore(NO_SNAPSHOTS, PricesConfig.defaults().retentionMs(), now::get);
                fill(store, market, sc);
                FuzzSource source = new FuzzSource(seed, new Random(seed * 17 + 3));
                LiveMarketPrices prices = new LiveMarketPrices(store, new PriceSources(List.of(source)),
                        PricesConfig.defaults(), now::get);

                Map<String, RoundPriceData> settled = new LinkedHashMap<>();
                for (int index : order) {
                    RoundPriceData data = settle(prices, now, market, WINDOWS.get(index), sc.delays()[index]);
                    if (data != null) {
                        settled.put(NAMES.get(index), data);
                    }
                }
                String what = "seed " + seed + " order " + order.stream().map(NAMES::get).toList() + " mode "
                        + source.mode + " outages " + sc.outages().stream()
                        .map(o -> (o[0] - S) / 1000 + ".." + (o[1] - S) / 1000).toList() + " flat " + sc.flatRate();
                settled.forEach((name, data) -> checkShape(data, what + " " + name));
                RoundPriceData a = settled.get("A");
                RoundPriceData b = settled.get("B");
                RoundPriceData d = settled.get("D");
                RoundPriceData e = settled.get("E");
                if (a != null && b != null) {
                    assertThat(b.open()).as(what + ": open(B) == close(A)").isEqualByComparingTo(a.close());
                    checkedAB++;
                }
                if (d != null && b != null) {
                    assertThat(d.close()).as(what + ": close(D) == close(B)").isEqualByComparingTo(b.close());
                    checkedDB++;
                }
                if (e != null && a != null) {
                    assertThat(e.open()).as(what + ": open(E) == open(A)").isEqualByComparingTo(a.open());
                    checkedEA++;
                }
                if (settled.size() == WINDOWS.size()) {
                    allSettled++;
                } else {
                    someUnavailable++;
                }
            }
        }
        // the scenarios are neither all trivially easy nor all hopeless: every property was really exercised
        assertThat(checkedAB).as("runs that compared open(B) with close(A)").isGreaterThan(5_000);
        assertThat(checkedDB).as("runs that compared close(D) with close(B)").isGreaterThan(5_000);
        assertThat(checkedEA).as("runs that compared open(E) with open(A)").isGreaterThan(5_000);
        assertThat(allSettled).as("runs in which all four windows settled").isGreaterThan(5_000);
        assertThat(someUnavailable).as("runs in which a window stayed unavailable").isGreaterThan(1_000);
    }
}
