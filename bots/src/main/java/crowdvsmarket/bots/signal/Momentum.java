package crowdvsmarket.bots.signal;

import crowdvsmarket.engine.round.Direction;
import java.util.Optional;

/**
 * Momentum: the sign of the price change over the last {@code n} candles, followed. {@code close[last] - close[last-n]}
 * above zero gives UP, below zero DOWN, exactly zero (a flat stretch) gives no signal. Confidence grows with the size of
 * the move relative to {@code 0.1% * sqrt(n)} (a proposal-level scale).
 */
public final class Momentum implements SignalStrategy {

    private final int n;

    public Momentum(int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be positive: " + n);
        }
        this.n = n;
    }

    @Override
    public String id() {
        return "momentum-" + n;
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
        double[] c = view.closes();
        double change = c[c.length - 1] - c[c.length - 1 - n];
        if (change == 0.0) {
            return Optional.empty();
        }
        double ret = Math.abs(change) / c[c.length - 1 - n];
        double confidence = Math.min(1.0, ret / (0.001 * Math.sqrt(n)));
        return Optional.of(new Signal(change > 0 ? Direction.UP : Direction.DOWN, confidence));
    }
}
