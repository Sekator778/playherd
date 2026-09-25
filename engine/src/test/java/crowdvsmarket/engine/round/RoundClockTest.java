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

    // --- предложение additive overloads (RoundDurations/originMs), Р-46 test-mode durations ---

    @Test
    void configurableDurationsDefaultToTheOriginalFixedOnes() {
        RoundDurations real = RoundDurations.DEFAULT;
        assertThat(real.forInterval(Interval.HOURLY)).isEqualTo(3_600_000L);
        assertThat(real.forInterval(Interval.DAILY)).isEqualTo(86_400_000L);
        assertThat(RoundClock.stepMs(Interval.HOURLY, real)).isEqualTo(RoundClock.stepMs(Interval.HOURLY));
    }

    @Test
    void windowContainingWithOriginZeroMatchesTheOriginalOverload() {
        long t = Instant.parse("2026-04-05T13:47:22Z").toEpochMilli();
        RoundWindow original = RoundClock.windowContaining(Interval.HOURLY, t);
        RoundWindow viaOverload = RoundClock.windowContaining(Interval.HOURLY, t, RoundDurations.DEFAULT, 0L);
        assertThat(viaOverload).isEqualTo(original);
    }

    @Test
    void testModeDurationsAlignToServerStartOrigin() {
        RoundDurations testMode = new RoundDurations(180_000L, 900_000L); // Р-46: 3 min / 15 min
        long origin = Instant.parse("2026-04-05T13:47:22.500Z").toEpochMilli(); // arbitrary server start, not epoch-aligned

        RoundWindow atOrigin = RoundClock.windowContaining(Interval.HOURLY, origin, testMode, origin);
        assertThat(atOrigin.startMs()).isEqualTo(origin);
        assertThat(atOrigin.endMs()).isEqualTo(origin + 180_000L);

        RoundWindow twoAndAHalfStepsLater = RoundClock.windowContaining(
                Interval.HOURLY, origin + 450_000L, testMode, origin);
        assertThat(twoAndAHalfStepsLater.startMs()).isEqualTo(origin + 360_000L); // floor(450000/180000)=2
        assertThat(twoAndAHalfStepsLater.endMs()).isEqualTo(origin + 540_000L);

        RoundWindow beforeOrigin = RoundClock.windowContaining(Interval.DAILY, origin - 1L, testMode, origin);
        assertThat(beforeOrigin.startMs()).isEqualTo(origin - 900_000L);
        assertThat(beforeOrigin.endMs()).isEqualTo(origin);
    }

    @Test
    void nextStaysOriginAlignedForCustomDurations() {
        RoundDurations testMode = new RoundDurations(180_000L, 900_000L);
        long origin = 12345L;
        RoundWindow first = RoundClock.windowContaining(Interval.DAILY, origin, testMode, origin);
        RoundWindow second = RoundClock.next(first);
        assertThat(second.startMs()).isEqualTo(first.endMs());
        assertThat(second.endMs() - second.startMs()).isEqualTo(900_000L);
    }
}
