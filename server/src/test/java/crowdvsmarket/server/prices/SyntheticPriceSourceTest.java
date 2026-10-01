package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The synthetic walk (herd.prices.source=synthetic): deterministic, seeded from the market id, one
 * tick per second, rebuilt exactly by its own history, never leaving the range a game can use; and
 * its two test hooks.
 */
class SyntheticPriceSourceTest {

    private static final long T0 = 1_790_883_600_000L;

    private final Market btc = TestMarkets.btc();
    private final Market doge = TestMarkets.synthetic("DOGE", MarketKind.PERP, "DOGE", 5, null);
    private final Market odds = TestMarkets.synthetic("PMSYN1", MarketKind.ODDS, "SYNP1", 4, "{\"start\":\"0.52\"}");

    private final AtomicLong now = new AtomicLong(T0);
    private FakeSnapshotStore snapshots;
    private TickStore store;
    private SyntheticPriceSource source;

    @BeforeEach
    void setUp() {
        snapshots = new FakeSnapshotStore();
        store = new TickStore(snapshots, PricesConfig.defaults().retentionMs(), now::get);
        source = new SyntheticPriceSource(store, PricesConfig.defaults(), now::get);
    }

    private PriceSource.Feed feed() {
        return source.feeds().get(0);
    }

    @Test
    void theSameMarketIdGivesTheSameSecondBySecondWalkEveryTime() {
        List<BigDecimal> first = walk(btc, 0, 600);
        List<BigDecimal> second = walk(btc, 0, 600);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differentMarketsWalkDifferently() {
        assertThat(walk(TestMarkets.synthetic("A", MarketKind.PERP, "BTC", 0, null), 0, 300))
                .isNotEqualTo(walk(TestMarkets.synthetic("B", MarketKind.PERP, "BTC", 0, null), 0, 300));
    }

    @Test
    void theWalkIsAPureFunctionOfTheSecondSoAnyHoleCanBeRebuiltExactly() {
        long from = T0 + 100_000;
        long to = T0 + 130_000;

        List<PricePoint> history = source.history(btc, from, to);

        assertThat(history).hasSize(31); // one per whole second, both ends included
        for (PricePoint p : history) {
            now.set(p.atMs() + 400); // a poll 400 ms into that second reads the second's price
            Map<String, PriceSource.Quote> polled = source.poll(feed(), List.of(btc));
            assertThat(polled.get("BTC").raw()).isEqualByComparingTo(p.raw());
        }
    }

    @Test
    void historyOfAFractionalRangeKeepsOnlyWholeSecondsInsideIt() {
        List<PricePoint> history = source.history(btc, T0 + 100_500, T0 + 103_200);

        assertThat(history).extracting(PricePoint::atMs).containsExactly(T0 + 101_000, T0 + 102_000, T0 + 103_000);
    }

    @Test
    void pricesAreOnTheMarketsOwnGridAndStartNearTheStartPrice() {
        BigDecimal start = SyntheticParams.forSymbol("BTC").start();
        for (BigDecimal price : walk(btc, 0, 3_600)) {
            assertThat(price.scale()).isLessThanOrEqualTo(btc.priceDecimals());
            assertThat(price.doubleValue()).isBetween(start.doubleValue() * 0.75, start.doubleValue() * 1.25);
            assertThat(btc.toEngine(price).signum()).isPositive(); // a lot price the engine accepts
        }
        for (BigDecimal price : walk(doge, 0, 3_600)) {
            assertThat(price.scale()).isLessThanOrEqualTo(doge.priceDecimals());
            assertThat(doge.toEngine(price).signum()).isPositive();
        }
    }

    @Test
    void anOddsWalkStaysAProbabilityOnTheHalfPointGridAndAroundItsStart() {
        List<BigDecimal> walk = walk(odds, 0, 7_200);

        BigDecimal grid = new BigDecimal("0.0005");
        for (BigDecimal p : walk) {
            assertThat(p.remainder(grid)).isEqualByComparingTo("0");
            assertThat(p.doubleValue()).isBetween(0.2, 0.8);
        }
        assertThat(walk.stream().distinct().count()).isGreaterThan(5); // it does move
        long periodStart = Math.floorDiv(T0 / 1000, SyntheticPriceSource.PERIOD_SECONDS)
                * SyntheticPriceSource.PERIOD_SECONDS;
        assertThat(SyntheticPriceSource.priceAt(odds, periodStart)).isEqualByComparingTo("0.52"); // the bridge starts at its start
    }

    @Test
    void theWalkIsContinuousAndReturnsToItsStartAtTheEndOfEachPeriod() {
        BigDecimal start = SyntheticParams.forSymbol("BTC").start();
        long periodStart = Math.floorDiv(T0 / 1000, SyntheticPriceSource.PERIOD_SECONDS) * SyntheticPriceSource.PERIOD_SECONDS;

        // a Brownian bridge: zero at both ends of the period, and the same price one period later
        assertThat(SyntheticPriceSource.priceAt(btc, periodStart)).isEqualByComparingTo(start.setScale(2));
        assertThat(SyntheticPriceSource.priceAt(btc, periodStart + SyntheticPriceSource.PERIOD_SECONDS))
                .isEqualByComparingTo(start.setScale(2));
        assertThat(SyntheticPriceSource.priceAt(btc, periodStart + 5_000))
                .isEqualByComparingTo(SyntheticPriceSource.priceAt(btc, periodStart + 5_000
                        + SyntheticPriceSource.PERIOD_SECONDS));
        // neighbouring seconds differ by far less than a percent
        BigDecimal a = SyntheticPriceSource.priceAt(btc, periodStart + 40_000);
        BigDecimal b = SyntheticPriceSource.priceAt(btc, periodStart + 40_001);
        assertThat(a.subtract(b).abs().divide(a, 8, java.math.RoundingMode.HALF_UP).doubleValue()).isLessThan(0.005);
    }

    @Test
    void theStartPriceComesFromSourceMetaThenTheTableThenTheOddsHash() {
        assertThat(SyntheticPriceSource.startOf(odds)).isEqualByComparingTo("0.52");
        assertThat(SyntheticPriceSource.startOf(btc)).isEqualByComparingTo("84000");
        assertThat(SyntheticPriceSource.startOf(TestMarkets.synthetic("Q", MarketKind.PERP, "NOSUCH", 3, null)))
                .isEqualByComparingTo("100");
        assertThat(SyntheticPriceSource.startOf(TestMarkets.synthetic("Q", MarketKind.PERP, "NOSUCH", 3,
                "{\"start\":\"7.5\"}"))).isEqualByComparingTo("7.5");
        BigDecimal hashed = SyntheticPriceSource.startOf(TestMarkets.synthetic("PMSYN9", MarketKind.ODDS, "S", 4, "not json"));
        assertThat(hashed.doubleValue()).isBetween(0.30, 0.70);
        assertThat(hashed).isEqualByComparingTo(SyntheticPriceSource.oddsStart("PMSYN9"));
    }

    @Test
    void pollGivesOneQuoteForEveryMarketItIsAskedAbout() {
        Map<String, PriceSource.Quote> quotes = source.poll(feed(), List.of(btc, doge, odds));

        assertThat(quotes).containsOnlyKeys("BTC", "DOGE", "PMSYN1");
        assertThat(quotes.values()).allSatisfy(q -> assertThat(q.change24h()).isNull());
    }

    @Test
    void theFeedPricesEveryMarketWhateverItsSourceColumnSaysWithTheSyntheticLimits() {
        assertThat(btc.source()).isEqualTo("hyperliquid"); // BTC's row comes from the V7 migration
        assertThat(feed().handles().test(btc)).isTrue();
        assertThat(feed().handles().test(odds)).isTrue();
        assertThat(feed().id()).isEqualTo("synthetic");
        assertThat(feed().staleMs()).isEqualTo(5_000);
        assertThat(feed().maxGapMs()).isEqualTo(10_000);
    }

    // ----------------------------------------------------------------------------- the test hooks

    @Test
    void pushStoresAnExplicitTickAtAnyTimeAsIfTheFeedDeliveredIt() {
        source.push("BTC", T0 - 3_600_000L, new BigDecimal("1234.50"));

        assertThat(store.last("BTC")).hasValueSatisfying(p -> {
            assertThat(p.atMs()).isEqualTo(T0 - 3_600_000L);
            assertThat(p.raw()).isEqualByComparingTo("1234.5");
        });
        assertThat(store.feedOf("BTC")).contains("synthetic");
        assertThat(snapshots.rows).hasSize(1);
    }

    @Test
    void stopWalkSilencesBothTheGeneratedTicksAndTheGeneratedHistory() {
        assertThat(source.isWalking()).isTrue();
        source.stopWalk();

        assertThat(source.isWalking()).isFalse();
        assertThat(source.poll(feed(), List.of(btc))).isEmpty();
        assertThat(source.history(btc, T0, T0 + 60_000)).isEmpty();

        source.resumeWalk();
        assertThat(source.poll(feed(), List.of(btc))).containsOnlyKeys("BTC");
        assertThat(source.history(btc, T0, T0 + 5_000)).hasSize(6);
    }

    @Test
    void aWalkAndAPushedTickReadThroughLiveMarketPricesLikeAnyOther() {
        LiveMarketPrices prices = new LiveMarketPrices(store, new PriceSources(List.of(source)), PricesConfig.defaults(),
                now::get);
        source.stopWalk();
        long end = T0 + 60_000;
        for (long at = T0 - 2_000; at <= end; at += 1_000) {
            source.push("BTC", at, new BigDecimal("84000").add(BigDecimal.valueOf((at - T0) / 1_000)));
        }

        crowdvsmarket.engine.round.RoundWindow window = new crowdvsmarket.engine.round.RoundWindow("BTC",
                crowdvsmarket.engine.market.Interval.HOURLY, T0, end);
        now.set(end + 500);
        RoundPriceData data = prices.resolve(btc, window);

        assertThat(data.open()).isEqualByComparingTo("84000.00"); // the tick at the start itself is P(start)
        assertThat(data.close()).isEqualByComparingTo("84060.00"); // the tick at the end
        assertThat(data.path()).hasSize(30);
        assertThat(prices.latest("BTC").orElseThrow().stale()).isFalse();
    }

    private List<BigDecimal> walk(Market market, long fromSecond, long toSecond) {
        List<BigDecimal> out = new ArrayList<>();
        for (long second = fromSecond; second < toSecond; second++) {
            out.add(SyntheticPriceSource.priceAt(market, T0 / 1000 + second));
        }
        return out;
    }
}
