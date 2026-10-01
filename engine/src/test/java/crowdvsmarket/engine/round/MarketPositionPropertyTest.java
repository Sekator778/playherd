package crowdvsmarket.engine.round;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.margin.LiquidationCalculator;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.MarketSpec;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Property test for the per-market maintenance margin rate (Р-69): for every market cap from 40
 * down to 3, every leverage the market allows, random stakes (100.00..5000.00, 2dp) and prices
 * (1.00..200 000.00, 2dp — "lot prices" far from BTC's, since the engine's price is whatever
 * scale-2 number the caller hands it) it asserts
 *
 * <ul>
 *   <li>the stake IS the position's initial margin, exactly ({@link PositionSizing}'s contract,
 *       now over every market's leverage range and over prices from 1.00 up);
 *   <li>the liquidation price is below the entry for a long and above it for a short;
 *   <li>a path whose low touches the long's liquidation price liquidates it, and one that stays a
 *       cent short does not — and the mirror image for a short and the path's high.
 * </ul>
 *
 * Seeded for reproducibility, like {@code PositionSizingPropertyTest}; prices are drawn
 * log-uniformly so that 1.00..100.00 is sampled as densely as 10 000..200 000.
 */
class MarketPositionPropertyTest {

    private static final long SEED = 20_261_001L;
    private static final int SAMPLES_PER_LEVERAGE = 30;
    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final long CANDLE_MS = 60_000L;

    @ParameterizedTest
    @ValueSource(ints = {40, 25, 20, 10, 5, 3})
    void stakeLiquidationSideAndTouchingHoldForEveryLeverageOfTheMarket(int maxLeverage) {
        MarketSpec spec = MarketSpec.of("M" + maxLeverage, maxLeverage);
        Random random = new Random(SEED + maxLeverage);
        int checked = 0;
        for (int value = Leverage.MIN; value <= maxLeverage; value++) {
            Leverage leverage = spec.leverage(value);
            // the corners of the stake x price box, then random interior points
            for (String price : List.of("1.00", "200000.00")) {
                for (String stake : List.of("100.00", "5000.00")) {
                    check(spec, leverage, new BigDecimal(stake), new BigDecimal(price));
                    checked++;
                }
            }
            for (int i = 0; i < SAMPLES_PER_LEVERAGE; i++) {
                check(spec, leverage, randomStake(random), randomPrice(random));
                checked++;
            }
        }
        assertThat(checked).isEqualTo((maxLeverage - Leverage.MIN + 1) * (4 + SAMPLES_PER_LEVERAGE));
    }

    private static void check(MarketSpec spec, Leverage leverage, BigDecimal margin, BigDecimal price) {
        String label = "maxLeverage=%d leverage=%d margin=%s price=%s".formatted(
                spec.maxLeverage(), leverage.value(), margin, price);

        // 1. the stake is the position's initial margin, exactly
        BigDecimal size = PositionSizing.sizeForMargin(margin, leverage, price);
        assertThat(LiquidationCalculator.initialMargin(size, price, leverage)).as(label).isEqualByComparingTo(margin);

        for (Direction direction : Direction.values()) {
            Position position = Position.openFromMargin("p", "r", direction, leverage, margin, price, spec);
            BigDecimal liquidationPrice = position.liquidationPrice();
            String side = label + " " + direction;

            assertThat(position.size()).as(side).isEqualTo(size);
            assertThat(liquidationPrice).as(side + " liquidation price is the market-rate formula").isEqualTo(
                    LiquidationCalculator.liquidationPrice(size, price, leverage, direction.toSide(), spec.maintenanceMarginRate()));

            if (direction == Direction.UP) {
                // 2. a long is liquidated below its entry
                assertThat(liquidationPrice).as(side).isPositive().isLessThan(price);
                // 3. touching the liquidation price with the low liquidates it, a cent above does not
                assertThat(simulate(position, candle(price, price, liquidationPrice, price))).as(side + " touched")
                        .isInstanceOf(PositionSimulator.Outcome.Liquidated.class);
                assertThat(simulate(position, candle(price, price, liquidationPrice.add(CENT), price))).as(side + " one cent short")
                        .isInstanceOf(PositionSimulator.Outcome.Settled.class);
            } else {
                // 2. a short is liquidated above its entry
                assertThat(liquidationPrice).as(side).isGreaterThan(price);
                // 3. touching the liquidation price with the high liquidates it, a cent below does not
                assertThat(simulate(position, candle(price, liquidationPrice, price, price))).as(side + " touched")
                        .isInstanceOf(PositionSimulator.Outcome.Liquidated.class);
                assertThat(simulate(position, candle(price, liquidationPrice.subtract(CENT), price, price))).as(side + " one cent short")
                        .isInstanceOf(PositionSimulator.Outcome.Settled.class);
            }
        }
    }

    /**
     * A three-candle path: quiet, {@code middle}, quiet — so the touch has to be found mid-path, and
     * the liquidation must be attributed to the middle candle (and cost the whole stake, Р-7).
     */
    private static PositionSimulator.Outcome simulate(Position position, Candle middle) {
        Candle quietBefore = candle(0L, middle.open(), middle.open(), middle.open(), middle.open());
        Candle quietAfter = candle(2 * CANDLE_MS, middle.close(), middle.close(), middle.close(), middle.close());
        PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, List.of(quietBefore, middle, quietAfter));
        if (outcome instanceof PositionSimulator.Outcome.Liquidated liquidated) {
            assertThat(liquidated.candleOpenTimeMs()).isEqualTo(middle.openTimeMs());
            assertThat(liquidated.loss()).isEqualByComparingTo(
                    LiquidationCalculator.initialMargin(position.size(), position.entryPrice(), position.leverage()));
        }
        return outcome;
    }

    private static Candle candle(BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        return candle(CANDLE_MS, open, high, low, close);
    }

    private static Candle candle(long openTimeMs, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        return new Candle(openTimeMs, openTimeMs + CANDLE_MS - 1, open, high, low, close, BigDecimal.ZERO, 0L);
    }

    /** 100.00 .. 5000.00, 2dp. */
    private static BigDecimal randomStake(Random random) {
        return BigDecimal.valueOf(10_000L + random.nextInt(490_001), 2);
    }

    /** 1.00 .. 200 000.00, 2dp, log-uniform. */
    private static BigDecimal randomPrice(Random random) {
        double logMin = Math.log(1.0);
        double logMax = Math.log(200_000.0);
        double price = Math.exp(logMin + random.nextDouble() * (logMax - logMin));
        long cents = Math.max(100L, Math.min(20_000_000L, Math.round(price * 100.0)));
        return BigDecimal.valueOf(cents, 2);
    }
}
