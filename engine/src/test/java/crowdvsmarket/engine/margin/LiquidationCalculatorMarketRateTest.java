package crowdvsmarket.engine.margin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import crowdvsmarket.engine.market.MarketSpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden vectors for the explicit-rate overloads of {@link LiquidationCalculator} (D-69): markets
 * whose leverage cap is 25, 20 and 10 have maintenance margin rates {@code l} 0.02, 0.025 and 0.05
 * ({@code l = 1/(2*maxLeverage)}, docs/research/hl_liquidation_math.md section 1.4), at prices of
 * very different magnitude (2694.40, 150.12, 5230.00), both sides, leverage 2 / mid / max, plus
 * rows that land exactly on a half cent (initial or maintenance margin, where HALF_UP and
 * HALF_EVEN differ) — the formulas and rounding of sections 2.2/2.6 are those of the BTC vectors,
 * only {@code l} changes.
 *
 * <p>Every number below was computed independently of the Java code with python3 {@code decimal}
 * (context precision 34 = {@code MathContext.DECIMAL128}, {@code ROUND_HALF_UP} at the 2dp final
 * step of each formula, same order of operations as the spec; {@code isolated_margin} rounded
 * before it enters {@code liquidation_price}). The script, whose output is the {@code ROWS} list
 * of this class, is:
 *
 * <pre>
 * from decimal import Decimal as D, ROUND_HALF_UP, getcontext
 *
 * getcontext().prec = 34  # Java MathContext.DECIMAL128: 34 digits, intermediates HALF_EVEN
 *
 *
 * def q2(x):
 *     return x.quantize(D("0.01"), rounding=ROUND_HALF_UP)  # the final step of every formula
 *
 *
 * def initial_margin(q, p, lev):
 *     return q2(q * p / D(lev))
 *
 *
 * def maintenance_margin(q, p, l):
 *     return q2(q * p * l)
 *
 *
 * def liquidation_price(q, p, lev, side, l):  # side: +1 long, -1 short
 *     im = initial_margin(q, p, lev)  # rounded to 2dp BEFORE it is used (section 2.6)
 *     return q2((q * p - D(side) * im) / (q * (D(1) - l * D(side))))
 *
 *
 * def equity(q, p, lev, side, px):
 *     return q2(initial_margin(q, p, lev) + q2(D(side) * q * (px - p)))
 *
 *
 * def margin_ratio(q, p, lev, side, px, l):
 *     return equity(q, p, lev, side, px) / maintenance_margin(q, p, l)
 *
 *
 * # (maxLeverage, entry price, size, mark price for the ratio, leverages, note)
 * ROWS = [
 *     (25, "2694.40", "0.50000", "2750.00", (2, 10, 25), ""),
 *     (25, "150.12", "0.66666667", "148.00", (2, 10, 25), ""),
 *     (25, "5230.00", "0.25000", "5300.00", (2, 10, 25), ""),
 *     (25, "2694.50", "0.50000", "2750.00", (25,), "maintenance margin 26.945: a half-cent tie"),
 *     (25, "150.12", "1.00000", "148.00", (8,), "initial margin 18.765: a half-cent tie"),
 *     (20, "2694.40", "0.50000", "2750.00", (2, 8, 20), ""),
 *     (20, "150.12", "0.66666667", "148.00", (2, 8, 20), ""),
 *     (20, "5230.00", "0.25000", "5300.00", (2, 8, 20), ""),
 *     (20, "2694.80", "0.50000", "2750.00", (20,), "maintenance margin 33.685: a half-cent tie"),
 *     (20, "150.12", "1.00000", "148.00", (8,), "initial margin 18.765: a half-cent tie"),
 *     (10, "2694.40", "0.50000", "2750.00", (2, 5, 10), ""),
 *     (10, "150.12", "0.66666667", "148.00", (2, 5, 10), ""),
 *     (10, "5230.00", "0.25000", "5300.00", (2, 5, 10), ""),
 *     (10, "5230.00", "0.75000", "5300.00", (10,), "maintenance margin 196.125: a half-cent tie"),
 *     (10, "150.12", "1.00000", "148.00", (8,), "initial margin 18.765: a half-cent tie"),
 * ]
 *
 * for max_lev, entry, size, mark, levs, note in ROWS:
 *     l = D(1) / (D(2) * D(max_lev))  # 1 / (2 * maxLeverage), section 1.4
 *     if note:
 *         print("// maxLeverage %d, %s" % (max_lev, note))
 *     for name, side in (("LONG", 1), ("SHORT", -1)):
 *         for lev in levs:
 *             p, q, px = D(entry), D(size), D(mark)
 *             print('new Row(%d, "%s", Side.%s, "%s", "%s", %d, "%s", "%s", "%s", "%s", "%s"),' % (
 *                 max_lev, str(l), name, entry, size, lev, mark,
 *                 initial_margin(q, p, lev), maintenance_margin(q, p, l),
 *                 liquidation_price(q, p, lev, side, l), margin_ratio(q, p, lev, side, px, l)))
 * </pre>
 */
class LiquidationCalculatorMarketRateTest {

    /** Spec section 2.2's defining identity holds up to the rounding of the 2dp liquidation price and of the 2dp results. */
    private static final BigDecimal IDENTITY_TOLERANCE = new BigDecimal("0.02");

    private record Row(
            int maxLeverage,
            String rate,
            Side side,
            String entryPrice,
            String size,
            int leverage,
            String markPrice,
            String initialMargin,
            String maintenanceMargin,
            String liquidationPrice,
            String marginRatio) {

        BigDecimal rateValue() {
            return new BigDecimal(rate);
        }

        BigDecimal sizeValue() {
            return new BigDecimal(size);
        }

        BigDecimal entryValue() {
            return new BigDecimal(entryPrice);
        }

        Leverage leverageValue() {
            return new Leverage(leverage);
        }

        @Override
        public String toString() {
            return "maxLeverage=%d %s %s x%d size=%s".formatted(maxLeverage, side, entryPrice, leverage, size);
        }
    }

    private static final List<Row> ROWS = List.of(
                new Row(25, "0.02", Side.LONG, "2694.40", "0.50000", 2, "2750.00", "673.60", "26.94", "1374.69", "26.03563474387527839643652561247216"),
                new Row(25, "0.02", Side.LONG, "2694.40", "0.50000", 10, "2750.00", "134.72", "26.94", "2474.45", "6.032665181885671863400148478099480"),
                new Row(25, "0.02", Side.LONG, "2694.40", "0.50000", 25, "2750.00", "53.89", "26.94", "2639.41", "3.032293986636971046770601336302895"),
                new Row(25, "0.02", Side.SHORT, "2694.40", "0.50000", 2, "2750.00", "673.60", "26.94", "3962.35", "23.97178916109873793615441722345954"),
                new Row(25, "0.02", Side.SHORT, "2694.40", "0.50000", 10, "2750.00", "134.72", "26.94", "2905.73", "3.968819599109131403118040089086860"),
                new Row(25, "0.02", Side.SHORT, "2694.40", "0.50000", 25, "2750.00", "53.89", "26.94", "2747.24", "0.9684484038604305864884929472902747"),
                new Row(25, "0.02", Side.LONG, "150.12", "0.66666667", 2, "148.00", "50.04", "2.00", "76.59", "24.315"),
                new Row(25, "0.02", Side.LONG, "150.12", "0.66666667", 10, "148.00", "10.01", "2.00", "137.86", "4.3"),
                new Row(25, "0.02", Side.LONG, "150.12", "0.66666667", 25, "148.00", "4.00", "2.00", "147.06", "1.295"),
                new Row(25, "0.02", Side.SHORT, "150.12", "0.66666667", 2, "148.00", "50.04", "2.00", "220.76", "25.725"),
                new Row(25, "0.02", Side.SHORT, "150.12", "0.66666667", 10, "148.00", "10.01", "2.00", "161.90", "5.71"),
                new Row(25, "0.02", Side.SHORT, "150.12", "0.66666667", 25, "148.00", "4.00", "2.00", "153.06", "2.705"),
                new Row(25, "0.02", Side.LONG, "5230.00", "0.25000", 2, "5300.00", "653.75", "26.15", "2668.37", "25.66921606118546845124282982791587"),
                new Row(25, "0.02", Side.LONG, "5230.00", "0.25000", 10, "5300.00", "130.75", "26.15", "4803.06", "5.669216061185468451242829827915870"),
                new Row(25, "0.02", Side.LONG, "5230.00", "0.25000", 25, "5300.00", "52.30", "26.15", "5123.27", "2.669216061185468451242829827915870"),
                new Row(25, "0.02", Side.SHORT, "5230.00", "0.25000", 2, "5300.00", "653.75", "26.15", "7691.18", "24.33078393881453154875717017208413"),
                new Row(25, "0.02", Side.SHORT, "5230.00", "0.25000", 10, "5300.00", "130.75", "26.15", "5640.20", "4.330783938814531548757170172084130"),
                new Row(25, "0.02", Side.SHORT, "5230.00", "0.25000", 25, "5300.00", "52.30", "26.15", "5332.55", "1.330783938814531548757170172084130"),
                // maxLeverage 25, maintenance margin 26.945: a half-cent tie
                new Row(25, "0.02", Side.LONG, "2694.50", "0.50000", 25, "2750.00", "53.89", "26.95", "2639.51", "3.029313543599257884972170686456401"),
                new Row(25, "0.02", Side.SHORT, "2694.50", "0.50000", 25, "2750.00", "53.89", "26.95", "2747.33", "0.9699443413729128014842300556586271"),
                // maxLeverage 25, initial margin 18.765: a half-cent tie
                new Row(25, "0.02", Side.LONG, "150.12", "1.00000", 8, "148.00", "18.77", "3.00", "134.03", "5.55"),
                new Row(25, "0.02", Side.SHORT, "150.12", "1.00000", 8, "148.00", "18.77", "3.00", "165.58", "6.963333333333333333333333333333333"),
                new Row(20, "0.025", Side.LONG, "2694.40", "0.50000", 2, "2750.00", "673.60", "33.68", "1381.74", "20.82541567695961995249406175771971"),
                new Row(20, "0.025", Side.LONG, "2694.40", "0.50000", 8, "2750.00", "168.40", "33.68", "2418.05", "5.825415676959619952494061757719715"),
                new Row(20, "0.025", Side.LONG, "2694.40", "0.50000", 20, "2750.00", "67.36", "33.68", "2625.31", "2.825415676959619952494061757719715"),
                new Row(20, "0.025", Side.SHORT, "2694.40", "0.50000", 2, "2750.00", "673.60", "33.68", "3943.02", "19.17458432304038004750593824228029"),
                new Row(20, "0.025", Side.SHORT, "2694.40", "0.50000", 8, "2750.00", "168.40", "33.68", "2957.27", "4.174584323040380047505938242280285"),
                new Row(20, "0.025", Side.SHORT, "2694.40", "0.50000", 20, "2750.00", "67.36", "33.68", "2760.12", "1.174584323040380047505938242280285"),
                new Row(20, "0.025", Side.LONG, "150.12", "0.66666667", 2, "148.00", "50.04", "2.50", "76.98", "19.452"),
                new Row(20, "0.025", Side.LONG, "150.12", "0.66666667", 8, "148.00", "12.51", "2.50", "134.72", "4.44"),
                new Row(20, "0.025", Side.LONG, "150.12", "0.66666667", 20, "148.00", "5.00", "2.50", "146.28", "1.436"),
                new Row(20, "0.025", Side.SHORT, "150.12", "0.66666667", 2, "148.00", "50.04", "2.50", "219.69", "20.58"),
                new Row(20, "0.025", Side.SHORT, "150.12", "0.66666667", 8, "148.00", "12.51", "2.50", "164.77", "5.568"),
                new Row(20, "0.025", Side.SHORT, "150.12", "0.66666667", 20, "148.00", "5.00", "2.50", "153.78", "2.564"),
                new Row(20, "0.025", Side.LONG, "5230.00", "0.25000", 2, "5300.00", "653.75", "32.69", "2682.05", "20.53380238605078005506271030896299"),
                new Row(20, "0.025", Side.LONG, "5230.00", "0.25000", 8, "5300.00", "163.44", "32.69", "4693.58", "5.535026001835423676965432854083818"),
                new Row(20, "0.025", Side.LONG, "5230.00", "0.25000", 20, "5300.00", "65.38", "32.69", "5095.88", "2.535331905781584582441113490364026"),
                new Row(20, "0.025", Side.SHORT, "5230.00", "0.25000", 2, "5300.00", "653.75", "32.69", "7653.66", "19.46313857448761089018048332823493"),
                new Row(20, "0.025", Side.SHORT, "5230.00", "0.25000", 8, "5300.00", "163.44", "32.69", "5740.25", "4.464362190272254512083205873355766"),
                new Row(20, "0.025", Side.SHORT, "5230.00", "0.25000", 20, "5300.00", "65.38", "32.69", "5357.58", "1.464668094218415417558886509635974"),
                // maxLeverage 20, maintenance margin 33.685: a half-cent tie
                new Row(20, "0.025", Side.LONG, "2694.80", "0.50000", 20, "2750.00", "67.37", "33.69", "2625.70", "2.818937370139507272187592757494806"),
                new Row(20, "0.025", Side.SHORT, "2694.80", "0.50000", 20, "2750.00", "67.37", "33.69", "2760.53", "1.180468981893737013950727218759276"),
                // maxLeverage 20, initial margin 18.765: a half-cent tie
                new Row(20, "0.025", Side.LONG, "150.12", "1.00000", 8, "148.00", "18.77", "3.75", "134.72", "4.44"),
                new Row(20, "0.025", Side.SHORT, "150.12", "1.00000", 8, "148.00", "18.77", "3.75", "164.77", "5.570666666666666666666666666666667"),
                new Row(10, "0.05", Side.LONG, "2694.40", "0.50000", 2, "2750.00", "673.60", "67.36", "1418.11", "10.41270783847980997624703087885986"),
                new Row(10, "0.05", Side.LONG, "2694.40", "0.50000", 5, "2750.00", "269.44", "67.36", "2268.97", "4.412707838479809976247030878859857"),
                new Row(10, "0.05", Side.LONG, "2694.40", "0.50000", 10, "2750.00", "134.72", "67.36", "2552.59", "2.412707838479809976247030878859857"),
                new Row(10, "0.05", Side.SHORT, "2694.40", "0.50000", 2, "2750.00", "673.60", "67.36", "3849.14", "9.587292161520190023752969121140143"),
                new Row(10, "0.05", Side.SHORT, "2694.40", "0.50000", 5, "2750.00", "269.44", "67.36", "3079.31", "3.587292161520190023752969121140143"),
                new Row(10, "0.05", Side.SHORT, "2694.40", "0.50000", 10, "2750.00", "134.72", "67.36", "2822.70", "1.587292161520190023752969121140143"),
                new Row(10, "0.05", Side.LONG, "150.12", "0.66666667", 2, "148.00", "50.04", "5.00", "79.01", "9.726"),
                new Row(10, "0.05", Side.LONG, "150.12", "0.66666667", 5, "148.00", "20.02", "5.00", "126.41", "3.722"),
                new Row(10, "0.05", Side.LONG, "150.12", "0.66666667", 10, "148.00", "10.01", "5.00", "142.22", "1.72"),
                new Row(10, "0.05", Side.SHORT, "150.12", "0.66666667", 2, "148.00", "50.04", "5.00", "214.46", "10.29"),
                new Row(10, "0.05", Side.SHORT, "150.12", "0.66666667", 5, "148.00", "20.02", "5.00", "171.57", "4.286"),
                new Row(10, "0.05", Side.SHORT, "150.12", "0.66666667", 10, "148.00", "10.01", "5.00", "157.27", "2.284"),
                new Row(10, "0.05", Side.LONG, "5230.00", "0.25000", 2, "5300.00", "653.75", "65.38", "2752.63", "10.26690119302539002753135515448149"),
                new Row(10, "0.05", Side.LONG, "5230.00", "0.25000", 5, "5300.00", "261.50", "65.38", "4404.21", "4.267360048944631385744876108901805"),
                new Row(10, "0.05", Side.LONG, "5230.00", "0.25000", 10, "5300.00", "130.75", "65.38", "4954.74", "2.267513000917711838482716427041909"),
                new Row(10, "0.05", Side.SHORT, "5230.00", "0.25000", 2, "5300.00", "653.75", "65.38", "7471.43", "9.731569287243805445090241664117467"),
                new Row(10, "0.05", Side.SHORT, "5230.00", "0.25000", 5, "5300.00", "261.50", "65.38", "5977.14", "3.732028143163046803303762618537779"),
                new Row(10, "0.05", Side.SHORT, "5230.00", "0.25000", 10, "5300.00", "130.75", "65.38", "5479.05", "1.732181095136127256041602936677883"),
                // maxLeverage 10, maintenance margin 196.125: a half-cent tie
                new Row(10, "0.05", Side.LONG, "5230.00", "0.75000", 10, "5300.00", "392.25", "196.13", "4954.74", "2.267628613674603579258654973741906"),
                new Row(10, "0.05", Side.SHORT, "5230.00", "0.75000", 10, "5300.00", "392.25", "196.13", "5479.05", "1.732269413144343037781063580278387"),
                // maxLeverage 10, initial margin 18.765: a half-cent tie
                new Row(10, "0.05", Side.LONG, "150.12", "1.00000", 8, "148.00", "18.77", "7.51", "138.26", "2.217043941411451398135818908122503"),
                new Row(10, "0.05", Side.SHORT, "150.12", "1.00000", 8, "148.00", "18.77", "7.51", "160.85", "2.781624500665778961384820239680426"));

    static Stream<Row> rows() {
        return ROWS.stream();
    }

    @Test
    void thereAreAtLeastEightRowsPerMarketAndBothSidesAreCovered() {
        for (int maxLeverage : new int[] {25, 20, 10}) {
            List<Row> market = ROWS.stream().filter(r -> r.maxLeverage() == maxLeverage).toList();
            assertThat(market).as("rows for maxLeverage %d", maxLeverage).hasSizeGreaterThanOrEqualTo(8);
            assertThat(market).extracting(Row::side).contains(Side.LONG, Side.SHORT);
            assertThat(market).extracting(Row::leverage).contains(2, maxLeverage);
        }
    }

    @ParameterizedTest
    @MethodSource("rows")
    void theRowRateIsTheTierZeroRuleOfItsMaxLeverage(Row row) {
        assertThat(MarketSpec.of("X", row.maxLeverage()).maintenanceMarginRate()).isEqualByComparingTo(row.rateValue());
    }

    @ParameterizedTest
    @MethodSource("rows")
    void initialMarginMatches(Row row) {
        assertThat(LiquidationCalculator.initialMargin(row.sizeValue(), row.entryValue(), row.leverageValue()))
                .isEqualByComparingTo(row.initialMargin());
    }

    @ParameterizedTest
    @MethodSource("rows")
    void maintenanceMarginRequiredMatches(Row row) {
        assertThat(LiquidationCalculator.maintenanceMarginRequired(row.sizeValue(), row.entryValue(), row.rateValue()))
                .isEqualByComparingTo(row.maintenanceMargin());
    }

    @ParameterizedTest
    @MethodSource("rows")
    void liquidationPriceMatches(Row row) {
        assertThat(LiquidationCalculator.liquidationPrice(
                        row.sizeValue(), row.entryValue(), row.leverageValue(), row.side(), row.rateValue()))
                .isEqualByComparingTo(row.liquidationPrice());
    }

    @ParameterizedTest
    @MethodSource("rows")
    void marginRatioMatches(Row row) {
        assertThat(LiquidationCalculator.marginRatio(
                        row.sizeValue(), row.entryValue(), row.leverageValue(), row.side(),
                        new BigDecimal(row.markPrice()), row.rateValue()))
                .isEqualByComparingTo(row.marginRatio());
    }

    /**
     * Equity at the liquidation price equals the maintenance margin at that same price,
     * {@code Q*liq_price*l} — the equation the derivation of {@code liquidation_price} solves (spec
     * section 2.2). Not {@link LiquidationCalculator#maintenanceMarginRequired}, the entry-price
     * figure {@code Q*P0*l}: the two coincide only when {@code liq_price == P0}, see
     * {@code LiquidationCalculatorInvariantTest}. Row sizes are at most 1, which keeps the error from
     * rounding {@code liq_price} to a cent (at most {@code 0.005*Q*(1+l)}) plus two rounded results
     * (0.01) inside the 0.02 tolerance.
     */
    @ParameterizedTest
    @MethodSource("rows")
    void equityAtTheLiquidationPriceIsTheMaintenanceMarginAtThatPrice(Row row) {
        BigDecimal liquidationPrice = LiquidationCalculator.liquidationPrice(
                row.sizeValue(), row.entryValue(), row.leverageValue(), row.side(), row.rateValue());
        BigDecimal equity = LiquidationCalculator.equityAt(
                row.sizeValue(), row.entryValue(), row.leverageValue(), row.side(), liquidationPrice);
        BigDecimal maintenanceMarginAtLiquidationPrice = row.sizeValue().multiply(liquidationPrice)
                .multiply(row.rateValue()).setScale(2, RoundingMode.HALF_UP);

        assertThat(equity).isCloseTo(maintenanceMarginAtLiquidationPrice, within(IDENTITY_TOLERANCE));
    }

    @ParameterizedTest
    @MethodSource("rows")
    void theRateLessOverloadsAreExactlyTheBtcRateOverloads(Row row) {
        BigDecimal size = row.sizeValue();
        BigDecimal entry = row.entryValue();
        BigDecimal mark = new BigDecimal(row.markPrice());
        BigDecimal btcRate = LiquidationCalculator.MAINTENANCE_MARGIN_RATE;

        assertThat(LiquidationCalculator.maintenanceMarginRequired(size, entry))
                .isEqualTo(LiquidationCalculator.maintenanceMarginRequired(size, entry, btcRate));
        assertThat(LiquidationCalculator.liquidationPrice(size, entry, row.leverageValue(), row.side()))
                .isEqualTo(LiquidationCalculator.liquidationPrice(size, entry, row.leverageValue(), row.side(), btcRate));
        assertThat(LiquidationCalculator.marginRatio(size, entry, row.leverageValue(), row.side(), mark))
                .isEqualTo(LiquidationCalculator.marginRatio(size, entry, row.leverageValue(), row.side(), mark, btcRate));
        // and MarketSpec.BTC carries that very rate
        assertThat(LiquidationCalculator.liquidationPrice(size, entry, row.leverageValue(), row.side(), MarketSpec.BTC.maintenanceMarginRate()))
                .isEqualTo(LiquidationCalculator.liquidationPrice(size, entry, row.leverageValue(), row.side()));
    }

    @ParameterizedTest
    @MethodSource("rows")
    void aHigherRateMovesTheLiquidationPriceTowardTheEntry(Row row) {
        BigDecimal previous = null;
        for (String rate : new String[] {"0.0125", "0.02", "0.025", "0.05"}) {
            BigDecimal liquidationPrice = LiquidationCalculator.liquidationPrice(
                    row.sizeValue(), row.entryValue(), row.leverageValue(), row.side(), new BigDecimal(rate));
            if (previous != null) {
                if (row.side() == Side.LONG) {
                    assertThat(liquidationPrice).as("long liq price at l=%s", rate).isGreaterThan(previous);
                } else {
                    assertThat(liquidationPrice).as("short liq price at l=%s", rate).isLessThan(previous);
                }
            }
            previous = liquidationPrice;
        }
    }
}
