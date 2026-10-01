package crowdvsmarket.server.prices;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * Keeps a source that is down from filling the log: a poll fails every 3 s, so the first failure of
 * a kind is logged, the next ones only once per {@code intervalMs} (with how many were skipped),
 * and the first success after failures is logged once as "recovered".
 */
final class ThrottledLog {

    private static final class State {
        boolean failing;
        long lastLoggedAtMs;
        int suppressed;
    }

    private final Logger log;
    private final LongSupplier clock;
    private final long intervalMs;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    ThrottledLog(Logger log, LongSupplier clock, long intervalMs) {
        this.log = log;
        this.clock = clock;
        this.intervalMs = intervalMs;
    }

    void failure(String key, String what, Throwable cause) {
        long now = clock.getAsLong();
        State state = states.computeIfAbsent(key, k -> new State());
        synchronized (state) {
            if (state.failing && now - state.lastLoggedAtMs < intervalMs) {
                state.suppressed++;
                return;
            }
            String suffix = state.suppressed > 0 ? " (" + state.suppressed + " similar failure(s) not logged)" : "";
            if (cause == null) {
                log.warn(what + suffix);
            } else {
                log.warnf("%s%s: %s", what, suffix, cause.toString());
                log.debug("stack trace of the failure above", cause);
            }
            state.failing = true;
            state.lastLoggedAtMs = now;
            state.suppressed = 0;
        }
    }

    void success(String key, String what) {
        State state = states.get(key);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.failing) {
                log.infof("%s recovered", what);
                state.failing = false;
                state.suppressed = 0;
            }
        }
    }
}
