package crowdvsmarket.server.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link LogThrottle}: at most once per period per key, on the clock the caller passes. */
class LogThrottleTest {

    @Test
    void aKeyIsAllowedOncePerPeriod() {
        LogThrottle throttle = new LogThrottle(60_000L);

        assertThat(throttle.allow("gate", 1_000L)).isTrue();
        assertThat(throttle.allow("gate", 1_001L)).isFalse();
        assertThat(throttle.allow("gate", 60_999L)).isFalse();
        assertThat(throttle.allow("gate", 61_000L)).isTrue();   // exactly one period after the last allowed
        assertThat(throttle.allow("gate", 61_500L)).isFalse();
    }

    @Test
    void keysAreIndependentAndResetMakesTheNextOneAFirstAgain() {
        LogThrottle throttle = new LogThrottle(60_000L);

        assertThat(throttle.allow("a", 0L)).isTrue();
        assertThat(throttle.allow("b", 1L)).isTrue();
        assertThat(throttle.allow("a", 2L)).isFalse();
        throttle.reset("a");
        assertThat(throttle.allow("a", 3L)).isTrue();
    }

    @Test
    void aClockThatWentBackwardsDoesNotSilenceTheKeyUntilItCatchesUp() {
        LogThrottle throttle = new LogThrottle(60_000L);
        assertThat(throttle.allow("a", 1_000_000L)).isTrue();

        assertThat(throttle.allow("a", 5_000L)).isTrue();
        assertThat(throttle.allow("a", 5_001L)).isFalse();
    }

    @Test
    void aNegativePeriodIsRejected() {
        assertThatThrownBy(() -> new LogThrottle(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
