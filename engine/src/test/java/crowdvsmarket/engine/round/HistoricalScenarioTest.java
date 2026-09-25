package crowdvsmarket.engine.round;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.CandleSeries;
import crowdvsmarket.engine.market.Interval;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The 25 scenarios from docs/research/hl_liquidation_math.md section 5 ("Сценарии для тестов"):
 * the 10 largest daily drops, 10 largest daily rises, and 5 most extreme hourly candles from the
 * fixtures, each with the exact set of leverages (2..40) that get liquidated. A position is
 * opened at STANDARD_SIZE_BTC (0.10000, doc section 2.7) at the candle's open price and replayed
 * through that single candle; this test asserts the resulting liquidated/not-liquidated set
 * matches the spec exactly, for every leverage and both directions.
 */
class HistoricalScenarioTest {

    private static final BigDecimal STANDARD_SIZE_BTC = new BigDecimal("0.10000");

    private record Scenario(
            String fixtureFile,
            long candleOpenTimeMs,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            Set<Integer> liquidatedLongLeverages,
            Set<Integer> liquidatedShortLeverages) {
    }

    private static Set<Integer> range(int fromInclusive, int toInclusive) {
        return IntStream.rangeClosed(fromInclusive, toInclusive).boxed().collect(toSet());
    }

    private static final Set<Integer> NONE = Set.of();

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static List<Scenario> scenarios() {
        String daily = "hl_btc_1d.csv";
        String hourly = "hl_btc_1h.csv";
        return List.of(
                // --- 10 largest daily drops (low vs open) ---
                new Scenario(daily, 1621382400000L, d("42882.5"), d("43615.9"), d("28688.0"), d("36689.1"),
                        range(3, 40), range(34, 40)),
                new Scenario(daily, 1638576000000L, d("53611.9"), d("53889.7"), d("40888.9"), d("49135.7"),
                        range(5, 40), NONE),
                new Scenario(daily, 1610323200000L, d("38200.0"), d("38322.2"), d("30448.0"), d("35408.5"),
                        range(5, 40), NONE),
                new Scenario(daily, 1630972800000L, d("52684.0"), d("52950.0"), d("42088.0"), d("46864.4"),
                        range(5, 40), NONE),
                new Scenario(daily, 1667865600000L, d("20578.5"), d("20685.0"), d("16830.0"), d("18534.0"),
                        range(6, 40), NONE),
                new Scenario(daily, 1655078400000L, d("26564.7"), d("26877.0"), d("21900.0"), d("22471.5"),
                        range(6, 40), NONE),
                new Scenario(daily, 1621555200000L, d("40500.0"), d("42270.4"), d("33400.0"), d("37211.0"),
                        range(6, 40), range(18, 40)),
                new Scenario(daily, 1760054400000L, d("121615.0"), d("122460.0"), d("100837.0"), d("112761.0"),
                        range(6, 40), NONE),
                new Scenario(daily, 1614038400000L, d("54125.6"), d("54260.1"), d("44893.8"), d("48930.0"),
                        range(6, 40), NONE),
                new Scenario(daily, 1621728000000L, d("37436.0"), d("38261.7"), d("31096.7"), d("34651.6"),
                        range(6, 40), range(29, 40)),
                // --- 10 largest daily rises (high vs open) ---
                new Scenario(daily, 1627257600000L, d("35370.7"), d("48168.6"), d("35185.5"), d("37218.1"),
                        NONE, range(3, 40)),
                new Scenario(daily, 1698019200000L, d("29994.0"), d("36504.0"), d("29902.0"), d("33121.0"),
                        NONE, range(5, 40)),
                new Scenario(daily, 1612742400000L, d("38849.2"), d("47146.0"), d("38037.0"), d("46451.3"),
                        range(31, 40), range(5, 40)),
                new Scenario(daily, 1646006400000L, d("37672.1"), d("44341.0"), d("37414.4"), d("43155.0"),
                        NONE, range(6, 40)),
                new Scenario(daily, 1621468800000L, d("36689.1"), d("42460.0"), d("34882.0"), d("40500.3"),
                        range(17, 40), range(6, 40)),
                new Scenario(daily, 1611878400000L, d("33400.0"), d("38641.1"), d("31960.0"), d("34269.4"),
                        range(19, 40), range(6, 40)),
                new Scenario(daily, 1621814400000L, d("34656.5"), d("39917.1"), d("34366.0"), d("38779.7"),
                        NONE, range(7, 40)),
                new Scenario(daily, 1668038400000L, d("15905.0"), d("18230.0"), d("15730.0"), d("17588.0"),
                        NONE, range(7, 40)),
                new Scenario(daily, 1609545600000L, d("29337.2"), d("33480.0"), d("28958.2"), d("32199.9"),
                        Set.of(40), range(7, 40)),
                new Scenario(daily, 1770336000000L, d("62894.0"), d("71684.0"), d("60000.0"), d("70516.0"),
                        range(18, 40), range(7, 40)),
                // --- 5 most extreme hourly candles ---
                new Scenario(hourly, 1787151600000L, d("65892.0"), d("70224.0"), d("65851.0"), d("68532.0"),
                        NONE, range(13, 40)),
                new Scenario(hourly, 1782392400000L, d("61212.0"), d("61336.0"), d("58062.0"), d("58234.0"),
                        range(16, 40), NONE),
                new Scenario(hourly, 1774263600000L, d("68515.0"), d("71502.0"), d("68513.0"), d("70075.0"),
                        NONE, range(18, 40)),
                new Scenario(hourly, 1787299200000L, d("76338.0"), d("79584.0"), d("76287.0"), d("79317.0"),
                        NONE, range(19, 40)),
                new Scenario(hourly, 1780869600000L, d("61750.0"), d("64228.0"), d("61696.0"), d("62833.0"),
                        NONE, range(19, 40)));
    }

    private static CandleSeries dailySeries;
    private static CandleSeries hourlySeries;

    @BeforeAll
    static void loadFixtures() {
        dailySeries = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1d.csv", Interval.DAILY);
        hourlySeries = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1h.csv", Interval.HOURLY);
    }

    static Stream<Scenario> scenarioStream() {
        return scenarios().stream();
    }

    private static Candle findByOpenTime(CandleSeries series, long openTimeMs) {
        return series.candles().stream()
                .filter(c -> c.openTimeMs() == openTimeMs)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no candle at openTimeMs=" + openTimeMs + " in fixture"));
    }

    @ParameterizedTest
    @MethodSource("scenarioStream")
    void reproducesLiquidatedLeverageSets(Scenario scenario) {
        CandleSeries series = scenario.fixtureFile().equals("hl_btc_1d.csv") ? dailySeries : hourlySeries;
        Candle candle = findByOpenTime(series, scenario.candleOpenTimeMs());

        // Cross-check: the fixture candle actually has the OHLC the spec quotes for this scenario.
        assertThat(candle.open()).as("open").isEqualByComparingTo(scenario.open());
        assertThat(candle.high()).as("high").isEqualByComparingTo(scenario.high());
        assertThat(candle.low()).as("low").isEqualByComparingTo(scenario.low());
        assertThat(candle.close()).as("close").isEqualByComparingTo(scenario.close());

        for (int leverageValue = Leverage.MIN; leverageValue <= Leverage.MAX; leverageValue++) {
            Leverage leverage = new Leverage(leverageValue);
            for (Direction direction : Direction.values()) {
                Position position = Position.open(
                        "crowd-user", "round-" + scenario.candleOpenTimeMs(), direction, leverage,
                        STANDARD_SIZE_BTC, candle.open());
                PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, List.of(candle));
                boolean actuallyLiquidated = outcome instanceof PositionSimulator.Outcome.Liquidated;

                boolean expectedLiquidated = direction == Direction.UP
                        ? scenario.liquidatedLongLeverages().contains(leverageValue)
                        : scenario.liquidatedShortLeverages().contains(leverageValue);

                assertThat(actuallyLiquidated)
                        .as("candle=%s direction=%s leverage=%d", scenario.candleOpenTimeMs(), direction, leverageValue)
                        .isEqualTo(expectedLiquidated);
            }
        }
    }
}
