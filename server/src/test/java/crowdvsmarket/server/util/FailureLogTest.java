package crowdvsmarket.server.util;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.util.FailureLog.Verbosity;
import org.junit.jupiter.api.Test;

/**
 * The rule of {@link FailureLog}: the first failure of a key — a window, a series — gets its stack trace, a later
 * one a single line at most once per period, anything else nothing; a key that works again starts over. The
 * clock is the caller's.
 */
class FailureLogTest {

    private static final long MINUTE = 60_000L;

    @Test
    void theFirstFailureOfAKeyIsTheOneWithTheStackTrace() {
        FailureLog log = new FailureLog(MINUTE);

        assertThat(log.next("round-1", 1_000L)).isEqualTo(Verbosity.STACK_TRACE);
    }

    @Test
    void laterFailuresInsideTheMinuteSayNothingAndOneAMinuteLaterAGetsALineWithoutATrace() {
        FailureLog log = new FailureLog(MINUTE);
        log.next("round-1", 0L);

        assertThat(log.next("round-1", 2_000L)).isEqualTo(Verbosity.NOTHING);
        assertThat(log.next("round-1", 30_000L)).isEqualTo(Verbosity.NOTHING);
        assertThat(log.next("round-1", MINUTE - 1)).isEqualTo(Verbosity.NOTHING);
        assertThat(log.next("round-1", MINUTE)).isEqualTo(Verbosity.LINE);
        assertThat(log.next("round-1", MINUTE + 5_000L)).as("the minute starts over at that line")
                .isEqualTo(Verbosity.NOTHING);
        assertThat(log.next("round-1", 2 * MINUTE)).isEqualTo(Verbosity.LINE);
    }

    @Test
    void everyKeyHasItsOwnTraceAndItsOwnMinute() {
        FailureLog log = new FailureLog(MINUTE);
        log.next("round-1", 0L);

        assertThat(log.next("round-2", 1_000L)).isEqualTo(Verbosity.STACK_TRACE);
        assertThat(log.next("round-1", 1_000L)).isEqualTo(Verbosity.NOTHING);
        assertThat(log.next("round-2", 1_500L)).isEqualTo(Verbosity.NOTHING);
    }

    @Test
    void aKeyThatWorksAgainGetsATraceAgainAtItsNextFailure() {
        FailureLog log = new FailureLog(MINUTE);
        log.next("round-1", 0L);
        log.next("round-1", MINUTE);

        log.clear("round-1");

        assertThat(log.next("round-1", MINUTE + 1)).isEqualTo(Verbosity.STACK_TRACE);
        assertThat(log.next("round-1", MINUTE + 2)).isEqualTo(Verbosity.NOTHING);
    }

    @Test
    void aClockThatWentBackwardsDoesNotSilenceAKeyUntilItCatchesUp() {
        FailureLog log = new FailureLog(MINUTE);
        log.next("round-1", 10 * MINUTE);

        assertThat(log.next("round-1", 5 * MINUTE)).isEqualTo(Verbosity.LINE);
    }

    @Test
    void theNumberOfKeysIsBounded() {
        FailureLog log = new FailureLog(MINUTE);
        for (int i = 0; i < FailureLog.MAX_KEYS + 10; i++) {
            assertThat(log.next("window-" + i, i)).isEqualTo(Verbosity.STACK_TRACE);
        }

        // past the cap the knowledge is dropped wholesale: at worst one more trace, never a failure
        assertThat(log.next("window-0", FailureLog.MAX_KEYS + 11L)).isIn(Verbosity.STACK_TRACE, Verbosity.NOTHING,
                Verbosity.LINE);
    }
}
