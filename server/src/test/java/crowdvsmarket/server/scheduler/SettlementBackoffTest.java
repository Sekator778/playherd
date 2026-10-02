package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.RoundProgress;
import org.junit.jupiter.api.Test;

/**
 * {@link SettlementBackoff}: after the k-th failed attempt of a window the next one is {@code min(60 s, 2^k s)}
 * later; a window that has not failed is never waiting; an entry about another window of the series is stale
 * and gone; the refund an hour after the window's end is not delayed by the attempts before it.
 */
class SettlementBackoffTest {

    private static final RoundProgress.SeriesKey SERIES = new RoundProgress.SeriesKey("BTC", Interval.HOURLY);
    private static final RoundWindow W1 = new RoundWindow("BTC", Interval.HOURLY, 60_000L, 120_000L);
    private static final RoundWindow W2 = new RoundWindow("BTC", Interval.HOURLY, 120_000L, 180_000L);

    @Test
    void theDelayDoublesFromTwoSecondsAndStopsAtAMinute() {
        assertThat(SettlementBackoff.delayMs(1)).isEqualTo(2_000L);
        assertThat(SettlementBackoff.delayMs(2)).isEqualTo(4_000L);
        assertThat(SettlementBackoff.delayMs(3)).isEqualTo(8_000L);
        assertThat(SettlementBackoff.delayMs(4)).isEqualTo(16_000L);
        assertThat(SettlementBackoff.delayMs(5)).isEqualTo(32_000L);
        assertThat(SettlementBackoff.delayMs(6)).as("2^6 = 64 s, capped").isEqualTo(60_000L);
        assertThat(SettlementBackoff.delayMs(7)).isEqualTo(60_000L);
        assertThat(SettlementBackoff.delayMs(1_000_000)).as("no overflow however long it fails").isEqualTo(60_000L);
    }

    @Test
    void aWindowThatHasNotFailedIsNeverWaiting() {
        SettlementBackoff backoff = new SettlementBackoff();

        assertThat(backoff.waiting(SERIES, W1, 1_000L, false)).isFalse();
    }

    @Test
    void afterAFailureTheWindowWaitsForTheDelayAndThenIsDue() {
        SettlementBackoff backoff = new SettlementBackoff();
        SettlementBackoff.State first = backoff.failed(SERIES, W1, 10_000L, false);

        assertThat(first.failures()).isEqualTo(1);
        assertThat(first.nextAttemptAtMs()).isEqualTo(12_000L);
        assertThat(backoff.waiting(SERIES, W1, 10_000L, false)).isTrue();
        assertThat(backoff.waiting(SERIES, W1, 11_999L, false)).isTrue();
        assertThat(backoff.waiting(SERIES, W1, 12_000L, false)).isFalse();

        SettlementBackoff.State second = backoff.failed(SERIES, W1, 12_000L, false);
        assertThat(second.failures()).isEqualTo(2);
        assertThat(second.nextAttemptAtMs()).isEqualTo(16_000L);
        assertThat(backoff.waiting(SERIES, W1, 15_999L, false)).isTrue();
    }

    @Test
    void anEntryAboutAnotherWindowOfTheSeriesIsStaleAndDoesNotHoldTheNewOneBack() {
        SettlementBackoff backoff = new SettlementBackoff();
        backoff.failed(SERIES, W1, 10_000L, false);

        assertThat(backoff.waiting(SERIES, W2, 10_000L, false)).as("the series has moved on").isFalse();
        assertThat(backoff.of(SERIES, W1)).as("and the old entry is gone").isNull();
        assertThat(backoff.failed(SERIES, W2, 20_000L, false).failures()).as("W2 starts at its first failure")
                .isEqualTo(1);
    }

    @Test
    void forgettingAWindowEndsItsBackoff() {
        SettlementBackoff backoff = new SettlementBackoff();
        backoff.failed(SERIES, W1, 10_000L, false);

        backoff.forget(SERIES);

        assertThat(backoff.waiting(SERIES, W1, 10_000L, false)).isFalse();
    }

    @Test
    void theFirstRefundAnHourAfterTheEndIsNotHeldBackByTheBackoffButTheSecondOneIs() {
        SettlementBackoff backoff = new SettlementBackoff();
        backoff.failed(SERIES, W1, 10_000L, false);

        assertThat(backoff.waiting(SERIES, W1, 10_500L, true)).as("the refund is due: not delayed").isFalse();

        backoff.failed(SERIES, W1, 10_500L, true); // the refund was tried and failed too

        assertThat(backoff.of(SERIES, W1).refundTried()).isTrue();
        assertThat(backoff.waiting(SERIES, W1, 11_000L, true)).as("a refund that failed is backed off").isTrue();
        assertThat(backoff.waiting(SERIES, W1, 10_500L + SettlementBackoff.delayMs(2), true)).isFalse();
    }

    @Test
    void aClockThatWentBackwardsPastTheFailureDoesNotHoldTheWindowUntilItCatchesUp() {
        SettlementBackoff backoff = new SettlementBackoff();
        backoff.failed(SERIES, W1, 3_600_000L, false);   // failed at one o'clock; the next attempt is due 2 s later

        assertThat(backoff.waiting(SERIES, W1, 3_600_000L, false)).as("at the instant of the failure it waits").isTrue();
        assertThat(backoff.waiting(SERIES, W1, 3_599_999L, false)).as("a millisecond before it: the clock stepped back")
                .isFalse();
        assertThat(backoff.waiting(SERIES, W1, 60_000L, false)).as("an hour before it").isFalse();
    }

    @Test
    void clearingForgetsEverySeries() {
        SettlementBackoff backoff = new SettlementBackoff();
        RoundProgress.SeriesKey daily = new RoundProgress.SeriesKey("BTC", Interval.DAILY);
        RoundWindow dailyWindow = new RoundWindow("BTC", Interval.DAILY, 60_000L, 120_000L);
        backoff.failed(SERIES, W1, 10_000L, false);
        backoff.failed(daily, dailyWindow, 10_000L, false);

        backoff.clear();

        assertThat(backoff.waiting(SERIES, W1, 10_000L, false)).isFalse();
        assertThat(backoff.waiting(daily, dailyWindow, 10_000L, false)).isFalse();
    }

    @Test
    void theSeriesAreIndependent() {
        SettlementBackoff backoff = new SettlementBackoff();
        RoundProgress.SeriesKey daily = new RoundProgress.SeriesKey("BTC", Interval.DAILY);
        backoff.failed(SERIES, W1, 10_000L, false);

        assertThat(backoff.waiting(daily, new RoundWindow("BTC", Interval.DAILY, 60_000L, 120_000L), 10_000L, false))
                .isFalse();
    }
}
