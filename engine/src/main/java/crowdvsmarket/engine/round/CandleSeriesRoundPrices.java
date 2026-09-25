package crowdvsmarket.engine.round;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.CandleSeries;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link RoundPrices} backed by a single {@link CandleSeries}. Works uniformly for both round
 * granularities: an hourly series feeding hourly rounds yields a one-candle path; the same hourly
 * series feeding daily rounds yields the ~24-candle intraday path (doc section 2.3's multi-candle
 * case, already exercised by {@code PositionSimulatorMultiCandleTest}); a daily series feeding
 * daily rounds also works, yielding a one-candle path.
 */
public final class CandleSeriesRoundPrices implements RoundPrices {

    private final CandleSeries series;

    public CandleSeriesRoundPrices(CandleSeries series) {
        this.series = series;
    }

    private List<Candle> candlesIn(RoundWindow window) {
        List<Candle> path = series.candles().stream()
                .filter(c -> c.openTimeMs() >= window.startMs() && c.openTimeMs() < window.endMs())
                .collect(Collectors.toList());
        if (path.isEmpty()) {
            throw new IllegalStateException("no candles for round window " + window.roundId());
        }
        return path;
    }

    @Override
    public BigDecimal openPrice(RoundWindow window) {
        return candlesIn(window).get(0).open();
    }

    @Override
    public BigDecimal closePrice(RoundWindow window) {
        List<Candle> path = candlesIn(window);
        return path.get(path.size() - 1).close();
    }

    @Override
    public List<Candle> path(RoundWindow window) {
        return candlesIn(window);
    }
}
