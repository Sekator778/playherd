package crowdvsmarket.engine.round;

import crowdvsmarket.engine.market.Interval;

/**
 * One round's fixed time window {@code [startMs, endMs)}, UTC-aligned to {@code interval} (Р-19).
 * Carries no price data — {@link RoundPrices} resolves open/close/path for a window once it is
 * known. Construct via {@link RoundClock}, which guarantees UTC alignment.
 */
public record RoundWindow(Interval interval, long startMs, long endMs) {

    public RoundWindow {
        if (endMs <= startMs) {
            throw new IllegalArgumentException("endMs must be after startMs");
        }
    }

    /** Deterministic id: interval code + UTC-aligned start — stable across re-runs on the same fixtures. */
    public String roundId() {
        return interval.code() + "-" + startMs;
    }
}
