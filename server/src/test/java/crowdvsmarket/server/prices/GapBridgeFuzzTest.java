package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
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
 * D-40 and the review's rule for a Hyperliquid-like source whose history is candles — the real
 * {@link HyperliquidPriceSource} over a scripted {@link CandleTape}, with the bridge on. Four windows that meet
 * at boundaries (A = [S, S+W], B = [S+W, S+2W], D = [S−W, S+2W], E = [S, S+3W]), random outages of the feed
 * of 5 to 200 s, candles that traded in every minute or in most of them (one in ten missing), candles that sit 1%
 * off the marks, a quiet flat asset that has not traded for an hour and whose last trade is 5% off, any delay
 * before each window is settled and every order of settling (24 of them, 300 scenarios):
 * <ul>
 *   <li>{@code close(A) == open(B)}, {@code close(D) == close(B)}, {@code open(E) == open(A)} whenever both
 *       settle — the bridge's points are merged into a ring that also holds the pins of the windows already
 *       settled, and nothing may move a price a settled round used;</li>
 *   <li>the shape of every path, 30 candles at most, continuous from open to close;</li>
 *   <li>a price that disagrees with the marks never lands in the ring: no tick, bridged or carried, is more than
 *       50 bp from the mark of its own time — the 1% shift and the 5% old trade never get in;</li>
 *   <li>the bridge is not decoration: with it on, more windows settle than with it off, over the same scenarios.</li>
 * </ul>
 */
class GapBridgeFuzzTest {

    private static final long W = 180_000L;
    private static final long S = 1_790_883_600_000L;
    private static final Market BTC = TestMarkets.btc();

    private static final RoundWindow A = new RoundWindow("BTC", Interval.HOURLY, S, S + W);
    private static final RoundWindow B = new RoundWindow("BTC", Interval.HOURLY, S + W, S + 2 * W);
    private static final RoundWindow D = new RoundWindow("BTC", Interval.DAILY, S - W, S + 2 * W);
    private static final RoundWindow E = new RoundWindow("BTC", Interval.DAILY, S, S + 3 * W);
    private static final List<RoundWindow> WINDOWS = List.of(A, B, D, E);
    private static final List<String> NAMES = List.of("A", "B", "D", "E");

    private static final int SEEDS = 300;

    /** What the market is like in one scenario. */
    private record Scenario(long seed, String kind, List<long[]> outages, long[] delays, CandleTape tape) {
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
        if (random.nextBoolean()) { // the dangerous one: the feed dies just before a boundary and stays dead a while
            long boundary = S + W * random.nextInt(3);
            long from = boundary - (long) (random.nextDouble() * 14_000L);
            outages.add(new long[] {from, from + 30_000L + (long) (random.nextDouble() * 90_000L)});
        }
        long[] choices = {1_000L, 20_000L, 70_000L, 200_000L};
        long[] delays = new long[WINDOWS.size()];
        for (int i = 0; i < delays.length; i++) {
            delays[i] = choices[random.nextInt(choices.length)];
        }
        String kind = new String[] {"liquid", "liquid", "gaps", "gaps", "shifted", "quiet"}[random.nextInt(6)];
        CandleTape tape = CandleTape.btc();
        long first = S - 40 * CandleTape.MINUTE;
        long last = S + 12 * CandleTape.MINUTE;
        switch (kind) {
            case "gaps" -> {
                for (long m = first; m <= last; m += CandleTape.MINUTE) {
                    if (random.nextInt(10) == 0) {
                        if (random.nextBoolean()) {
                            tape.quiet(m);
                        } else {
                            tape.absent(m);
                        }
                    }
                }
            }
            case "shifted" -> tape.shifted(random.nextBoolean() ? -0.01 : 0.012);
            case "quiet" -> {
                tape = CandleTape.flat(100.0).quiet(first, last).fillerPrice(95.0); // the last trade, 5% off the marks
            }
            default -> {
            }
        }
        return new Scenario(seed, kind, outages, delays, tape);
    }

    /** The marks the poller would have stored: every 3 s from before the first window to after the last. */
    private static void fill(TickStore store, Scenario sc) {
        for (long at = S - W - 60_000L; at <= S + 3 * W + 120_000L; at += 3_000L) {
            boolean inOutage = false;
            for (long[] outage : sc.outages()) {
                inOutage |= at >= outage[0] && at < outage[1];
            }
            if (!inOutage) {
                store.append("hyperliquid", at, Map.of("BTC", sc.tape().markPrice(at)));
            }
        }
    }

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

    /** The scheduler's retry: every 61 s (the source is asked once a minute), a few times. */
    private static RoundPriceData settle(LiveMarketPrices prices, AtomicLong now, RoundWindow window, long delay) {
        now.set(Math.max(now.get(), window.endMs() + delay)); // time never goes backwards
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return prices.resolve(BTC, window);
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

    /** What one pass over every scenario and every order found. */
    private record Tally(int settled, int checkedAB, int checkedDB, int checkedEA, int allSettled) {
    }

    private Tally everyOrder(PricesConfig config) {
        List<List<Integer>> orders = new ArrayList<>();
        permutations(List.of(), List.of(0, 1, 2, 3), orders::add);
        assertThat(orders).hasSize(24);
        int settledWindows = 0;
        int checkedAB = 0;
        int checkedDB = 0;
        int checkedEA = 0;
        int allSettled = 0;
        for (long seed = 1; seed <= SEEDS; seed++) {
            Scenario sc = scenario(seed);
            for (List<Integer> order : orders) {
                AtomicLong now = new AtomicLong(S);
                TickStore store = new TickStore(NO_SNAPSHOTS, config.retentionMs(), now::get);
                fill(store, sc);
                FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
                api.info.handler = body -> sc.tape().snapshot(body, now.get());
                HyperliquidPriceSource source = new HyperliquidPriceSource(() -> api, config, now::get);
                LiveMarketPrices prices = new LiveMarketPrices(store, new PriceSources(List.of(source)), config, now::get);

                Map<String, RoundPriceData> settled = new LinkedHashMap<>();
                for (int index : order) {
                    RoundPriceData data = settle(prices, now, WINDOWS.get(index), sc.delays()[index]);
                    if (data != null) {
                        settled.put(NAMES.get(index), data);
                    }
                }
                String what = "seed " + seed + " (" + sc.kind() + ") order " + order.stream().map(NAMES::get).toList()
                        + " outages " + sc.outages().stream().map(o -> (o[0] - S) / 1000 + ".." + (o[1] - S) / 1000).toList();
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
                // no price of another level than the marks' ever got into the ring, bridged, carried or pinned
                for (PricePoint p : store.series("BTC", Long.MIN_VALUE / 2, Long.MAX_VALUE / 2)) {
                    double mark = sc.tape().markAt(p.atMs());
                    assertThat(Math.abs(p.raw().doubleValue() - mark) / mark)
                            .as(what + ": the tick at +" + (p.atMs() - S) / 1000.0 + " s is " + p.raw() + ", the mark is " + mark)
                            .isLessThan(0.005);
                }
                settledWindows += settled.size();
                if (settled.size() == WINDOWS.size()) {
                    allSettled++;
                }
            }
        }
        return new Tally(settledWindows, checkedAB, checkedDB, checkedEA, allSettled);
    }

    @Test
    void windowsThatMeetAtABoundaryReadTheSamePriceThereWhateverTheCandlesDidAndNoOffPriceEntersTheRing() {
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(LiveMarketPrices.class.getName());
        java.util.logging.Level before = log.getLevel();
        log.setLevel(java.util.logging.Level.OFF); // tens of thousands of backfills: the log is not what is looked at
        try {
            Tally bridged = everyOrder(PricesConfig.defaults());
            Tally unbridged = everyOrder(PricesConfig.defaults().withHyperliquidMaxHealMs(0));

            assertThat(bridged.checkedAB()).as("runs that compared open(B) with close(A)").isGreaterThan(2_000);
            assertThat(bridged.checkedDB()).as("runs that compared close(D) with close(B)").isGreaterThan(2_000);
            assertThat(bridged.checkedEA()).as("runs that compared open(E) with open(A)").isGreaterThan(2_000);
            assertThat(unbridged.checkedAB()).isPositive();
            // the bridge settles windows that waited for a refund without it, and never fewer
            assertThat(bridged.settled()).as("windows settled with the bridge / without it: %d / %d", bridged.settled(),
                    unbridged.settled()).isGreaterThan(unbridged.settled() + 1_000);
            assertThat(bridged.allSettled()).isGreaterThan(unbridged.allSettled());
        } finally {
            log.setLevel(before);
        }
    }
}
