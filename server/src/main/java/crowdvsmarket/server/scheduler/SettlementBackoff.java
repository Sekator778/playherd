package crowdvsmarket.server.scheduler;

import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.RoundProgress;
import java.util.HashMap;
import java.util.Map;

/**
 * What the scheduler remembers about a window that it could not settle: how many attempts failed, and when
 * the next one may be made — {@code now + min(60 s, 2^k s)} after the k-th failure, so 2 s, 4 s, 8 s, … and a
 * minute at most. Without it a settlement that keeps failing was retried on every tick, and every failure that
 * had touched the engine ran a full rebuild from the event log under the global write lock (review of the
 * core, finding 3: 5.6 µs per event plus 25 ms, a lobby poll answered 503 for 83 % of its calls at 1.8 k
 * events and 99 % at 91 k).
 *
 * <p>One entry per series: the scheduler works on one window of a series at a time (the one its cursor
 * points at), so an entry for any other window is stale — the round was settled, cancelled by the operator or
 * jumped over, or the cursor was re-seeded by a rebuild — and is simply not there any more. The map therefore
 * never holds more than one window per series. The one-hour refund ({@code RoundSchedulerService}, Р-23) is
 * not postponed by a backoff: when an hour has passed since the window's end, the refund is tried at once
 * ({@link #waiting}), and if it fails too it is backed off like any other attempt. A scheduler tick is one
 * thread ({@code concurrentExecution = SKIP}), the methods are synchronized for a test that calls {@code
 * tick} from two. A clock that went backwards past a failure counts as "long ago" (like {@code LogThrottle}):
 * a step back must not hold a window until the clock catches up.
 */
final class SettlementBackoff {

    /** The longest wait between two attempts on one window. */
    static final long MAX_DELAY_MS = 60_000L;

    /** @param failures    failed attempts of this window so far
     *  @param failedAtMs  when the last of them failed
     *  @param refundTried whether the one-hour refund has already been attempted (and failed) */
    record State(long windowStartMs, int failures, long failedAtMs, long nextAttemptAtMs, boolean refundTried) {
    }

    private final Map<RoundProgress.SeriesKey, State> bySeries = new HashMap<>();

    /** {@code 2^failures} seconds, at most {@link #MAX_DELAY_MS}. */
    static long delayMs(int failures) {
        return Math.min(MAX_DELAY_MS, 1000L << Math.min(Math.max(failures, 0), 16));
    }

    /**
     * Whether the next attempt on {@code window} is not due yet at {@code nowMs}. Never for a window that has
     * not failed, and never for the first attempt of the refund once {@code refundDue} (an hour after the
     * window's end): that one is not delayed by the backoff of the attempts before it.
     */
    synchronized boolean waiting(RoundProgress.SeriesKey series, RoundWindow window, long nowMs, boolean refundDue) {
        State state = stateOf(series, window);
        if (state == null) {
            return false;
        }
        if (refundDue && !state.refundTried()) {
            return false;
        }
        return state.failedAtMs() <= nowMs && nowMs < state.nextAttemptAtMs();
    }

    /** An attempt on {@code window} failed at {@code nowMs}: one more failure, and the next attempt later. */
    synchronized State failed(RoundProgress.SeriesKey series, RoundWindow window, long nowMs, boolean wasRefund) {
        State previous = stateOf(series, window);
        int failures = previous == null ? 1 : previous.failures() + 1;
        State next = new State(window.startMs(), failures, nowMs, nowMs + delayMs(failures),
                wasRefund || (previous != null && previous.refundTried()));
        bySeries.put(series, next);
        return next;
    }

    /** The window was settled, cancelled or is not the series' any more: nothing to remember. */
    synchronized void forget(RoundProgress.SeriesKey series) {
        bySeries.remove(series);
    }

    /** Nothing is remembered about any series. */
    synchronized void clear() {
        bySeries.clear();
    }

    /** The state of {@code window}, if it is the window the series' entry is about. */
    synchronized State of(RoundProgress.SeriesKey series, RoundWindow window) {
        return stateOf(series, window);
    }

    private State stateOf(RoundProgress.SeriesKey series, RoundWindow window) {
        State state = bySeries.get(series);
        if (state == null) {
            return null;
        }
        if (state.windowStartMs() != window.startMs()) {
            bySeries.remove(series); // stale: the series has moved on
            return null;
        }
        return state;
    }
}
