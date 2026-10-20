package crowdvsmarket.server.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The "This week" boards: the window starts Monday 00:00 UTC; the season's minimum of rounds; winners only on the rich list. */
class WeeklyBoardsTest {

    private static long ms(String iso) {
        return Instant.parse(iso).toEpochMilli();
    }

    @Test
    void theWeekStartsOnMondayMidnightUtc() {
        long monday = ms("2026-10-19T00:00:00Z");
        assertThat(WeeklyBoards.weekStartMs(monday)).as("the very first millisecond of Monday").isEqualTo(monday);
        assertThat(WeeklyBoards.weekStartMs(monday - 1)).as("the last millisecond of Sunday is the week before")
                .isEqualTo(ms("2026-10-12T00:00:00Z"));
        assertThat(WeeklyBoards.weekStartMs(ms("2026-10-20T13:45:10Z"))).isEqualTo(monday);
        assertThat(WeeklyBoards.weekStartMs(ms("2026-10-25T23:59:59.999Z"))).as("Sunday evening").isEqualTo(monday);
        assertThat(WeeklyBoards.weekStartMs(ms("2026-10-26T00:00:00Z"))).isEqualTo(ms("2026-10-26T00:00:00Z"));
    }

    @Test
    void theWeekCrossesAMonthAndAYear() {
        assertThat(WeeklyBoards.weekStartMs(ms("2026-11-01T10:00:00Z"))).isEqualTo(ms("2026-10-26T00:00:00Z"));
        assertThat(WeeklyBoards.weekStartMs(ms("2027-01-01T10:00:00Z"))).isEqualTo(ms("2026-12-28T00:00:00Z"));
    }

    private static WeeklyBoards.Totals totals(String id, int rounds, long net, int eligible, int correct) {
        return new WeeklyBoards.Totals(id, rounds, net, eligible, correct);
    }

    @Test
    void theMinimumOfRoundsIsTheSeasonsAndNineRoundsDoNotCount() {
        List<WeeklyBoards.Totals> t = List.of(totals("nine", 9, 5_000, 9, 9), totals("ten", 10, 1_000, 10, 6));
        assertThat(WeeklyBoards.rich(t, 10)).extracting(WeeklyBoards.Rich::playerId).containsExactly("ten");
        assertThat(WeeklyBoards.prophets(t, 10)).extracting(WeeklyBoards.Prophet::playerId).containsExactly("ten");
    }

    @Test
    void theRichListHoldsWinnersOnlyBiggestFirst() {
        List<WeeklyBoards.Totals> t = List.of(totals("small", 12, 500, 12, 6), totals("big", 12, 9_000, 12, 6),
                totals("zero", 12, 0, 12, 6), totals("loser", 40, -7_000, 40, 10));
        assertThat(WeeklyBoards.rich(t, 10)).extracting(WeeklyBoards.Rich::playerId).containsExactly("big", "small");
    }

    @Test
    void theProphetsAreRankedByTheShareOfRightCallsAndAllFlatPlayersAreLeftOut() {
        List<WeeklyBoards.Totals> t = List.of(totals("half", 10, 0, 10, 5), totals("sharp", 10, 0, 8, 7), totals("flat", 10, 0, 0, 0));
        List<WeeklyBoards.Prophet> p = WeeklyBoards.prophets(t, 10);
        assertThat(p).extracting(WeeklyBoards.Prophet::playerId).containsExactly("sharp", "half");
        assertThat(p.get(0).accuracy()).isEqualTo(7.0 / 8.0);
        assertThat(p.get(0).eligible()).isEqualTo(8);
        assertThat(p.get(0).rounds()).isEqualTo(10);
    }

    @Test
    void equalResultsKeepAStableOrder() {
        List<WeeklyBoards.Totals> t = List.of(totals("b", 10, 100, 10, 5), totals("a", 10, 100, 10, 5));
        assertThat(WeeklyBoards.rich(t, 10)).extracting(WeeklyBoards.Rich::playerId).containsExactly("a", "b");
    }
}
