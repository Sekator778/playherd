package crowdvsmarket.server.chain;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * At most one log line per key per interval, for a condition that persists and is looked at every
 * few seconds — a chain service that stays unreachable, an old one that never reports the seq floor.
 * The first occurrence of a key reports at once; the same key is then silent until
 * {@code intervalMs} has passed, or until {@link #reset} says the condition went away (so its next
 * occurrence is reported at once again).
 */
public final class LogThrottle {

    private final long intervalMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Long> lastReportedAtMs = new ConcurrentHashMap<>();

    public LogThrottle(long intervalMs) {
        this(intervalMs, System::currentTimeMillis);
    }

    LogThrottle(long intervalMs, LongSupplier clock) {
        this.intervalMs = intervalMs;
        this.clock = clock;
    }

    /** True when the caller should log {@code key} now; records that it did. */
    public boolean ready(String key) {
        long now = clock.getAsLong();
        boolean[] report = {false};
        lastReportedAtMs.compute(key, (k, last) -> {
            if (last == null || now - last >= intervalMs) {
                report[0] = true;
                return now;
            }
            return last;
        });
        return report[0];
    }

    /** The condition behind {@code key} is over: its next occurrence reports at once. */
    public void reset(String key) {
        lastReportedAtMs.remove(key);
    }
}
