package crowdvsmarket.bots.signal;

import crowdvsmarket.bots.indicator.Donchian;
import crowdvsmarket.engine.round.Direction;
import java.util.Optional;

/**
 * Donchian breakout (the turtle-style channel rule): the last close above the highest high of the {@code n} candles
 * before it gives UP, below their lowest low DOWN, inside the channel no signal. The last candle itself is not part
 * of the channel it breaks.
 */
public final class DonchianBreakout implements SignalStrategy {

    private final int n;

    public DonchianBreakout(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be positive: " + n);
        }
        this.n = n;
    }

    @Override
    public String id() {
        return "donchian-" + n;
    }

    @Override
    public int warmupBars() {
        return n + 1;
    }

    @Override
    public Optional<Signal> evaluate(MarketView view) {
        if (!warmedUp(view)) {
            return Optional.empty();
        }
        double[] closes = view.closes();
        double[] highs = view.highs();
        double[] lows = view.lows();
        int last = closes.length - 1;
        double top = Donchian.highest(highs, last - n, last);
        double bottom = Donchian.lowest(lows, last - n, last);
        double width = top - bottom;
        double c = closes[last];
        if (c > top) {
            return Optional.of(new Signal(Direction.UP, width > 0 ? Math.min(1.0, (c - top) / width) : 1.0));
        }
        if (c < bottom) {
            return Optional.of(new Signal(Direction.DOWN, width > 0 ? Math.min(1.0, (bottom - c) / width) : 1.0));
        }
        return Optional.empty();
    }
}
