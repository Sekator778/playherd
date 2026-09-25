package crowdvsmarket.engine.round;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.CandleSeries;
import crowdvsmarket.engine.market.Interval;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reuses the exact day used by {@code PositionSimulatorMultiCandleTest} (24 real hourly candles,
 * documented there), so its known open/close/candle-count double as cross-checks here. */
class CandleSeriesRoundPricesTest {

    private static final long DAY_START_MS = 1_772_409_600_000L; // 2026-03-02T00:00:00Z
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 24 * HOUR_MS;

    @Test
    void hourlyRoundOnHourlySeriesYieldsOneCandlePath() {
        CandleSeries hourly = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1h.csv", Interval.HOURLY);
        CandleSeriesRoundPrices prices = new CandleSeriesRoundPrices(hourly);
        RoundWindow window = new RoundWindow(Interval.HOURLY, DAY_START_MS, DAY_START_MS + HOUR_MS);

        List<Candle> path = prices.path(window);

        assertThat(path).hasSize(1);
        assertThat(prices.openPrice(window)).isEqualByComparingTo(path.get(0).open());
        assertThat(prices.closePrice(window)).isEqualByComparingTo(path.get(0).close());
    }

    @Test
    void dailyRoundOnHourlySeriesYieldsTheFullIntradayPath() {
        CandleSeries hourly = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1h.csv", Interval.HOURLY);
        CandleSeriesRoundPrices prices = new CandleSeriesRoundPrices(hourly);
        RoundWindow window = new RoundWindow(Interval.DAILY, DAY_START_MS, DAY_START_MS + DAY_MS);

        List<Candle> path = prices.path(window);

        assertThat(path).hasSize(24);
        assertThat(prices.openPrice(window)).isEqualByComparingTo(path.get(0).open());
        assertThat(prices.closePrice(window)).isEqualByComparingTo(path.get(23).close());
        assertThat(prices.openPrice(window)).isEqualByComparingTo("65734.00");
    }

    @Test
    void dailyRoundOnDailySeriesYieldsOneCandlePath() {
        CandleSeries daily = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1d.csv", Interval.DAILY);
        CandleSeriesRoundPrices prices = new CandleSeriesRoundPrices(daily);
        RoundWindow window = new RoundWindow(Interval.DAILY, DAY_START_MS, DAY_START_MS + DAY_MS);

        assertThat(prices.path(window)).hasSize(1);
    }

    @Test
    void windowWithNoCandlesThrows() {
        CandleSeries hourly = CandleSeries.loadFromClasspath("/fixtures/hl_btc_1h.csv", Interval.HOURLY);
        CandleSeriesRoundPrices prices = new CandleSeriesRoundPrices(hourly);
        // Far before the fixture's earliest candle (2026-03-01T04:00:00Z, see PROVENANCE.md).
        RoundWindow window = new RoundWindow(Interval.HOURLY, 0L, HOUR_MS);

        assertThatThrownBy(() -> prices.path(window)).isInstanceOf(IllegalStateException.class);
    }
}
