package crowdvsmarket.server.domain;

/**
 * A player's win streak (D-71, contract.ts {@code PublicProfileDto.streak}), over the player's settled
 * rounds taken NEWEST FIRST:
 * <ul>
 *   <li>a round is a <b>win</b> when {@code payout > stake}; a liquidation (payout 0) is therefore a loss;
 *   <li>a <b>flat</b> round ({@code payout == stake}) neither extends nor breaks a streak — it is as if it
 *       were not there;
 *   <li>a cancelled round is not a round at all: it never reaches this class (a cancellation writes no
 *       {@code player_round_history} row, its stake is refunded in the ledger);
 *   <li>{@code current} is the number of wins in a row from the newest round on (0 when the newest
 *       non-flat round is a loss), {@code best} the longest such run anywhere in the history.
 * </ul>
 * Amounts are the mojos of {@code player_round_history}; the class is a pure rule, no I/O. Feed it with
 * {@link Counter#add} one round at a time, so that a long history is never held in memory.
 */
public final class WinStreak {

    private WinStreak() {
    }

    /** {@code current}: the run of wins that ends at the newest round; {@code best}: the longest run. */
    public record Result(int current, int best) {
    }

    /** One settled round, for {@link #of}. */
    public record Round(long stakeMojos, long payoutMojos) {
    }

    /** Accumulates a history, newest round first. */
    public static final class Counter {
        private int run;
        private int best;
        private int current;
        private boolean newestRunClosed;

        /** The next round, one older than the previous call's. */
        public void add(long stakeMojos, long payoutMojos) {
            if (payoutMojos == stakeMojos) {
                return; // flat: neither extends nor breaks the streak
            }
            if (payoutMojos > stakeMojos) {
                run++;
                best = Math.max(best, run);
                return;
            }
            if (!newestRunClosed) {
                current = run; // the first loss ends the run that started at the newest round
                newestRunClosed = true;
            }
            run = 0;
        }

        public Result result() {
            return new Result(newestRunClosed ? current : run, best);
        }
    }

    /** The streak of {@code newestFirst}. */
    public static Result of(Iterable<Round> newestFirst) {
        Counter counter = new Counter();
        for (Round round : newestFirst) {
            counter.add(round.stakeMojos(), round.payoutMojos());
        }
        return counter.result();
    }
}
