package crowdvsmarket.bots.signal;

import java.util.Optional;

/** Crowd follow: bet with the previous round's majority of this market (today's "Trend Follower"). Silent without one. */
public final class CrowdFollow implements SignalStrategy {

    @Override
    public String id() {
        return "crowd-follow";
    }

    @Override
    public int warmupBars() {
        return 0;
    }

    @Override
    public Optional<Signal> evaluate(MarketView view) {
        return view.previousMajority().map(d -> new Signal(d, 0.5));
    }
}
