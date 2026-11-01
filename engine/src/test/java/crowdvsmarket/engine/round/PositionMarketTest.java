package crowdvsmarket.engine.round;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.margin.LiquidationCalculator;
import crowdvsmarket.engine.market.MarketSpec;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * {@link Position}'s market overloads (D-69): the market contributes exactly one thing, the
 * maintenance margin rate inside {@code liquidationPrice}; everything pre-markets is the BTC case.
 */
class PositionMarketTest {

    private static final MarketSpec ETH = MarketSpec.of("ETH", 25); // l = 0.02
    private static final BigDecimal ETH_SIZE = new BigDecimal("0.50000");
    private static final BigDecimal ETH_PRICE = new BigDecimal("2694.40");

    @Test
    void theOverloadsWithoutASpecAreTheBtcCase() {
        for (int value = Leverage.MIN; value <= 40; value++) {
            Leverage leverage = new Leverage(value);
            for (Direction direction : Direction.values()) {
                BigDecimal size = new BigDecimal("0.10000");
                BigDecimal price = new BigDecimal("50000.00");
                BigDecimal margin = new BigDecimal("500.00");

                assertThat(Position.open("u", "r", direction, leverage, size, price))
                        .isEqualTo(Position.open("u", "r", direction, leverage, size, price, MarketSpec.BTC));
                assertThat(Position.openFromMargin("u", "r", direction, leverage, margin, price))
                        .isEqualTo(Position.openFromMargin("u", "r", direction, leverage, margin, price, MarketSpec.BTC));
            }
        }
    }

    @Test
    void aBtcPositionKeepsTheGoldenLiquidationPrices() {
        // hl_liquidation_math.md section 3's BTC vectors at entry 50000.00, size 0.10000
        BigDecimal size = new BigDecimal("0.10000");
        BigDecimal price = new BigDecimal("50000.00");

        assertThat(Position.open("u", "r", Direction.UP, new Leverage(2), size, price, MarketSpec.BTC).liquidationPrice())
                .isEqualByComparingTo("25316.46");
        assertThat(Position.open("u", "r", Direction.UP, new Leverage(40), size, price, MarketSpec.BTC).liquidationPrice())
                .isEqualByComparingTo("49367.09");
        assertThat(Position.open("u", "r", Direction.DOWN, new Leverage(2), size, price, MarketSpec.BTC).liquidationPrice())
                .isEqualByComparingTo("74074.07");
        assertThat(Position.open("u", "r", Direction.DOWN, new Leverage(40), size, price, MarketSpec.BTC).liquidationPrice())
                .isEqualByComparingTo("50617.28");
    }

    @Test
    void anotherMarketsPositionUsesItsOwnRate() {
        // golden rows of LiquidationCalculatorMarketRateTest: maxLeverage 25, l = 0.02, 2694.40, size 0.5
        Position longPosition = Position.open("u", "r", Direction.UP, new Leverage(25), ETH_SIZE, ETH_PRICE, ETH);
        Position shortPosition = Position.open("u", "r", Direction.DOWN, new Leverage(25), ETH_SIZE, ETH_PRICE, ETH);

        assertThat(longPosition.liquidationPrice()).isEqualByComparingTo("2639.41");
        assertThat(shortPosition.liquidationPrice()).isEqualByComparingTo("2747.24");

        // the same position at BTC's lower rate (0.0125) is liquidated further from the entry
        Position btcLong = Position.open("u", "r", Direction.UP, new Leverage(25), ETH_SIZE, ETH_PRICE, MarketSpec.BTC);
        Position btcShort = Position.open("u", "r", Direction.DOWN, new Leverage(25), ETH_SIZE, ETH_PRICE, MarketSpec.BTC);
        assertThat(btcLong.liquidationPrice()).isEqualByComparingTo("2619.36");
        assertThat(btcShort.liquidationPrice()).isEqualByComparingTo("2767.59");
        assertThat(longPosition.liquidationPrice()).isGreaterThan(btcLong.liquidationPrice());
        assertThat(shortPosition.liquidationPrice()).isLessThan(btcShort.liquidationPrice());
    }

    @Test
    void theMarketChangesNothingButTheLiquidationPrice() {
        Position eth = Position.open("alice", "ETH-1h-5", Direction.UP, new Leverage(10), ETH_SIZE, ETH_PRICE, ETH);
        Position btc = Position.open("alice", "ETH-1h-5", Direction.UP, new Leverage(10), ETH_SIZE, ETH_PRICE, MarketSpec.BTC);

        // swap in the BTC liquidation price and nothing else differs: user, round, direction, leverage, size, entry
        assertThat(new Position(eth.userId(), eth.roundId(), eth.direction(), eth.leverage(), eth.size(), eth.entryPrice(),
                btc.liquidationPrice(), eth.pnl())).isEqualTo(btc);
        assertThat(eth.liquidationPrice()).isNotEqualByComparingTo(btc.liquidationPrice());
    }

    @Test
    void openFromMarginSizesTheStakeAsInitialMarginInEveryMarket() {
        BigDecimal margin = new BigDecimal("1500.00");
        for (MarketSpec spec : new MarketSpec[] {MarketSpec.BTC, ETH, MarketSpec.of("SOL", 10)}) {
            Leverage leverage = spec.leverage(spec.maxLeverage());

            Position position = Position.openFromMargin("u", "r", Direction.DOWN, leverage, margin, ETH_PRICE, spec);

            assertThat(position.size()).isEqualTo(PositionSizing.sizeForMargin(margin, leverage, ETH_PRICE));
            assertThat(LiquidationCalculator.initialMargin(position.size(), ETH_PRICE, leverage)).isEqualByComparingTo(margin);
            assertThat(position.liquidationPrice()).isEqualTo(LiquidationCalculator.liquidationPrice(
                    position.size(), ETH_PRICE, leverage, Direction.DOWN.toSide(), spec.maintenanceMarginRate()));
            assertThat(position.isSettled()).isFalse();
        }
    }
}
