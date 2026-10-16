package crowdvsmarket.bots.signal;

import crowdvsmarket.engine.round.Direction;
import java.util.Optional;

/** Crowd contrarian: bet against the previous round's majority of this market (today's "Contrarian"). Silent without one. */
public final class CrowdContrarian implements SignalStrategy {

    @Override
    public String id() {
        return "crowd-contrarian";
    }

    @Override
    public int warmupBars() {
        return 0;
    }

    @Override
    public Optional<Signal> evaluate(MarketView view) {
        return view.previousMajority()
                .map(d -> new Signal(d == Direction.UP ? Direction.DOWN : Direction.UP, 0.5));
    }
}
