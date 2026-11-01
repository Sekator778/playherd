package crowdvsmarket.server.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The "This week" boards of the ratings (docs/research/features_growth_2026-10-18.md §3 item 3): the same two rankings as the
 * season's, over the settled rounds of the window Monday 00:00 UTC to now.
 * <ul>
 *   <li>the same minimum of rounds as the season (D-28): fewer settled rounds in the window and the player is in no list;
 *   <li>"богачи": the week's result is the sum of {@code payout - stake - fees} of the player's settled rounds, and only a player
 *       above zero is listed (D-90: the showcase shows winners, a loss is seen by its owner only);
 *   <li>"пророки": the share of direction calls that were right among the rounds that were not flat, as in the season; a player
 *       whose rounds were all flat has no ratio and is not listed.
 * </ul>
 * A pure rule, no I/O: the totals come from {@code player_round_history}.
 */
public final class WeeklyBoards {

    private WeeklyBoards() {
    }

    /** The window's start: the Monday 00:00 UTC on or before {@code nowMs}. */
    public static long weekStartMs(long nowMs) {
        return Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    /** One player's rounds of the window: how many settled, the sum of {@code payout - stake - fees} (mojos), and the direction
     *  calls ({@code eligible}: rounds that were not flat, {@code correct}: of those, the right ones). */
    public record Totals(String playerId, int rounds, long netMojos, int eligible, int correct) {
    }

    public record Rich(String playerId, long netMojos, int rounds) {
    }

    public record Prophet(String playerId, double accuracy, int eligible, int rounds) {
    }

    public static List<Rich> rich(List<Totals> totals, int minRounds) {
        List<Rich> out = new ArrayList<>();
        for (Totals t : totals) {
            if (t.rounds() >= minRounds && t.netMojos() > 0) {
                out.add(new Rich(t.playerId(), t.netMojos(), t.rounds()));
            }
        }
        out.sort(Comparator.comparingLong(Rich::netMojos).reversed().thenComparing(Rich::playerId));
        return out;
    }

    public static List<Prophet> prophets(List<Totals> totals, int minRounds) {
        List<Prophet> out = new ArrayList<>();
        for (Totals t : totals) {
            if (t.rounds() >= minRounds && t.eligible() > 0) {
                out.add(new Prophet(t.playerId(), (double) t.correct() / t.eligible(), t.eligible(), t.rounds()));
            }
        }
        out.sort(Comparator.comparingDouble(Prophet::accuracy).reversed().thenComparing(Prophet::playerId));
        return out;
    }
}
