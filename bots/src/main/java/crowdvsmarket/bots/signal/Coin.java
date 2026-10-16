package crowdvsmarket.bots.signal;

import crowdvsmarket.engine.round.Direction;
import java.util.Optional;

/** Coin: a fair flip from the decision's seeded generator (today's "Wildcard"). Always speaks, confidence 0. */
public final class Coin implements SignalStrategy {

    @Override
    public String id() {
        return "coin";
    }

    @Override
    public int warmupBars() {
        return 0;
    }

    @Override
    public Optional<Signal> evaluate(MarketView view) {
        return Optional.of(new Signal(view.rng().nextBoolean() ? Direction.UP : Direction.DOWN, 0.0));
    }
}
