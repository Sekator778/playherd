package crowdvsmarket.server.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** "Played N days in a row": UTC days with a settled round; a run counts up to today, or up to yesterday while today is not played. */
class PlayDaysTest {

    private static final long TODAY = 20_000;

    private static PlayDays.Result of(Long... daysNewestFirst) {
        return PlayDays.of(List.of(daysNewestFirst), TODAY);
    }

    @Test
    void noRoundsIsACalmZero() {
        assertThat(PlayDays.of(List.of(), TODAY)).isEqualTo(new PlayDays.Result(0, 0));
    }

    @Test
    void aRunThatEndsTodayCounts() {
        assertThat(of(TODAY, TODAY - 1, TODAY - 2)).isEqualTo(new PlayDays.Result(3, 3));
    }

    @Test
    void aRunThatEndsYesterdayStillCountsBecauseTodayIsNotOverYet() {
        assertThat(of(TODAY - 1, TODAY - 2)).isEqualTo(new PlayDays.Result(2, 2));
    }

    @Test
    void aGapOfAWholeDayEndsTheCurrentRunButNotTheBest() {
        assertThat(of(TODAY - 2, TODAY - 3, TODAY - 4, TODAY - 5, TODAY - 6)).isEqualTo(new PlayDays.Result(0, 5));
        // played today, skipped yesterday, five before that: one now, five at best
        assertThat(of(TODAY, TODAY - 2, TODAY - 3, TODAY - 4, TODAY - 5, TODAY - 6)).isEqualTo(new PlayDays.Result(1, 5));
    }

    @Test
    void theBestRunCanBeOlderThanTheCurrentOne() {
        assertThat(of(TODAY, TODAY - 1, TODAY - 5, TODAY - 6, TODAY - 7)).isEqualTo(new PlayDays.Result(2, 3));
    }

    @Test
    void aSingleOldDayIsNotARunNow() {
        assertThat(of(TODAY - 9)).isEqualTo(new PlayDays.Result(0, 1));
    }

    @Test
    void severalRoundsOnOneDayAreOneDay() {
        assertThat(of(TODAY, TODAY, TODAY, TODAY - 1)).isEqualTo(new PlayDays.Result(2, 2));
    }

    @Test
    void theDayIsTheUtcCalendarDay() {
        long dayMs = PlayDays.DAY_MS;
        assertThat(PlayDays.dayOf(0)).isZero();
        assertThat(PlayDays.dayOf(dayMs - 1)).isZero();
        assertThat(PlayDays.dayOf(dayMs)).isEqualTo(1);
        assertThat(PlayDays.dayOf(5 * dayMs + 3_600_000)).isEqualTo(5);
    }
}
