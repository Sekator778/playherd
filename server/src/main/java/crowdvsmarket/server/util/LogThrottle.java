package crowdvsmarket.server.util;

import java.util.HashMap;
import java.util.Map;

/**
 * "At most once per period, per key": keeps a condition that is checked every scheduler tick (a
 * closed oracle gate, a series whose settlement keeps failing, a rebuild that cannot succeed yet)
 * from writing a log line, or a stack trace, every second. The caller passes the clock, so a test
 * that drives time through {@code RoundSchedulerService#tick(now)} drives this too.
 */
public final class LogThrottle {

    private final long periodMs;
    private final Map<String, Long> lastAllowedAtMs = new HashMap<>();

    public LogThrottle(long periodMs) {
        if (periodMs < 0) {
            throw new IllegalArgumentException("periodMs must not be negative: " + periodMs);
        }
        this.periodMs = periodMs;
    }

    /**
     * Whether {@code key} may be logged at {@code nowMs}: the first time, and again once at least
     * the period has passed since the last time it was allowed. A clock that went backwards counts
     * as "long ago" (it must not silence the key until the clock catches up).
     */
    public synchronized boolean allow(String key, long nowMs) {
        Long last = lastAllowedAtMs.get(key);
        if (last == null || nowMs < last || nowMs - last >= periodMs) {
            lastAllowedAtMs.put(key, nowMs);
            return true;
        }
        return false;
    }

    /** Forgets {@code key}: its next {@link #allow} is a first one again (the condition cleared). */
    public synchronized void reset(String key) {
        lastAllowedAtMs.remove(key);
    }
}
