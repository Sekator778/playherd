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
 */
public final class RoundClock {

    private RoundClock() {
    }

    public static long stepMs(Interval interval) {
        return switch (interval) {
            case HOURLY -> 3_600_000L;
            case DAILY -> 86_400_000L;
        };
    }

    /** The round window whose {@code [start,end)} contains {@code epochMs} — the one RUNNING at that instant. */
    public static RoundWindow windowContaining(Interval interval, long epochMs) {
        long step = stepMs(interval);
        long start = Math.floorDiv(epochMs, step) * step;
        return new RoundWindow(interval, start, start + step);
    }

    /** The window immediately after {@code window} — the one open for betting for {@code window}'s
     * whole duration (Р-19). */
    public static RoundWindow next(RoundWindow window) {
        long step = window.endMs() - window.startMs();
        return new RoundWindow(window.interval(), window.endMs(), window.endMs() + step);
    }
}
