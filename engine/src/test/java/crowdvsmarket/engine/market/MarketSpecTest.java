package crowdvsmarket.engine.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.margin.LiquidationCalculator;
import crowdvsmarket.engine.margin.MarginTable;
import crowdvsmarket.engine.margin.Side;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSizing;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link MarketSpec}: the per-market maintenance margin rate {@code l = 1/(2*maxLeverage)}
 * (docs/research/hl_liquidation_math.md section 1.4, Р-69), its validation, and the leverage
 * helpers the server and {@code GameEngine} build on.
 */
class MarketSpecTest {

    private static final MarketSpec ETH = MarketSpec.of("ETH", 25);

    // --- the BTC spec is the old constant, not a lookalike ---

    @Test
    void btcSpecCarriesTheVeryRateOfTheMarginTableAndTheCalculator() {
        assertThat(MarketSpec.BTC_ID).isEqualTo("BTC");
        assertThat(MarketSpec.BTC.id()).isEqualTo(MarketSpec.BTC_ID);
        assertThat(MarketSpec.BTC.maxLeverage()).isEqualTo(40);
        assertThat(MarketSpec.BTC.maintenanceMarginRate().compareTo(MarginTable.BTC.tier(0).maintenanceMarginRate())).isZero();
        assertThat(MarketSpec.BTC.maintenanceMarginRate().compareTo(LiquidationCalculator.MAINTENANCE_MARGIN_RATE)).isZero();
        assertThat(MarketSpec.BTC.maintenanceMarginRate()).isEqualByComparingTo("0.0125");
        assertThat(MarketSpec.BTC.maxLeverage()).isEqualTo(MarginTable.BTC.tier(0).maxLeverage());
    }

    // --- rate = 1 / (2 * maxLeverage) ---

    @ParameterizedTest
    @CsvSource({
            "40, 0.0125",
            "25, 0.02",
            "20, 0.025",
            "10, 0.05",
            "5, 0.1",
            "3, 0.1666666666666666666666666666666667", // 1/6 at 34 significant digits, python decimal prec=34
            "2, 0.25"})
    void rateIsOneOverTwiceTheMaxLeverage(int maxLeverage, String expectedRate) {
        MarketSpec spec = MarketSpec.of("X", maxLeverage);

        assertThat(spec.maintenanceMarginRate()).isEqualByComparingTo(expectedRate);
        assertThat(spec.maxLeverage()).isEqualTo(maxLeverage);
    }

    @Test
    void rateIsComputedExactlyTheWayMarginTableBuildsIt() {
        // Same value AND same scale (isEqualTo, not isEqualByComparingTo) for every legal cap.
        for (int maxLeverage = Leverage.MIN; maxLeverage <= 40; maxLeverage++) {
            BigDecimal fromTable = MarginTable.build(List.of(new MarginTable.TierSpec(new BigDecimal("0.0"), maxLeverage)))
                    .tier(0).maintenanceMarginRate();

            assertThat(MarketSpec.of("X", maxLeverage).maintenanceMarginRate())
                    .as("maxLeverage %d", maxLeverage)
                    .isEqualTo(fromTable);
        }
    }

    // --- validation ---

    @ParameterizedTest
    @ValueSource(strings = {"BTC", "ETH", "sol", "SOL_PERP", "a", "A1_b2", "_", "0", "123456789012345678901234567890AB"})
    void legalIdsAreAccepted(String id) {
        assertThat(MarketSpec.of(id, 10).id()).isEqualTo(id);
        assertThat(MarketSpec.requireValidId(id)).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "ETH ", " ETH", "ET H", "BT-C", "ETH/USD", "eth.p", "ETH!", "ЕТН" /* Cyrillic */, "ETH\n",
            "123456789012345678901234567890ABC" /* 33 chars */})
    void illegalIdsAreRejected(String id) {
        assertThatThrownBy(() -> MarketSpec.of(id, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("market id");
        assertThatThrownBy(() -> new MarketSpec(id, 10, new BigDecimal("0.05")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketSpec.requireValidId(id)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullIdIsABadArgumentLikeEveryOtherIllegalId() {
        // E1: anything that is not [A-Za-z0-9_]{1,32} is an IllegalArgumentException, null included —
        // a caller that catches that around a config read must not be surprised by an NPE.
        assertThatThrownBy(() -> MarketSpec.of(null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("market id must match [A-Za-z0-9_]{1,32}, got null");
        assertThatThrownBy(() -> new MarketSpec(null, 10, new BigDecimal("0.05"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketSpec.requireValidId(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -40, -1, 0, 1, 10_001, 20_000, Integer.MAX_VALUE})
    void maxLeverageOutsideTwoToTenThousandIsRejectedBothWays(int maxLeverage) {
        // of() must fail as a bad argument even for 0, where the rate formula would divide by zero.
        assertThatThrownBy(() -> MarketSpec.of("X", maxLeverage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLeverage");
        assertThatThrownBy(() -> new MarketSpec("X", maxLeverage, new BigDecimal("0.05")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxLeverage");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "-0.01", "-1", "0.5", "0.500", "0.51", "1", "2"})
    void rateMustBeStrictlyBetweenZeroAndOneHalf(String rate) {
        assertThatThrownBy(() -> new MarketSpec("X", 10, new BigDecimal(rate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maintenanceMarginRate");
    }

    @ParameterizedTest
    @CsvSource({
            "10, 0.0001", "10, 0.0125", "10, 0.05", "10, 0.0999999999",
            "40, 0.0125", "40, 0.0249999999", "25, 0.0399", "3, 0.3333", "2, 0.25", "2, 0.4999999999"})
    void ratesUnderOneOverTheMaxLeverageAreAccepted(int maxLeverage, String rate) {
        assertThat(new MarketSpec("X", maxLeverage, new BigDecimal(rate)).maintenanceMarginRate()).isEqualByComparingTo(rate);
    }

    @Test
    void nullRateIsABadArgumentToo() {
        assertThatThrownBy(() -> new MarketSpec("X", 10, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maintenanceMarginRate");
    }

    @ParameterizedTest
    @CsvSource({
            "40, 0.4",        // inside (0, 0.5), yet a 40x long would liquidate at 1.625 x its entry
            "40, 0.025",      // exactly 1/40
            "40, 0.0250000001",
            "25, 0.04",       // exactly 1/25
            "25, 0.2",
            "10, 0.1",        // exactly 1/10
            "10, 0.25",
            "10, 0.4999999999",
            "3, 0.34", "3, 0.3334",
            "2, 0.5"})
    void aRateAtOrAboveOneOverTheMaxLeverageIsRejected(int maxLeverage, String rate) {
        assertThatThrownBy(() -> new MarketSpec("ODD", maxLeverage, new BigDecimal(rate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maintenanceMarginRate");
    }

    @Test
    void theRateBoundIsExactlyWhatKeepsALiquidationPriceOnTheRightSideOfTheEntry() {
        BigDecimal margin = new BigDecimal("1000.00");
        BigDecimal entry = new BigDecimal("100.00");
        // The finding's market: 40x with l = 0.4 passes the (0, 0.5) range but not the new bound ...
        assertThatThrownBy(() -> new MarketSpec("ODD", 40, new BigDecimal("0.4"))).isInstanceOf(IllegalArgumentException.class);
        // ... and without the bound its 40x long would liquidate ABOVE the entry: (1 - 1/40) / (1 - 0.4) x 100 = 162.50
        BigDecimal wrongSide = LiquidationCalculator.liquidationPrice(
                PositionSizing.sizeForMargin(margin, new Leverage(40), entry), entry,
                new Leverage(40), Side.LONG, new BigDecimal("0.4"));
        assertThat(wrongSide).isEqualByComparingTo("162.50");

        // A spec just under the bound stays on the right side at every leverage the market allows.
        for (int maxLeverage : new int[] {2, 3, 10, 25, 40}) {
            BigDecimal justUnder = BigDecimal.ONE.divide(BigDecimal.valueOf(maxLeverage), MathContext.DECIMAL128)
                    .subtract(new BigDecimal("0.0001"));
            MarketSpec spec = new MarketSpec("EDGE", maxLeverage, justUnder);
            for (int leverage = Leverage.MIN; leverage <= maxLeverage; leverage++) {
                Position up = Position.openFromMargin("u", "r", Direction.UP, new Leverage(leverage), margin, entry, spec);
                Position down = Position.openFromMargin("u", "r", Direction.DOWN, new Leverage(leverage), margin, entry, spec);
                assertThat(up.liquidationPrice()).as("long %dx on %dx market", leverage, maxLeverage).isLessThan(entry);
                assertThat(down.liquidationPrice()).as("short %dx on %dx market", leverage, maxLeverage).isGreaterThan(entry);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 10, 20, 25, 40})
    void theBuiltInRuleAlwaysSatisfiesTheBound(int maxLeverage) {
        // of() builds l = 1/(2*maxLeverage), half of 1/maxLeverage: it can never trip the new check
        MarketSpec spec = MarketSpec.of("X", maxLeverage);

        assertThat(spec.maintenanceMarginRate().multiply(BigDecimal.valueOf(maxLeverage))).isLessThan(BigDecimal.ONE);
        assertThat(new MarketSpec("X", maxLeverage, spec.maintenanceMarginRate())).isEqualTo(spec);
    }

    // --- leverage(int) ---

    @Test
    void leverageAcceptsTwoUpToTheMarketCap() {
        assertThat(ETH.leverage(2)).isEqualTo(new Leverage(2));
        assertThat(ETH.leverage(13)).isEqualTo(new Leverage(13));
        assertThat(ETH.leverage(25)).isEqualTo(new Leverage(25));
        assertThat(MarketSpec.BTC.leverage(40)).isEqualTo(new Leverage(40));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, 26, 30, 40, 41, Integer.MAX_VALUE})
    void leverageOutsideTwoToTheMarketCapIsRejected(int value) {
        assertThatThrownBy(() -> ETH.leverage(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("leverage must be in [2, 25] for ETH, got " + value);
    }

    @Test
    void theBtcCapStillRejectsFortyOne() {
        assertThatThrownBy(() -> MarketSpec.BTC.leverage(41))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("leverage must be in [2, 40] for BTC, got 41");
    }

    // --- clampLeverage(int) ---

    @ParameterizedTest
    @CsvSource({
            "-5, 2", "0, 2", "1, 2", "2, 2", "3, 3", "13, 13", "24, 24", "25, 25", "26, 25", "40, 25", "41, 25", "1000, 25",
            "-2147483648, 2", "2147483647, 25"})
    void clampLeveragePullsIntoTwoToTheMarketCap(int value, int expected) {
        assertThat(ETH.clampLeverage(value)).isEqualTo(expected);
    }

    @Test
    void clampLeverageOnBtcIsTheOldTwoToFortyClamp() {
        for (int value = -5; value <= 60; value++) {
            assertThat(MarketSpec.BTC.clampLeverage(value))
                    .as("value %d", value)
                    .isEqualTo(Math.max(Leverage.MIN, Math.min(40, value)));
        }
    }

    // --- value semantics ---

    @Test
    void specsWithTheSameFactsAreEqual() {
        assertThat(MarketSpec.of("ETH", 25)).isEqualTo(ETH).hasSameHashCodeAs(ETH);
        assertThat(MarketSpec.of("ETH", 20)).isNotEqualTo(ETH);
        assertThat(MarketSpec.of("SOL", 25)).isNotEqualTo(ETH);
    }
}
