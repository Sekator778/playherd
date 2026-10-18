package crowdvsmarket.bots.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.bots.TestBars;
import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.catalog.Catalog;
import crowdvsmarket.bots.catalog.CatalogEntry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BacktesterTest {

    private static CatalogEntry entry(String strategy, Map<String, Double> params, String persona) {
        return new CatalogEntry(strategy + "-" + persona, "Test " + strategy, strategy, params, persona);
    }

    @Test
    void windowsAreAlignedToTheEpochGridAndNeedEveryMinute() {
        // minutes 1..10: 3-minute windows start at 3, 6 (9 is incomplete: only minutes 9, 10)
        List<BarDto> bars = TestBars.from(1, TestBars.ramp(10, 100, 1));
        List<Backtester.Window> w = Backtester.windows(bars, 3);
        assertThat(w).extracting(Backtester.Window::startMs).containsExactly(3 * 60_000L, 6 * 60_000L);
        // the window at minute 3 opens with minute 3's open (= minute 2's close, 101) and closes with minute 5's close
        assertThat(w.get(0).candle().open()).isEqualByComparingTo("101.00");
        assertThat(w.get(0).candle().close()).isEqualByComparingTo("104.00");
        // a hole drops the windows that touch it
        List<BarDto> holey = new ArrayList<>(bars);
        holey.remove(5); // minute 6
        assertThat(Backtester.windows(holey, 3)).extracting(Backtester.Window::startMs).containsExactly(3 * 60_000L);
    }

    @Test
    void aRisingMarketPaysAMaximalistAtThePriceOfFees() {
        // +6 per minute on 60 000 (1 bp); momentum-3 UP at leverage 1200 (BTC, 3-minute round, stake 100 PLAY).
        // A window gains ~3 minutes * 6 = 18 on 60 000 = 0.03%; payout = 100 * (1 + 1200 * 0.0003) = 136;
        // fees = herd min(20%, 0.000092 * 1200 = 11.04%) = 11.04 + network 0.10 = 11.14; net ~ 24.9 per bet.
        List<BarDto> bars = TestBars.of(TestBars.ramp(60, 60_000, 6));
        Backtester.Result r = new Backtester().run(entry("momentum", Map.of("n", 3.0), "maximalist"), BacktestMarket.of("BTC", bars), 3);
        assertThat(r.windows()).isEqualTo(20);
        assertThat(r.noSignal()).isEqualTo(2); // windows at minutes 0 and 3 lack the 4 candles of history
        assertThat(r.bets()).isEqualTo(18);
        assertThat(r.hits()).isEqualTo(18);
        assertThat(r.liquidations()).isZero();
        assertThat(r.meanLeverage()).isEqualTo(1200.0);
        assertThat(r.fees()).isEqualByComparingTo(new BigDecimal("11.14").multiply(BigDecimal.valueOf(18)));
        BigDecimal perBet = r.pnl().divide(BigDecimal.valueOf(r.bets()), java.math.MathContext.DECIMAL64);
        assertThat(perBet.doubleValue()).isBetween(24.0, 26.0);
    }

    @Test
    void aCrashInsideTheWindowLiquidatesAndLosesStakePlusFees() {
        // 30 rising minutes, then window [30, 33) falls 0.5% a minute: the long at 1200x is liquidated on its first minute
        // (liquidation price is about 0.075% below the entry) and the remainder at that candle's low is 0.
        double[] closes = TestBars.concat(TestBars.ramp(30, 60_000, 6), new double[] {59_700, 59_400, 59_100});
        List<BarDto> bars = TestBars.of(closes);
        Backtester.Result r = new Backtester().run(entry("momentum", Map.of("n", 3.0), "maximalist"), BacktestMarket.of("BTC", bars), 3);
        assertThat(r.windows()).isEqualTo(11);
        assertThat(r.bets()).isEqualTo(9);
        assertThat(r.hits()).isEqualTo(8);
        assertThat(r.liquidations()).isEqualTo(1);
    }

    @Test
    void aFallingMarketPaysTheShortSideToo() {
        List<BarDto> bars = TestBars.of(TestBars.ramp(60, 60_000, -6));
        Backtester.Result r = new Backtester().run(entry("momentum", Map.of("n", 3.0), "maximalist"), BacktestMarket.of("BTC", bars), 3);
        assertThat(r.hitRate()).isEqualTo(1.0);
        assertThat(r.pnl().signum()).isPositive();
    }

    @Test
    void aFlatMarketHasNoSignalFromMomentumAndTiesAreNotHits() {
        List<BarDto> bars = TestBars.of(TestBars.flat(60, 60_000));
        Backtester.Result r = new Backtester().run(entry("momentum", Map.of("n", 3.0), "disciplined"), BacktestMarket.of("BTC", bars), 15);
        assertThat(r.bets()).isZero();
        assertThat(r.noSignalShare()).isEqualTo(1.0);
        assertThat(Double.isNaN(r.hitRate())).isTrue();
        // coin bets on a flat market: every window is a tie, nothing is hit or missed
        Backtester.Result coin = new Backtester().run(entry("coin", Map.of(), "disciplined"), BacktestMarket.of("BTC", bars), 15);
        assertThat(coin.bets()).isEqualTo(coin.windows());
        assertThat(coin.ties()).isEqualTo(coin.bets());
        assertThat(coin.hits()).isZero();
    }

    @Test
    void theReplayIsDeterministic() {
        List<BarDto> bars = new ArrayList<>();
        java.util.SplittableRandom rng = new java.util.SplittableRandom(7);
        double p = 3_000;
        for (int i = 0; i < 600; i++) {
            double c = p * (1 + rng.nextDouble(-0.002, 0.002));
            bars.add(new BarDto(i * 60_000L, p, Math.max(p, c), Math.min(p, c), c));
            p = c;
        }
        BacktestMarket m = BacktestMarket.of("ETH", bars);
        Catalog catalog = Catalog.load();
        List<Backtester.Result> a = new Backtester().run(catalog.bots(), List.of(m));
        List<Backtester.Result> b = new Backtester().run(catalog.bots(), List.of(m));
        assertThat(b).isEqualTo(a);
        // 21 bots minus the 4 crowd ones, 2 horizons
        assertThat(a).hasSize(17 * 2);
    }

    @Test
    void crowdStrategiesAreNotReplayed() {
        assertThat(Backtester.replayable(entry("crowd-follow", Map.of(), "cautious"))).isFalse();
        assertThat(Backtester.replayable(entry("coin", Map.of(), "cautious"))).isTrue();
    }

    @Test
    void marketTermsAreKnownForTheBacktestMarkets() {
        for (String id : List.of("BTC", "ETH", "SOL", "NVDA", "GOLD")) {
            assertThat(BacktestMarket.specOf(id).capHourly()).isGreaterThan(0);
        }
        assertThat(BacktestMarket.specOf("BTC").capHourly()).isEqualTo(1200);
        assertThat(BacktestMarket.specOf("BTC").capDaily()).isEqualTo(500);
        assertThat(BacktestMarket.specOf("GOLD").herdFeeRate()).isEqualByComparingTo("0.000048");
    }

    @Test
    void candleFilesSkipCommentsDuplicatesAndBrokenLines(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("BTC_1m.csv");
        Files.writeString(f, """
                # source=test
                120000,10,12,9,11
                60000,10,11,9,10
                60000,10,11,9,10.5
                180000,10,9,11,10
                oops
                """);
        CandleFiles.Loaded loaded = CandleFiles.read(f);
        assertThat(loaded.bars()).extracting(BarDto::openTimeMs).containsExactly(60_000L, 120_000L);
        assertThat(loaded.bars().get(0).close()).isEqualTo(10.5);
        assertThat(loaded.dropped()).isEqualTo(2); // the inconsistent candle and the broken line
    }

    @Test
    void reportRendersTablesForTheFilesOfADirectory(@TempDir Path dir) throws IOException {
        StringBuilder sb = new StringBuilder("# source=test\n");
        double p = 3_000;
        java.util.SplittableRandom rng = new java.util.SplittableRandom(3);
        for (int i = 0; i < 400; i++) {
            double c = p * (1 + rng.nextDouble(-0.002, 0.002));
            sb.append(i * 60_000L).append(',').append(p).append(',').append(Math.max(p, c)).append(',')
                    .append(Math.min(p, c)).append(',').append(c).append('\n');
            p = c;
        }
        Files.writeString(dir.resolve("ETH_1m.csv"), sb.toString());
        String report = BacktestMain.report(dir, 100);
        assertThat(report).contains("## Horizon 3 min", "## Horizon 15 min", "momentum-3-disciplined", "| ETH |", "all markets");
        assertThat(report).doesNotContain("crowd-follow-1");
    }
}
