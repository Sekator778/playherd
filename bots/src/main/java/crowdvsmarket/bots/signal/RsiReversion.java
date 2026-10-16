package crowdvsmarket.bots.signal;

import crowdvsmarket.bots.indicator.Rsi;
import crowdvsmarket.engine.round.Direction;
import java.util.Optional;

/**
 * RSI mean reversion (the idea of Connors' RSI(2) and Wilder's RSI): Wilder RSI(n) below {@code low} means oversold, so
 * UP; above {@code high} overbought, so DOWN; in between no signal. Warm-up is {@code 3n} candles (the seed window plus
 * two more windows of Wilder smoothing so the seed's weight has faded; at least {@code n + 2}).
 */
public final class RsiReversion implements SignalStrategy {

    private final int n;
    private final double low;
    private final double high;

    public RsiReversion(int n, double low, double high) {
        if (n < 1 || !(0 < low && low < high && high < 100)) {
            throw new IllegalArgumentException("bad RSI parameters: n=" + n + " low=" + low + " high=" + high);
        }
        this.n = n;
        this.low = low;
        this.high = high;
    }

    @Override
    public String id() {
        return "rsi-" + n + "-" + trim(low) + "-" + trim(high);
    }

    @Override
    public int warmupBars() {
        return Math.max(3 * n, n + 2);
    }

    @Override
    public Optional<Signal> evaluate(MarketView view) {
        if (!warmedUp(view)) {
            return Optional.empty();
        }
        double rsi = Rsi.last(view.closes(), n);
        if (rsi < low) {
            return Optional.of(new Signal(Direction.UP, Math.min(1.0, (low - rsi) / low)));
        }
        if (rsi > high) {
            return Optional.of(new Signal(Direction.DOWN, Math.min(1.0, (rsi - high) / (100 - high))));
        }
        return Optional.empty();
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
