package crowdvsmarket.bots.signal;

import java.util.Optional;

/**
 * A rule that answers "which way?" from what is visible on a market. Pure: the same {@link MarketView} gives the same
 * answer (randomness only through {@link MarketView#rng()}). Silent ({@code Optional.empty()}) when the history is
 * shorter than {@link #warmupBars()} or the rule has nothing to say.
 */
public interface SignalStrategy {

    /** Stable id such as {@code momentum-3}. */
    String id();

    /** Contiguous candles needed before the rule may speak. */
    int warmupBars();

    Optional<Signal> evaluate(MarketView view);

    /** Whether the history is long enough. */
    default boolean warmedUp(MarketView view) {
        return view.size() >= warmupBars();
    }
}
