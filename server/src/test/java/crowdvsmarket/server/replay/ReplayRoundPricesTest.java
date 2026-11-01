package crowdvsmarket.server.replay;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReplayRoundPricesTest {

    /** N candles, each 1 minute apart, open/close/low/high all equal to {@code 100 + index} so
     *  every field is trivially traceable back to its source index. */
    private static List<Candle> series(int n) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            BigDecimal price = BigDecimal.valueOf(100 + i);
            out.add(new Candle(i * 60_000L, (i + 1) * 60_000L, price, price, price, price, BigDecimal.ZERO, 0));
        }
        return out;
    }

    @Test
    void indexAtOriginIsZero() {
        ReplayRoundPrices prices = new ReplayRoundPrices(series(10), 20.0, 1_000_000L);
        assertThat(prices.indexAt(1_000_000L)).isZero();
    }

    @Test
    void speedupAdvancesOneSourceMinutePerSixtySpeedupSeconds() {
        // speedup=20 -> 60_000ms source / 20 = 3_000ms wall per source minute (task: "one
        // historical minute every 3 s" for HOURLY test-mode rounds).
        long origin = 0L;
        ReplayRoundPrices prices = new ReplayRoundPrices(series(10), 20.0, origin);
        assertThat(prices.indexAt(origin)).isZero();
        assertThat(prices.indexAt(origin + 2_999L)).isZero();
        assertThat(prices.indexAt(origin + 3_000L)).isEqualTo(1);
        assertThat(prices.indexAt(origin + 6_000L)).isEqualTo(2);
    }

    @Test
    void indexWrapsAroundAtTheEndOfTheSeries() {
        ReplayRoundPrices prices = new ReplayRoundPrices(series(5), 20.0, 0L);
        // index 5 source-minutes in -> wraps to 0 (series has 5 candles, indices 0..4)
        assertThat(prices.indexAt(5 * 3_000L)).isEqualTo(0);
        assertThat(prices.indexAt(7 * 3_000L)).isEqualTo(2);
    }

    @Test
    void pathOpenPriceCloseSpanExactlyTheWindow() {
        List<Candle> candles = series(20);
        ReplayRoundPrices prices = new ReplayRoundPrices(candles, 20.0, 0L);
        // 180_000ms window (D-46 hourly test duration) * speedup 20 / 60_000 = 60 source minutes,
        // but our series only has 20 candles -> path wraps and repeats, still exactly 60 entries.
        RoundWindow window = new RoundWindow(Interval.HOURLY, 0L, 180_000L);
        List<Candle> path = prices.path(window);
        assertThat(path).hasSize(60);
        assertThat(prices.openPrice(window)).isEqualByComparingTo(path.get(0).open());
        assertThat(prices.closePrice(window)).isEqualByComparingTo(path.get(path.size() - 1).close());
    }

    @Test
    void shortWindowStillReturnsAtLeastOneCandle() {
        ReplayRoundPrices prices = new ReplayRoundPrices(series(5), 1.0, 0L); // slow speedup
        RoundWindow tiny = new RoundWindow(Interval.HOURLY, 0L, 10L); // far under one source minute
        assertThat(prices.path(tiny)).hasSize(1);
    }

    @Test
    void priceAtMatchesTheCandleTheIndexPointsTo() {
        List<Candle> candles = series(10);
        ReplayRoundPrices prices = new ReplayRoundPrices(candles, 20.0, 0L);
        int idx = prices.indexAt(6_000L);
        assertThat(prices.priceAt(6_000L)).isEqualByComparingTo(candles.get(idx).open());
    }

    @Test
    void rejectsEmptySeriesAndNonPositiveSpeedup() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ReplayRoundPrices(List.of(), 20.0, 0L));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ReplayRoundPrices(series(3), 0.0, 0L));
    }
}
