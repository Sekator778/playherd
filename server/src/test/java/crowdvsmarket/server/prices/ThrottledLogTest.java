package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/** A source that is down must not fill the log: first failure, then one per interval, one "recovered". */
class ThrottledLogTest {

    /** Collects what the logger is asked to write. */
    private static final class Capture extends Logger {

        final List<String> lines = new ArrayList<>();

        Capture() {
            super("capture");
        }

        @Override
        protected void doLog(Level level, String loggerClassName, Object message, Object[] parameters,
                             Throwable thrown) {
            if (isEnabled(level)) { // the backend, not the facade, filters by level
                lines.add(level + " " + message);
            }
        }

        @Override
        protected void doLogf(Level level, String loggerClassName, String format, Object[] parameters,
                              Throwable thrown) {
            if (isEnabled(level)) {
                lines.add(level + " " + String.format(format, parameters));
            }
        }

        @Override
        public boolean isEnabled(Level level) {
            return level.ordinal() <= Level.INFO.ordinal(); // FATAL, ERROR, WARN, INFO are enabled; DEBUG, TRACE are not
        }
    }

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Capture log = new Capture();
    private final ThrottledLog throttled = new ThrottledLog(log, now::get, 60_000L);

    @Test
    void theFirstFailureIsLoggedTheNextOnesOnlyOncePerInterval() {
        throttled.failure("poll", "poll of X failed", new RuntimeException("timeout"));
        for (int i = 0; i < 19; i++) { // 19 more polls over 57 s
            now.addAndGet(3_000);
            throttled.failure("poll", "poll of X failed", new RuntimeException("timeout"));
        }
        assertThat(log.lines).hasSize(1);

        now.addAndGet(3_000); // 60 s after the first line
        throttled.failure("poll", "poll of X failed", new RuntimeException("timeout"));

        assertThat(log.lines).hasSize(2);
        assertThat(log.lines.get(1)).contains("19 similar failure(s) not logged");
    }

    @Test
    void recoveryIsLoggedOnceAndOnlyAfterAFailure() {
        throttled.success("poll", "poll of X"); // nothing failed: silence
        assertThat(log.lines).isEmpty();

        throttled.failure("poll", "poll of X failed", null);
        throttled.success("poll", "poll of X");
        throttled.success("poll", "poll of X");

        assertThat(log.lines).hasSize(2);
        assertThat(log.lines.get(1)).contains("poll of X recovered");
    }

    @Test
    void keysAreIndependentAndAFailureAfterRecoveryIsLoggedAgainAtOnce() {
        throttled.failure("a", "A failed", null);
        throttled.failure("b", "B failed", null);
        assertThat(log.lines).hasSize(2);

        throttled.success("a", "A");
        now.addAndGet(1_000);
        throttled.failure("a", "A failed again", null);

        assertThat(log.lines).hasSize(4); // recovered + the new failure, inside the interval
        assertThat(log.lines.get(3)).contains("A failed again");
    }
}
