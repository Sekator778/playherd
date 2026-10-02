package crowdvsmarket.server.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The win streak (Р-71, contract.ts {@code PublicProfileDto.streak}), over rounds taken newest first: a win
 * is {@code payout > stake}, a flat round ({@code payout == stake}) neither extends nor breaks a streak, a
 * liquidation (payout 0) is a loss. (A cancelled round is not a round: it never has a history row to feed
 * this, which {@code ProfileRpcTest} checks against the database.)
 */
class WinStreakTest {

    private static final long STAKE = 100_000; // 100.000 PLAY in mojos

    private static WinStreak.Round win() {
        return new WinStreak.Round(STAKE, STAKE + 40_000);
    }

    private static WinStreak.Round loss() {
        return new WinStreak.Round(STAKE, STAKE - 30_000);
    }

    private static WinStreak.Round liquidated() {
        return new WinStreak.Round(STAKE, 0);
    }

    private static WinStreak.Round flat() {
        return new WinStreak.Round(STAKE, STAKE);
    }

    private static WinStreak.Result streak(WinStreak.Round... newestFirst) {
        return WinStreak.of(List.of(newestFirst));
    }

    @Test
    void noRoundsNoStreak() {
        assertThat(WinStreak.of(List.of())).isEqualTo(new WinStreak.Result(0, 0));
    }

    @Test
    void allWinsCountFromTheNewest() {
        assertThat(streak(win(), win(), win())).isEqualTo(new WinStreak.Result(3, 3));
    }

    @Test
    void aLossAsTheNewestRoundEndsTheCurrentStreakButNotTheBest() {
        assertThat(streak(loss(), win(), win(), win(), loss(), win()))
                .isEqualTo(new WinStreak.Result(0, 3));
    }

    @Test
    void theCurrentStreakStopsAtTheFirstLossAndTheBestIsTheLongestRunAnywhere() {
        // newest first: W W L W W W — two now, three once
        assertThat(streak(win(), win(), loss(), win(), win(), win())).isEqualTo(new WinStreak.Result(2, 3));
        // the current one can be the best
        assertThat(streak(win(), win(), win(), loss(), win())).isEqualTo(new WinStreak.Result(3, 3));
    }

    @Test
    void aFlatRoundNeitherExtendsNorBreaksAStreak() {
        // a flat round between two wins does not break the run ...
        assertThat(streak(win(), flat(), win())).isEqualTo(new WinStreak.Result(2, 2));
        // ... on top of it, it adds nothing ...
        assertThat(streak(flat(), win(), loss())).isEqualTo(new WinStreak.Result(1, 1));
        // ... and a history of nothing but flat rounds has no streak at all
        assertThat(streak(flat(), flat(), flat())).isEqualTo(new WinStreak.Result(0, 0));
        // nor does it rescue a streak a loss has ended
        assertThat(streak(loss(), flat(), win(), win())).isEqualTo(new WinStreak.Result(0, 2));
    }

    @Test
    void aLiquidationIsALoss() {
        assertThat(streak(win(), liquidated(), win(), win())).isEqualTo(new WinStreak.Result(1, 2));
        assertThat(streak(liquidated(), win())).isEqualTo(new WinStreak.Result(0, 1));
    }

    @Test
    void aWinIsAnyPayoutAboveTheStakeEvenByOneMojo() {
        assertThat(streak(new WinStreak.Round(STAKE, STAKE + 1), new WinStreak.Round(STAKE, STAKE - 1)))
                .isEqualTo(new WinStreak.Result(1, 1));
    }

    @Test
    void theCounterGivesTheSameAnswerAsTheListForm() {
        List<WinStreak.Round> history = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            history.add(switch (i % 7) {
                case 0, 1, 4 -> win();
                case 2 -> flat();
                case 3 -> loss();
                case 5 -> liquidated();
                default -> win();
            });
        }
        WinStreak.Counter counter = new WinStreak.Counter();
        for (WinStreak.Round round : history) {
            counter.add(round.stakeMojos(), round.payoutMojos());
        }

        assertThat(counter.result()).isEqualTo(WinStreak.of(history));
    }

    @Test
    void theBestIsTheLongestRunWhicheverEndOfTheHistoryItIsAt() {
        List<WinStreak.Round> oldRun = new ArrayList<>(List.of(loss(), win()));
        oldRun.addAll(Collections.nCopies(25, win())); // 26 wins at the old end
        assertThat(WinStreak.of(oldRun)).isEqualTo(new WinStreak.Result(0, 26));

        List<WinStreak.Round> newRun = new ArrayList<>(Collections.nCopies(25, win()));
        newRun.add(loss());
        newRun.addAll(Collections.nCopies(3, win()));
        assertThat(WinStreak.of(newRun)).isEqualTo(new WinStreak.Result(25, 25));
    }
}
