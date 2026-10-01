package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongToDoubleFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The rules of {@link LiveMarketPrices} (docs/research/markets_architecture.md §3.2 rules 3–5):
 * boundary price {@code P(t)}, staleness, holes and their one-shot backfill, the settlement data,
 * the running path, the lobby quote.
 */
class LiveMarketPricesTest {

    /** 2026-10-01T19:40:00Z, on a minute. */
    private static final long T0 = 1_790_883_600_000L;
    private static final long START = T0;
    private static final long END = T0 + 180_000L;

    private final PricesConfig config = PricesConfig.defaults();
    private final AtomicLong now = new AtomicLong(END + 1_000);
    private final Market btc = TestMarkets.btc();
    private final Market xyz = TestMarkets.perp("xyz_NVDA", "xyz:NVDA", Category.STOCKS, 20, 3, 5);

    private FakeSnapshotStore snapshots;
    private TickStore store;
    private ScriptedSource source;
    private LiveMarketPrices prices;

    /** A Hyperliquid-shaped source whose history is whatever the test scripts. */
    private static final class ScriptedSource implements PriceSource {

        final List<long[]> historyRequests = new ArrayList<>();
        java.util.function.Function<Market, List<PricePoint>> history = m -> List.of();
        IOException failure;

        @Override
        public String name() {
            return "hyperliquid";
        }

        @Override
        public List<Feed> feeds() {
            return List.of(new Feed("hyperliquid", 15_000, 30_000, m -> "BTC".equals(m.sourceRef())),
                    new Feed("hyperliquid:xyz", 20_000, 30_000, m -> m.sourceRef().startsWith("xyz:")));
        }

        @Override
        public Map<String, Quote> poll(Feed feed, List<Market> markets) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PricePoint> history(Market market, long fromMs, long toMs) throws IOException {
            historyRequests.add(new long[] {fromMs, toMs});
            if (failure != null) {
                throw failure;
            }
            return history.apply(market);
        }
    }

    @BeforeEach
    void setUp() {
        snapshots = new FakeSnapshotStore();
        store = new TickStore(snapshots, config.retentionMs(), now::get);
        source = new ScriptedSource();
        prices = new LiveMarketPrices(store, new PriceSources(List.of(source)), config, now::get);
    }

    private void ticks(Market market, String feed, long from, long to, long stepMs, LongToDoubleFunction price) {
        for (long at = from; at <= to; at += stepMs) {
            store.append(feed, at, Map.of(market.id(), BigDecimal.valueOf(price.applyAsDouble(at))));
        }
    }

    private static double ramp(long at) {
        return 100 + (at - T0) / 3_000.0;
    }

    private RoundWindow window(Market market) {
        return new RoundWindow(market.id(), Interval.HOURLY, START, END);
    }

    private static PricePoint point(long at, double price) {
        return new PricePoint(at, BigDecimal.valueOf(price));
    }

    // ------------------------------------------------------------------------------ settlement

    @Test
    void aCompleteWindowSettlesFromMemoryWithoutAskingTheSource() {
        ticks(btc, "hyperliquid", START - 2_000, END + 6_000, 3_000, LiveMarketPricesTest::ramp);

        RoundPriceData data = prices.resolve(btc, window(btc));

        // P(start): the last tick at or before START is at START - 2 s; P(end): the last at or before END
        assertThat(data.open()).isEqualByComparingTo(BigDecimal.valueOf(ramp(START - 2_000)).setScale(2, java.math.RoundingMode.HALF_UP));
        long lastBeforeEnd = START - 2_000 + 3_000 * ((END - (START - 2_000)) / 3_000);
        assertThat(data.close()).isEqualByComparingTo(BigDecimal.valueOf(ramp(lastBeforeEnd)).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(data.sourceTsMs()).isEqualTo(lastBeforeEnd);
        assertThat(data.path()).hasSize(30);
        assertThat(data.path().get(0).open()).isEqualByComparingTo(data.open());
        assertThat(data.path().get(29).close()).isEqualByComparingTo(data.close());
        assertThat(source.historyRequests).isEmpty();
    }

    @Test
    void theBoundaryPriceIsTheLastTickAtOrBeforeTheBoundaryNotTheFirstAfter() {
        ticks(btc, "hyperliquid", START - 4_000, START - 4_000, 1, at -> 100);
        ticks(btc, "hyperliquid", START + 1_000, END - 3_000, 3_000, at -> 150);
        store.append("hyperliquid", END, Map.of("BTC", new BigDecimal("160"))); // exactly at the end: P(end)
        store.append("hyperliquid", END + 1, Map.of("BTC", new BigDecimal("170"))); // after: not part of the round

        RoundPriceData data = prices.resolve(btc, window(btc));

        assertThat(data.open()).isEqualByComparingTo("100.00");
        assertThat(data.close()).isEqualByComparingTo("160.00");
        assertThat(data.sourceTsMs()).isEqualTo(END);
    }

    @Test
    void theCloseOfOneRoundIsTheOpenOfTheNext() {
        ticks(btc, "hyperliquid", START - 2_000, END + 200_000, 3_000, LiveMarketPricesTest::ramp);
        RoundWindow next = new RoundWindow(btc.id(), Interval.HOURLY, END, END + 180_000);

        RoundPriceData first = prices.resolve(btc, window(btc));
        RoundPriceData second = prices.resolve(btc, next);

        assertThat(second.open()).isEqualByComparingTo(first.close());
    }

    @Test
    void aStaleBoundaryTickIsAHoleAndIsFilledFromHistory() {
        // the last tick before START is 16 s old: one second beyond the 15 s of the default dex
        ticks(btc, "hyperliquid", START - 16_000, START - 16_000, 1, at -> 100);
        ticks(btc, "hyperliquid", START + 3_000, END + 3_000, 3_000, at -> 101);
        source.history = m -> List.of(point(START - 10_000, 100.5), point(START - 5_000, 100.7),
                point(START - 1, 100.9), point(START + 3_000, 999)); // the last one would overwrite a live tick

        RoundPriceData data = prices.resolve(btc, window(btc));

        assertThat(source.historyRequests).hasSize(1);
        assertThat(data.open()).isEqualByComparingTo("100.90"); // P(start) is the history point 1 ms before START
        assertThat(store.series("BTC", START + 3_000, START + 3_001).get(0).raw()).isEqualByComparingTo("101");
        assertThat(store.size("BTC")).isGreaterThan(60);
    }

    @Test
    void aMissingOpenWithNoTicksBeforeItIsFilledFromHistoryToo() {
        // a market listed after the round began: the first live tick is in the middle of the window
        ticks(btc, "hyperliquid", START + 100_000, END + 3_000, 3_000, at -> 120);
        source.history = m -> {
            List<PricePoint> out = new ArrayList<>();
            for (long at = START - 60_000; at <= START + 100_000; at += 20_000) {
                out.add(point(at, 110));
            }
            return out;
        };

        RoundPriceData data = prices.resolve(btc, window(btc));

        assertThat(data.open()).isEqualByComparingTo("110.00");
        assertThat(data.close()).isEqualByComparingTo("120.00");
        assertThat(source.historyRequests.get(0)[0]).isLessThanOrEqualTo(START - 60_000);
    }

    @Test
    void twoNeighbouringTicksMoreThanTheMaximumGapApartAreAHoleBackfilledOnce() {
        ticks(btc, "hyperliquid", START - 2_000, START + 60_000, 3_000, at -> 100);
        ticks(btc, "hyperliquid", START + 100_000, END + 3_000, 3_000, at -> 100); // 40 s without a tick (and a bit)
        long gapFrom = START - 2_000 + 3_000 * ((60_000 + 2_000) / 3_000); // the last tick before the hole
        source.history = m -> {
            List<PricePoint> out = new ArrayList<>();
            for (long at = START + 40_000; at <= START + 120_000; at += 20_000) {
                out.add(point(at, 100.25));
            }
            return out;
        };

        RoundPriceData data = prices.resolve(btc, window(btc));

        assertThat(source.historyRequests).hasSize(1);
        assertThat(source.historyRequests.get(0)[0]).isEqualTo(gapFrom);
        assertThat(source.historyRequests.get(0)[1]).isEqualTo(START + 100_000);
        assertThat(data.path()).hasSize(30);
        // only the points inside the hole were merged (+60 s and +80 s), not the one at +40 s before it
        assertThat(store.series("BTC", gapFrom + 1, START + 100_000)).extracting(PricePoint::atMs)
                .containsExactly(START + 60_000, START + 80_000);
    }

    @Test
    void theStalenessLimitItselfIsStillFreshAndOneMillisecondMoreIsStale() {
        // a tick exactly 15 s before START is fresh (not OLDER than staleMs), 15.001 s is stale
        ticks(btc, "hyperliquid", START - 15_000, START - 15_000, 1, at -> 100);
        ticks(btc, "hyperliquid", START + 3_000, END + 3_000, 3_000, at -> 101);
        assertThat(prices.resolve(btc, window(btc)).open()).isEqualByComparingTo("100.00");
        assertThat(source.historyRequests).isEmpty();

        setUp();
        ticks(btc, "hyperliquid", START - 15_001, START - 15_001, 1, at -> 100);
        ticks(btc, "hyperliquid", START + 3_000, END + 3_000, 3_000, at -> 101);
        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class);
        assertThat(source.historyRequests).hasSize(1);
    }

    @Test
    void aGapOfOneMillisecondMoreThanTheMaximumIsAHole() {
        ticks(btc, "hyperliquid", START - 2_000, START + 58_000, 30_000, at -> 100);
        ticks(btc, "hyperliquid", START + 88_001, END + 3_000, 30_000, at -> 100); // 30.001 s after START + 58 s

        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class);
        assertThat(source.historyRequests).hasSize(1);
        assertThat(source.historyRequests.get(0)).containsExactly(START + 58_000, START + 88_001);
    }

    @Test
    void aGapExactlyAtTheMaximumIsNotAHole() {
        ticks(btc, "hyperliquid", START - 2_000, START + 58_000, 30_000, at -> 100); // 30 s apart: allowed
        ticks(btc, "hyperliquid", START + 58_000, END + 3_000, 30_000, at -> 100);

        prices.resolve(btc, window(btc));

        assertThat(source.historyRequests).isEmpty();
    }

    @Test
    void aHoleThatStaysIsPriceUnavailableAndTheSourceIsNotAskedAgainWithinTheCooldown() {
        ticks(btc, "hyperliquid", START - 2_000, START + 30_000, 3_000, at -> 100);
        // nothing after START + 30 s: P(end) is missing

        assertThatThrownBy(() -> prices.resolve(btc, window(btc)))
                .isInstanceOf(PriceUnavailableException.class)
                .hasMessageContaining("BTC")
                .hasMessageContaining(window(btc).roundId());
        assertThat(source.historyRequests).hasSize(1);

        // the scheduler retries every second: no new request within a minute, but the answer stays "unavailable"
        now.addAndGet(30_000);
        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class);
        assertThat(source.historyRequests).hasSize(1);

        now.addAndGet(31_000);
        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class);
        assertThat(source.historyRequests).hasSize(2);
    }

    @Test
    void aFailingHistoryRequestIsPriceUnavailableNotACrash() {
        ticks(btc, "hyperliquid", START - 2_000, START + 30_000, 3_000, at -> 100);
        source.failure = new IOException("connection refused");

        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class);
    }

    @Test
    void historyPointsAtTheSameTimeAsALiveTickNeverReplaceIt() {
        ticks(btc, "hyperliquid", START - 2_000, START + 60_000, 3_000, at -> 100);
        ticks(btc, "hyperliquid", START + 100_000, END + 3_000, 3_000, at -> 100);
        // two points inside the hole, one on the live tick that ends it: the hole is still 40 s wide
        source.history = m -> List.of(point(START + 60_001, 7), point(START + 99_999, 7), point(START + 100_000, 7));

        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class);

        assertThat(store.series("BTC", START + 100_000, START + 100_001).get(0).raw()).isEqualByComparingTo("100");
        assertThat(store.series("BTC", START + 60_001, START + 100_000)).extracting(PricePoint::atMs)
                .containsExactly(START + 60_001, START + 99_999); // merged all the same, they are real history
    }

    @Test
    void noTickAtAllIsUnavailable() {
        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class);
    }

    @Test
    void theStalenessLimitIsTheFeedsOwn() {
        // 18 s before START: stale for the default dex (15 s), still fine for xyz (20 s)
        ticks(xyz, "hyperliquid:xyz", START - 18_000, START - 18_000, 1, at -> 231.7);
        ticks(xyz, "hyperliquid:xyz", START + 3_000, END + 3_000, 6_000, at -> 231.7);
        ticks(btc, "hyperliquid", START - 18_000, START - 18_000, 1, at -> 84_000);
        ticks(btc, "hyperliquid", START + 3_000, END + 3_000, 3_000, at -> 84_000);

        RoundPriceData ok = prices.resolve(xyz, window(xyz));

        assertThat(ok.open()).isEqualByComparingTo("231700.00"); // lot exponent 3
        assertThat(source.historyRequests).isEmpty();
        assertThatThrownBy(() -> prices.resolve(btc, window(btc))).isInstanceOf(PriceUnavailableException.class);
        assertThat(source.historyRequests).hasSize(1);
    }

    @Test
    void aWindowOfAnotherMarketIsRefused() {
        assertThatThrownBy(() -> prices.resolve(xyz, window(btc))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> prices.soFar(xyz, window(btc), now.get())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPriceTheEngineCannotHoldMakesTheRoundUnavailableInsteadOfThrowingAnIllegalArgument() {
        ticks(btc, "hyperliquid", START - 2_000, END + 3_000, 3_000, at -> 100);
        // 0.001 USD is 0.00 at lot exponent 0: an engine Candle would reject low = 0
        store.append("hyperliquid", START + 30_000, Map.of("BTC", new BigDecimal("0.001")));

        assertThatThrownBy(() -> prices.resolve(btc, window(btc)))
                .isInstanceOf(PriceUnavailableException.class)
                .hasMessageContaining("engine");
    }

    // ------------------------------------------------------------------------------- running path

    @Test
    void soFarIsEmptyUntilTheOpenPriceIsKnown() {
        ticks(btc, "hyperliquid", START + 10_000, START + 40_000, 3_000, at -> 100); // the first tick is after START

        assertThat(prices.soFar(btc, window(btc), START + 40_000)).isEmpty();
        assertThat(prices.soFar(btc, window(btc), START - 1)).isEmpty(); // not started
    }

    @Test
    void soFarIsEmptyWhenTheOpenTickIsStale() {
        ticks(btc, "hyperliquid", START - 16_000, START - 16_000, 1, at -> 100);
        ticks(btc, "hyperliquid", START + 3_000, START + 30_000, 3_000, at -> 101);

        assertThat(prices.soFar(btc, window(btc), START + 30_000)).isEmpty();
    }

    @Test
    void soFarBuildsTheSameConstructionOverTheTicksSoFarFromMemoryOnly() {
        ticks(btc, "hyperliquid", START - 2_000, START + 50_000, 3_000, LiveMarketPricesTest::ramp);
        long nowMs = START + 45_500;

        RoundPriceData data = prices.soFar(btc, window(btc), nowMs).orElseThrow();

        assertThat(data.open()).isEqualByComparingTo(BigDecimal.valueOf(ramp(START - 2_000)).setScale(2, java.math.RoundingMode.HALF_UP));
        List<Candle> path = data.path();
        assertThat(path).hasSize(8); // 45.5 s into 6 s candles
        assertThat(path.get(7).closeTimeMs()).isEqualTo(nowMs);
        long lastTickSoFar = START - 2_000 + 3_000 * ((45_500 + 2_000) / 3_000);
        assertThat(data.close()).isEqualByComparingTo(BigDecimal.valueOf(ramp(lastTickSoFar)).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(data.sourceTsMs()).isEqualTo(lastTickSoFar);
        assertThat(source.historyRequests).isEmpty();
    }

    @Test
    void soFarOfAFinishedWindowIsTheFullPathWithoutHistory() {
        ticks(btc, "hyperliquid", START - 2_000, END + 6_000, 3_000, LiveMarketPricesTest::ramp);

        RoundPriceData running = prices.soFar(btc, window(btc), END + 500).orElseThrow();
        RoundPriceData settled = prices.resolve(btc, window(btc));

        assertThat(running).isEqualTo(settled);
    }

    // ----------------------------------------------------------------------------- lobby quote

    @Test
    void latestIsTheLastTickWithItsStaleFlagAndTheSourcesDayChange() {
        assertThat(prices.latest("BTC")).isEmpty();
        store.append("hyperliquid", END, Map.of("BTC", new BigDecimal("84629.0")));
        store.setChange24h("BTC", 0.0105);

        now.set(END + 14_000);
        PriceQuote fresh = prices.latest("BTC").orElseThrow();
        now.set(END + 16_000);
        PriceQuote stale = prices.latest("BTC").orElseThrow();

        assertThat(fresh.raw()).isEqualByComparingTo("84629.0");
        assertThat(fresh.asOfMs()).isEqualTo(END);
        assertThat(fresh.change24h()).isEqualTo(0.0105);
        assertThat(fresh.stale()).isFalse();
        assertThat(stale.stale()).isTrue();
    }

    @Test
    void latestOfTheXyzDexIsStaleOnlyAfterTwentySeconds() {
        store.append("hyperliquid:xyz", END, Map.of("xyz_NVDA", new BigDecimal("231.7")));

        now.set(END + 19_000);
        assertThat(prices.latest("xyz_NVDA").orElseThrow().stale()).isFalse();
        now.set(END + 21_000);
        assertThat(prices.latest("xyz_NVDA").orElseThrow().stale()).isTrue();
    }

    @Test
    void latestWithoutChange24hHasNone() {
        store.append("hyperliquid", END, Map.of("BTC", new BigDecimal("84629.0")));

        assertThat(prices.latest("BTC").orElseThrow().change24h()).isNull();
    }

    @Test
    void seriesIsTheTicksOfTheRangeFromMemory() {
        ticks(btc, "hyperliquid", START, START + 30_000, 3_000, at -> 100);

        assertThat(prices.series("BTC", START + 6_000, START + 15_000)).extracting(PricePoint::atMs)
                .containsExactly(START + 6_000, START + 9_000, START + 12_000);
    }
}
