package crowdvsmarket.engine.round;

import crowdvsmarket.engine.market.Interval;

/**
 * Configurable per-{@link Interval} round step length, milliseconds (предложение — additive
 * addition for the Quarkus server's test mode, Р-46: "часовой" round = 3 min, "дневной" = 15 min,
 * instead of the real 3 600 000 / 86 400 000 ms {@link RoundClock#DEFAULT} uses). Carries no
 * behavior of its own beyond {@link #forInterval}; {@link RoundClock}'s overloads that take this
 * are new methods added alongside the original fixed-duration ones (see that class's javadoc) —
 * nothing existing changes shape or behavior.
 */
public record RoundDurations(long hourlyMs, long dailyMs) {

    /** The engine's original fixed durations (real hour / real day) — what every pre-existing
     *  {@code RoundClock} method still uses. */
    public static final RoundDurations DEFAULT = new RoundDurations(3_600_000L, 86_400_000L);

    public RoundDurations {
        if (hourlyMs <= 0 || dailyMs <= 0) {
            throw new IllegalArgumentException("round durations must be positive");
        }
    }

    public long forInterval(Interval interval) {
        return switch (interval) {
            case HOURLY -> hourlyMs;
            case DAILY -> dailyMs;
        };
    }
}
