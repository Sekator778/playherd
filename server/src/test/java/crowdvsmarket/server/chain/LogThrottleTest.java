package crowdvsmarket.server.chain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LogThrottleTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final LogThrottle throttle = new LogThrottle(60_000, now::get);

    @Test
    void theFirstOccurrenceOfAKeyReportsAtOnceAndTheNextOnesWaitForTheInterval() {
        assertThat(throttle.ready("down")).isTrue();
        now.addAndGet(59_999);
        assertThat(throttle.ready("down")).isFalse();
        now.addAndGet(1);
        assertThat(throttle.ready("down")).isTrue();
        assertThat(throttle.ready("down")).isFalse();
    }

    @Test
    void keysAreIndependent() {
        assertThat(throttle.ready("a")).isTrue();
        assertThat(throttle.ready("b")).isTrue();
        assertThat(throttle.ready("a")).isFalse();
    }

    @Test
    void aConditionThatEndedReportsAtOnceWhenItComesBack() {
        assertThat(throttle.ready("down")).isTrue();
        assertThat(throttle.ready("down")).isFalse();

        throttle.reset("down");

        assertThat(throttle.ready("down")).isTrue();
    }
}
