package crowdvsmarket.engine.round;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RoundClockTest {

    @Test
    void hourlyWindowFloorsToUtcHourBoundary() {
        long midHour = Instant.parse("2026-04-05T13:47:22Z").toEpochMilli();
        RoundWindow window = RoundClock.windowContaining(Interval.HOURLY, midHour);

        assertThat(window.startMs()).isEqualTo(Instant.parse("2026-04-05T13:00:00Z").toEpochMilli());
        assertThat(window.endMs()).isEqualTo(Instant.parse("2026-04-05T14:00:00Z").toEpochMilli());
    }

    @Test
    void hourlyWindowIsStableAtExactBoundary() {
        long exactHour = Instant.parse("2026-04-05T13:00:00Z").toEpochMilli();
        RoundWindow window = RoundClock.windowContaining(Interval.HOURLY, exactHour);
        assertThat(window.startMs()).isEqualTo(exactHour);
    }

    @Test
    void dailyWindowFloorsToUtcMidnight() {
        long midDay = Instant.parse("2026-04-05T13:47:22Z").toEpochMilli();
        RoundWindow window = RoundClock.windowContaining(Interval.DAILY, midDay);

        assertThat(window.startMs()).isEqualTo(Instant.parse("2026-04-05T00:00:00Z").toEpochMilli());
        assertThat(window.endMs()).isEqualTo(Instant.parse("2026-04-06T00:00:00Z").toEpochMilli());
    }

    @Test
    void nextIsContiguousWithTheSameDuration() {
        RoundWindow first = RoundClock.windowContaining(Interval.HOURLY, Instant.parse("2026-04-05T13:00:00Z").toEpochMilli());
        RoundWindow second = RoundClock.next(first);

        assertThat(second.startMs()).isEqualTo(first.endMs());
        assertThat(second.endMs() - second.startMs()).isEqualTo(first.endMs() - first.startMs());
        assertThat(second.interval()).isEqualTo(first.interval());
    }

    @Test
    void roundIdIsDeterministicAndIntervalScoped() {
        RoundWindow hourly = RoundClock.windowContaining(Interval.HOURLY, 0L);
        RoundWindow daily = RoundClock.windowContaining(Interval.DAILY, 0L);
        assertThat(hourly.roundId()).isNotEqualTo(daily.roundId());
        assertThat(hourly.roundId()).isEqualTo(RoundClock.windowContaining(Interval.HOURLY, 0L).roundId());
    }
}
