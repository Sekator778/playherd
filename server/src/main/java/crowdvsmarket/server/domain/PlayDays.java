package crowdvsmarket.server.domain;

import java.util.List;

/**
 * "Played N days in a row" (docs/research/features_growth_2026-10-18.md §3 item 4): the run of UTC days with at least one
 * settled round of the player. No bonus and no pressure hang on it - it is only told.
 * <ul>
 *   <li>a day is a UTC calendar day (epoch milliseconds / 86 400 000); a round counts on the day it settled, whatever its result
 *       (a flat round and a loss are played rounds); a cancelled round leaves no history row and is not a round;
 *   <li>{@code current} is the run that ends today, or yesterday while today has not been played yet (a player is not shown a
 *       broken run for a day that is still going); 0 when the newest played day is older;
 *   <li>{@code best} is the longest run of consecutive days anywhere in the history.
 * </ul>
 * A pure rule, no I/O.
 */
public final class PlayDays {

    public static final long DAY_MS = 86_400_000L;

    private PlayDays() {
    }

    public record Result(int current, int best) {
    }

    /** The UTC day number of an instant. */
    public static long dayOf(long epochMs) {
        return Math.floorDiv(epochMs, DAY_MS);
    }

    /**
     * @param daysNewestFirst the UTC day numbers with a settled round, newest first (a repeated day is tolerated)
     * @param today           the UTC day number of "now"
     */
    public static Result of(List<Long> daysNewestFirst, long today) {
        int best = 0;
        int run = 0;
        Integer current = null;
        Long previous = null;
        for (long day : daysNewestFirst) {
            if (previous != null && day == previous) {
                continue;
            }
            if (previous == null) {
                run = 1;
                if (day < today - 1) {
                    current = 0; // the newest played day is older than yesterday: nothing is running now
                }
            } else if (day == previous - 1) {
                run++;
            } else {
                if (current == null) {
                    current = run;
                }
                run = 1;
            }
            best = Math.max(best, run);
            previous = day;
        }
        if (current == null) {
            current = run;
        }
        return new Result(current, best);
    }
}
