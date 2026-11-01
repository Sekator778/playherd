package crowdvsmarket.engine.round;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.margin.LiquidationCalculator;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.CandleSeries;
import crowdvsmarket.engine.market.Interval;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Property test for the task's core "Units" requirement: a player's stake IS the position's
 * initial margin exactly. {@link PositionSizing} derives an 8dp BTC {@code size} from a PLAY
 * margin; this asserts {@code round2(size * price / leverage) == margin} for many randomized
 * stakes/leverages, against both real BTC prices from the fixtures and synthetic edge prices,
 * seeded for reproducibility.
 */
class PositionSizingPropertyTest {

    private static final long SEED = 42L;
    private static final int SAMPLES_PER_PRICE = 20;

    private static List<BigDecimal> fixturePrices() {
        CandleSeries daily = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1d.csv", Interval.DAILY);
        CandleSeries hourly = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1h.csv", Interval.HOURLY);
        return Stream.concat(daily.candles().stream(), hourly.candles().stream())
                .map(Candle::open)
                .distinct()
                .toList();
    }

    /** Synthetic edge prices beyond anything BTC has traded at, to stress the rounding bound
     * documented on {@link PositionSizing} (error grows with price). */
    private static Stream<BigDecimal> syntheticEdgePrices() {
        return Stream.of(
                new BigDecimal("0.01"),
                new BigDecimal("1.00"),
                new BigDecimal("100.00"),
                new BigDecimal("1000000.00"));
    }

    private static Stream<BigDecimal> prices() {
        return Stream.concat(fixturePrices().stream(), syntheticEdgePrices());
    }

    @ParameterizedTest
    @MethodSource("prices")
    void derivedSizeReproducesTheMarginExactlyAtMoneyScale(BigDecimal price) {
        Random random = new Random(SEED ^ price.hashCode());
        for (int i = 0; i < SAMPLES_PER_PRICE; i++) {
            BigDecimal margin = randomMargin(random);
            Leverage leverage = new Leverage(2 + random.nextInt(40 - Leverage.MIN + 1));

            BigDecimal size = PositionSizing.sizeForMargin(margin, leverage, price);
            BigDecimal recoveredMargin = LiquidationCalculator.initialMargin(size, price, leverage);

            assertThat(recoveredMargin)
                    .as("price=%s margin=%s leverage=%s size=%s", price, margin, leverage.value(), size)
                    .isEqualByComparingTo(margin);
        }
    }

    private static BigDecimal randomMargin(Random random) {
        // 100.00 .. 100000.00, 2dp — spans the min stake (D-12) up to well past the starter pack.
        long cents = 100_00L + (long) (random.nextDouble() * (100_000_00L - 100_00L));
        return BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.UNNECESSARY);
    }
}
