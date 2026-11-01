package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T-3: the raw price of a market reaches the engine as a lot price in [1000, 10000) at listing time
 * ({@code Market.lotExpFor}), 2 engine decimals keep six significant digits, and a candle built from
 * it is one the engine accepts. The prices are the ones the live samples of 2026-10-01 had.
 */
class LotConversionTest {

    static Stream<Arguments> prices() {
        return Stream.of(
                //        raw price   lotExp  engine lot price
                Arguments.of("84647.0", 0, "84647.00"),
                Arguments.of("2694.4", 0, "2694.40"),
                Arguments.of("150.12", 1, "1501.20"),
                Arguments.of("2.5123", 3, "2512.30"),
                Arguments.of("0.094534", 5, "9453.40"),
                Arguments.of("0.005708", 6, "5708.00"),
                Arguments.of("0.5230", 4, "5230.00"));
    }

    @ParameterizedTest
    @MethodSource("prices")
    void lotExponentAndEnginePrice(String raw, int lotExp, String engine) {
        BigDecimal price = new BigDecimal(raw);

        assertThat(Market.lotExpFor(price)).isEqualTo(lotExp);

        Market market = market(lotExp);
        assertThat(market.toEngine(price)).isEqualByComparingTo(engine);
        assertThat(market.toEngine(price).scale()).isEqualTo(2);
        assertThat(market.priceDecimals()).isEqualTo(2 + lotExp);
        // out again with priceDecimals fractional digits, unchanged
        assertThat(market.rawString(market.toEngine(price)))
                .isEqualTo(price.setScale(2 + lotExp, java.math.RoundingMode.HALF_UP).toPlainString());
    }

    @ParameterizedTest
    @MethodSource("prices")
    void aRoundPathOfSuchPricesIsAcceptedByTheEngineCandle(String raw, int lotExp, String engine) {
        Market market = market(lotExp);
        long open = Px.encode(new BigDecimal(raw));
        long up = Px.encode(new BigDecimal(raw).multiply(new BigDecimal("1.01")));
        long down = Px.encode(new BigDecimal(raw).multiply(new BigDecimal("0.99")));
        long[] at = {10_000, 20_000, 30_000};
        long[] px = {up, down, open};

        RoundPath.Folded folded = RoundPath.fold(market, 0, 60_000, 60_000, open, at, px);

        List<Candle> path = folded.candles();
        assertThat(path).isNotEmpty();
        assertThat(path.get(0).open()).isEqualByComparingTo(engine);
        assertThat(path.get(path.size() - 1).close()).isEqualByComparingTo(engine);
        for (Candle candle : path) {
            assertThat(candle.low().signum()).isPositive();
            assertThat(candle.high()).isGreaterThanOrEqualTo(candle.low());
        }
        // six significant digits survive the two engine decimals
        assertThat(path.stream().map(Candle::high).max(BigDecimal::compareTo).orElseThrow().precision())
                .isGreaterThanOrEqualTo(6);
    }

    @ParameterizedTest
    @MethodSource("prices")
    void feedServiceAcceptsThemAtTheirOwnLotExponentAndRefusesAZeroLot(String raw, int lotExp, String engine) {
        Market market = market(lotExp);

        assertThat(PriceFeedService.fitsTheEngine(market, new BigDecimal(raw))).isTrue();
        // a collapse of more than 99.99% would leave a lot price under half a cent
        assertThat(PriceFeedService.fitsTheEngine(market, new BigDecimal(raw).movePointLeft(8))).isFalse();
    }

    private static Market market(int lotExp) {
        Market market = new Market("X", crowdvsmarket.server.markets.MarketKind.PERP, Category.CRYPTO, "X", "X", null,
                null, "hyperliquid", "X", null, 20, lotExp, 1, crowdvsmarket.server.markets.MarketStatus.ACTIVE, 0L, null,
                null, null);
        assertThat(new RoundWindow("X", crowdvsmarket.engine.market.Interval.HOURLY, 0, 60_000).market())
                .isEqualTo(market.id());
        return market;
    }
}
