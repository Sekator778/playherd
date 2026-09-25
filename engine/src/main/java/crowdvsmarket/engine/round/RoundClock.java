package crowdvsmarket.engine.round;

import crowdvsmarket.engine.market.Interval;

/**
 * Pure UTC round-boundary arithmetic (Р-19: hourly rounds on UTC hour boundaries, daily on
 * 00:00 UTC — предложение: aligned with Hyperliquid candle boundaries, see
 * docs/research/round_engine_design.md). Epoch milliseconds are already UTC, and both step sizes
 * (3 600 000 ms, 86 400 000 ms) divide evenly into a UTC day — flooring is exact, with no
 * calendar/DST handling needed.
 *
 * <p>Р-19 also fixes the scheduling invariant this class encodes: "always one running + one open
 * [for betting] round per series" — the round open for betting is always exactly the one
 * immediately after the currently running one ({@link #next}), matching "betting window for round
 * N = the whole duration of round N-1".
 *
 * <p><b>Предложение (additive):</b> {@link #stepMs(Interval, RoundDurations)} and
 * {@link #windowContaining(Interval, long, RoundDurations, long)} let a caller supply a
 * non-default {@link RoundDurations} (e.g. the Quarkus server's Р-46 test-mode durations) and an
 * {@code originMs} the floor arithmetic is relative to (e.g. the server's own start time, rather
 * than the Unix epoch) — see the server's design doc for why. The original no-arg-config methods
 * below are unchanged and are exactly {@code stepMs(interval, RoundDurations.DEFAULT)} /
 * {@code windowContaining(interval, epochMs, RoundDurations.DEFAULT, 0L)}.
 */
public final class RoundClock {

    private RoundClock() {
    }

    public static long stepMs(Interval interval) {
        return stepMs(interval, RoundDurations.DEFAULT);
    }

    /** Predложение overload — see class javadoc. */
    public static long stepMs(Interval interval, RoundDurations durations) {
        return durations.forInterval(interval);
    }

    /** The round window whose {@code [start,end)} contains {@code epochMs} — the one RUNNING at that instant. */
    public static RoundWindow windowContaining(Interval interval, long epochMs) {
        return windowContaining(interval, epochMs, RoundDurations.DEFAULT, 0L);
    }

    /**
     * Предложение overload — see class javadoc. {@code originMs} is a fixed reference instant
     * that a window boundary always lands exactly on (typically {@code originMs} itself, if it is
     * ever exactly on a window start); floors {@code epochMs} to the nearest {@code originMs +
     * k*step} at or before it.
     */
    public static RoundWindow windowContaining(Interval interval, long epochMs, RoundDurations durations, long originMs) {
        long step = stepMs(interval, durations);
        long offset = epochMs - originMs;
        long start = originMs + Math.floorDiv(offset, step) * step;
        return new RoundWindow(interval, start, start + step);
    }

    /** The window immediately after {@code window} — the one open for betting for {@code window}'s
     * whole duration (Р-19). */
    public static RoundWindow next(RoundWindow window) {
        long step = window.endMs() - window.startMs();
        return new RoundWindow(window.interval(), window.endMs(), window.endMs() + step);
    }
}
