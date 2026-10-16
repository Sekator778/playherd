package crowdvsmarket.bots.signal;

import crowdvsmarket.bots.indicator.Ema;
import crowdvsmarket.engine.round.Direction;
import java.util.Optional;

/**
 * EMA cross: the fast EMA above the slow EMA gives UP, below DOWN, equal (a flat series) no signal. Warm-up is twice
 * the slow period so the slow EMA is past its SMA seed. MACD is this same difference of EMAs and is not a separate rule.
 */
public final class EmaCross implements SignalStrategy {

    private final int fast;
    private final int slow;

    public EmaCross(int fast, int slow) {
        if (fast < 1 || slow <= fast) {
            throw new IllegalArgumentException("need 1 <= fast < slow, got " + fast + "/" + slow);
        }
        this.fast = fast;
        this.slow = slow;
    }

    @Override
    public String id() {
        return "ema-" + fast + "-" + slow;
    }

    @Override
    public int warmupBars() {
        return 2 * slow;
    }

    @Override
    public Optional<Signal> evaluate(MarketView view) {
        if (!warmedUp(view)) {
            return Optional.empty();
        }
        double[] c = view.closes();
        double f = Ema.last(c, fast);
        double s = Ema.last(c, slow);
        double gap = (f - s) / s;
        if (Math.abs(gap) < 1e-12) {
            return Optional.empty();
        }
        double confidence = Math.min(1.0, Math.abs(gap) / 0.002);
        return Optional.of(new Signal(gap > 0 ? Direction.UP : Direction.DOWN, confidence));
    }
}
