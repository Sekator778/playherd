package crowdvsmarket.engine.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sanity checks that the CSV fixtures load as documented in
 * engine/src/test/resources/fixtures/PROVENANCE.md (row counts, first/last candle timestamps).
 */
class CandleSeriesTest {

    @Test
    void loadsDailyFixtureAsDocumented() {
        CandleSeries series = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1d.csv", Interval.DAILY);
        List<Candle> candles = series.candles();

        assertThat(series.size()).isEqualTo(2228);
        assertThat(candles.get(0).openTimeMs()).isEqualTo(1597795200000L); // 2020-08-19T00:00:00Z
        assertThat(candles.get(candles.size() - 1).openTimeMs()).isEqualTo(1790208000000L); // 2026-09-24T00:00:00Z
    }

    @Test
    void loadsHourlyFixtureAsDocumented() {
        CandleSeries series = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1h.csv", Interval.HOURLY);
        List<Candle> candles = series.candles();

        assertThat(series.size()).isEqualTo(5002);
        assertThat(candles.get(0).openTimeMs()).isEqualTo(1772337600000L); // 2026-03-01T04:00:00Z
    }

    @Test
    void candlesAreInAscendingTimeOrder() {
        CandleSeries series = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1d.csv", Interval.DAILY);
        List<Candle> candles = series.candles();
        for (int i = 1; i < candles.size(); i++) {
            assertThat(candles.get(i).openTimeMs()).isGreaterThan(candles.get(i - 1).openTimeMs());
        }
    }

    @Test
    void ohlcInvariantsHoldForEveryCandle() {
        for (String resource : List.of("/fixtures/hl_btc_1d.csv", "/fixtures/hl_btc_1h.csv")) {
            CandleSeries series = CandleSeries.loadFromClasspath(resource, Interval.DAILY);
            for (Candle candle : series.candles()) {
                assertThat(candle.low()).isLessThanOrEqualTo(candle.open());
                assertThat(candle.low()).isLessThanOrEqualTo(candle.close());
                assertThat(candle.high()).isGreaterThanOrEqualTo(candle.open());
                assertThat(candle.high()).isGreaterThanOrEqualTo(candle.close());
                assertThat(candle.volume()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            }
        }
    }
}
