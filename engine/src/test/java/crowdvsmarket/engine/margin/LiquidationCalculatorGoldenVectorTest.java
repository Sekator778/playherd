package crowdvsmarket.engine.margin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The 20 golden vectors from docs/research/hl_liquidation_math.md section 3 (long/short x
 * leverage {2,3,10,25,40} x entry price {83451.00, 50000.00}, size 0.10000 BTC), reproduced
 * verbatim from the spec's table, including {@code payout_on_liquidation} (product decision D-7,
 * {@code the decision log}: 0 for every vector) and, at the spec's stated
 * {@code close_price} per entry-price group, {@code unrealized_pnl}/{@code payout_at_round_close}.
 */
class LiquidationCalculatorGoldenVectorTest {

    private record GoldenVector(
            Side side,
            BigDecimal entryPrice,
            int leverage,
            BigDecimal sizeBtc,
            BigDecimal isolatedMargin,
            BigDecimal liquidationPrice,
            BigDecimal maintenanceMargin,
            BigDecimal payoutOnLiquidation,
            BigDecimal closePrice,
            BigDecimal unrealizedPnl,
            BigDecimal payoutAtRoundClose) {
    }

    private static List<GoldenVector> vectors() {
        return List.of(
                new GoldenVector(Side.LONG, d("83451.00"), 2, d("0.10000"), d("4172.55"), d("42253.67"), d("104.31"),
                        d("0.00"), d("90000.00"), d("654.90"), d("4827.45")),
                new GoldenVector(Side.LONG, d("83451.00"), 3, d("0.10000"), d("2781.70"), d("56338.23"), d("104.31"),
                        d("0.00"), d("90000.00"), d("654.90"), d("3436.60")),
                new GoldenVector(Side.LONG, d("83451.00"), 10, d("0.10000"), d("834.51"), d("76056.61"), d("104.31"),
                        d("0.00"), d("90000.00"), d("654.90"), d("1489.41")),
                new GoldenVector(Side.LONG, d("83451.00"), 25, d("0.10000"), d("333.80"), d("81127.09"), d("104.31"),
                        d("0.00"), d("90000.00"), d("654.90"), d("988.70")),
                new GoldenVector(Side.LONG, d("83451.00"), 40, d("0.10000"), d("208.63"), d("82394.63"), d("104.31"),
                        d("0.00"), d("90000.00"), d("654.90"), d("863.53")),
                new GoldenVector(Side.SHORT, d("83451.00"), 2, d("0.10000"), d("4172.55"), d("123631.11"), d("104.31"),
                        d("0.00"), d("90000.00"), d("-654.90"), d("3517.65")),
                new GoldenVector(Side.SHORT, d("83451.00"), 3, d("0.10000"), d("2781.70"), d("109894.32"), d("104.31"),
                        d("0.00"), d("90000.00"), d("-654.90"), d("2126.80")),
                new GoldenVector(Side.SHORT, d("83451.00"), 10, d("0.10000"), d("834.51"), d("90662.81"), d("104.31"),
                        d("0.00"), d("90000.00"), d("-654.90"), d("179.61")),
                new GoldenVector(Side.SHORT, d("83451.00"), 25, d("0.10000"), d("333.80"), d("85717.53"), d("104.31"),
                        d("0.00"), d("90000.00"), d("-654.90"), d("0.00")),
                new GoldenVector(Side.SHORT, d("83451.00"), 40, d("0.10000"), d("208.63"), d("84481.28"), d("104.31"),
                        d("0.00"), d("90000.00"), d("-654.90"), d("0.00")),
                new GoldenVector(Side.LONG, d("50000.00"), 2, d("0.10000"), d("2500.00"), d("25316.46"), d("62.50"),
                        d("0.00"), d("45000.00"), d("-500.00"), d("2000.00")),
                new GoldenVector(Side.LONG, d("50000.00"), 3, d("0.10000"), d("1666.67"), d("33755.24"), d("62.50"),
                        d("0.00"), d("45000.00"), d("-500.00"), d("1166.67")),
                new GoldenVector(Side.LONG, d("50000.00"), 10, d("0.10000"), d("500.00"), d("45569.62"), d("62.50"),
                        d("0.00"), d("45000.00"), d("-500.00"), d("0.00")),
                new GoldenVector(Side.LONG, d("50000.00"), 25, d("0.10000"), d("200.00"), d("48607.59"), d("62.50"),
                        d("0.00"), d("45000.00"), d("-500.00"), d("0.00")),
                new GoldenVector(Side.LONG, d("50000.00"), 40, d("0.10000"), d("125.00"), d("49367.09"), d("62.50"),
                        d("0.00"), d("45000.00"), d("-500.00"), d("0.00")),
                new GoldenVector(Side.SHORT, d("50000.00"), 2, d("0.10000"), d("2500.00"), d("74074.07"), d("62.50"),
                        d("0.00"), d("45000.00"), d("500.00"), d("3000.00")),
                new GoldenVector(Side.SHORT, d("50000.00"), 3, d("0.10000"), d("1666.67"), d("65843.65"), d("62.50"),
                        d("0.00"), d("45000.00"), d("500.00"), d("2166.67")),
                new GoldenVector(Side.SHORT, d("50000.00"), 10, d("0.10000"), d("500.00"), d("54320.99"), d("62.50"),
                        d("0.00"), d("45000.00"), d("500.00"), d("1000.00")),
                new GoldenVector(Side.SHORT, d("50000.00"), 25, d("0.10000"), d("200.00"), d("51358.02"), d("62.50"),
                        d("0.00"), d("45000.00"), d("500.00"), d("700.00")),
                new GoldenVector(Side.SHORT, d("50000.00"), 40, d("0.10000"), d("125.00"), d("50617.28"), d("62.50"),
                        d("0.00"), d("45000.00"), d("500.00"), d("625.00")));
    }

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    static Stream<GoldenVector> goldenVectors() {
        return vectors().stream();
    }

    @ParameterizedTest
    @MethodSource("goldenVectors")
    void isolatedMarginMatchesGoldenVector(GoldenVector v) {
        Leverage leverage = new Leverage(v.leverage());
        BigDecimal actual = LiquidationCalculator.initialMargin(v.sizeBtc(), v.entryPrice(), leverage);
        assertThat(actual).isEqualByComparingTo(v.isolatedMargin());
    }

    @ParameterizedTest
    @MethodSource("goldenVectors")
    void liquidationPriceMatchesGoldenVector(GoldenVector v) {
        Leverage leverage = new Leverage(v.leverage());
        BigDecimal actual = LiquidationCalculator.liquidationPrice(v.sizeBtc(), v.entryPrice(), leverage, v.side());
        assertThat(actual).isEqualByComparingTo(v.liquidationPrice());
    }

    @ParameterizedTest
    @MethodSource("goldenVectors")
    void maintenanceMarginMatchesGoldenVector(GoldenVector v) {
        BigDecimal actual = LiquidationCalculator.maintenanceMarginRequired(v.sizeBtc(), v.entryPrice());
        assertThat(actual).isEqualByComparingTo(v.maintenanceMargin());
    }

    /** Product decision D-7 (the decision log): payout_on_liquidation is always 0. */
    @ParameterizedTest
    @MethodSource("goldenVectors")
    void payoutOnLiquidationMatchesGoldenVector(GoldenVector v) {
        BigDecimal actual = LiquidationCalculator.payoutOnLiquidation(v.sizeBtc(), v.entryPrice());
        assertThat(actual).isEqualByComparingTo(v.payoutOnLiquidation());
    }

    @ParameterizedTest
    @MethodSource("goldenVectors")
    void unrealizedPnlAtStatedClosePriceMatchesGoldenVector(GoldenVector v) {
        BigDecimal actual = LiquidationCalculator.unrealizedPnl(v.sizeBtc(), v.entryPrice(), v.side(), v.closePrice());
        assertThat(actual).isEqualByComparingTo(v.unrealizedPnl());
    }

    @ParameterizedTest
    @MethodSource("goldenVectors")
    void payoutAtRoundCloseAtStatedClosePriceMatchesGoldenVector(GoldenVector v) {
        Leverage leverage = new Leverage(v.leverage());
        BigDecimal actual = LiquidationCalculator.payoutAtRoundClose(
                v.sizeBtc(), v.entryPrice(), leverage, v.side(), v.closePrice());
        assertThat(actual).isEqualByComparingTo(v.payoutAtRoundClose());
    }
}
