package crowdvsmarket.bots.signal;

import crowdvsmarket.bots.indicator.Bollinger;
import crowdvsmarket.engine.round.Direction;
import java.util.Optional;

/**
 * Bollinger bounce: a close strictly below the lower band (SMA(n) - k sigma) gives UP, strictly above the upper band
 * DOWN, inside the bands (and on a flat series, where the bands collapse onto the price) no signal.
 */
public final class BollingerBounce implements SignalStrategy {

    private final int n;
    private final double k;

    public BollingerBounce(int n, double k) {
        if (n < 2 || !(k > 0)) {
            throw new IllegalArgumentException("bad Bollinger parameters: n=" + n + " k=" + k);
        }
        this.n = n;
        this.k = k;
    }

    @Override
    public String id() {
        return "bollinger-" + n + "-" + k;
    }

    @Override
    public int warmupBars() {
        return n;
    }

    @Override
    public Optional<Signal> evaluate(MarketView view) {
        if (!warmedUp(view)) {
            return Optional.empty();
        }
        double[] c = view.closes();
        double last = c[c.length - 1];
        Bollinger.Bands bands = Bollinger.last(c, n, k);
        double halfWidth = bands.upper() - bands.middle();
        if (last < bands.lower()) {
            return Optional.of(new Signal(Direction.UP, Math.min(1.0, (bands.lower() - last) / halfWidth)));
        }
        if (last > bands.upper()) {
            return Optional.of(new Signal(Direction.DOWN, Math.min(1.0, (last - bands.upper()) / halfWidth)));
        }
        return Optional.empty();
    }
}
