package crowdvsmarket.server.util;

import java.util.HashSet;
import java.util.Set;

/**
 * What to write for a failure that repeats: the first one of a key — a window, a series — with its stack
 * trace, then one line (no trace) at most once per period, and nothing in between ({@link
 * LogThrottle}'s "at most once per period" is the second half). The stack trace of the same failure printed
 * again every attempt tells nothing new and, with 36 series failing at the same time, buries the log; the
 * line keeps saying that it still goes on.
 *
 * <p>A key is forgotten with {@link #clear} when the thing it names works again (or is no more), so its next
 * failure is a first one again. The caller passes the clock, like {@link LogThrottle}'s: a test that drives time
 * through {@code RoundSchedulerService#tick(now)} drives this too. The number of keys that are never cleared
 * (a window that was refunded, a market that closed) is capped: past {@link #MAX_KEYS} the oldest knowledge is
 * dropped wholesale, which at worst prints one more stack trace.
 */
public final class FailureLog {

    /** What the caller writes now. */
    public enum Verbosity {
        /** The first failure of the key: the message with the stack trace. */
        STACK_TRACE,
        /** A later failure, and a period has passed since the last line: the message alone. */
        LINE,
        /** A later failure inside the period: nothing. */
        NOTHING
    }

    static final int MAX_KEYS = 4096;

    private final LogThrottle lines;
    private final Set<String> traced = new HashSet<>();

    public FailureLog(long periodMs) {
        this.lines = new LogThrottle(periodMs);
    }

    /** What to write for a failure of {@code key} at {@code nowMs}. */
    public synchronized Verbosity next(String key, long nowMs) {
        if (traced.size() >= MAX_KEYS) {
            traced.clear();
        }
        if (traced.add(key)) {
            lines.allow(key, nowMs); // the stack trace is this period's line
            return Verbosity.STACK_TRACE;
        }
        return lines.allow(key, nowMs) ? Verbosity.LINE : Verbosity.NOTHING;
    }

    /** {@code key} works again (or is gone): its next failure gets a stack trace again. */
    public synchronized void clear(String key) {
        traced.remove(key);
        lines.reset(key);
    }
}
