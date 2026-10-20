package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.server.dto.Enums;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The counters the lobby shows about a crowd account — rounds traded, rounds won, how the last traded round
 * ended — (counted over the history the read views hold, F8).
 */
class CrowdStatsTest {

    private static ReadViews.CrowdStats of(CrowdAccount account) {
        return ReadViews.statsOf(account.history());
    }

    private static Candle candle(String open, String high, String low, String close) {
        return new Candle(0L, 59_999L, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), BigDecimal.ZERO, 0);
    }

    private static List<Bet> upBet(int leverage) {
        return List.of(new Bet("p", "r", Direction.UP, new Leverage(leverage), PlayAmount.ofWholePlay(500)));
    }

    /** The crowd trades UP at 2x and the price rises: a win. */
    private static void win(CrowdAccount account, String roundId) {
        account.processRound(roundId, upBet(2), new BigDecimal("1000.00"),
                List.of(candle("1000", "1100", "1000", "1100")));
    }

    /** The crowd trades UP at 2x and the price falls a little: a loss. */
    private static void loss(CrowdAccount account, String roundId) {
        account.processRound(roundId, upBet(2), new BigDecimal("1000.00"),
                List.of(candle("1000", "1000", "900", "900")));
    }

    /** The crowd trades UP at 40x and the price falls 10%: liquidated. */
    private static void liquidation(CrowdAccount account, String roundId) {
        account.processRound(roundId, upBet(40), new BigDecimal("1000.00"),
                List.of(candle("1000", "1000", "900", "900")));
    }

    /** The crowd trades UP at 2x and the price ends where it began: flat, no gain, no loss. */
    private static void flat(CrowdAccount account, String roundId) {
        account.processRound(roundId, upBet(2), new BigDecimal("1000.00"),
                List.of(candle("1000", "1010", "990", "1000")));
    }

    /** Nobody bet: the crowd does not trade. */
    private static void untraded(CrowdAccount account, String roundId) {
        account.processRound(roundId, List.of(), new BigDecimal("1000.00"),
                List.of(candle("1000", "1000", "1000", "1000")));
    }

    @Test
    void anAccountWithoutRoundsHasNoOutcome() {
        ReadViews.CrowdStats stats = of(new CrowdAccount());

        assertThat(stats).isEqualTo(new ReadViews.CrowdStats(0, 0, null));
    }

    @Test
    void anUntradedRoundCountsNowhereAndLeavesTheLastOutcomeAlone() {
        CrowdAccount account = new CrowdAccount();
        untraded(account, "r1");
        assertThat(of(account)).isEqualTo(new ReadViews.CrowdStats(0, 0, null));

        win(account, "r2");
        untraded(account, "r3");
        assertThat(of(account)).as("the last TRADED round says how it ended")
                .isEqualTo(new ReadViews.CrowdStats(1, 1, Enums.CrowdOutcome.WIN));
    }

    @Test
    void wonMeansMoreCapitalThanBeforeAndTheLastOutcomeFollowsTheLastTradedRound() {
        CrowdAccount account = new CrowdAccount();
        win(account, "r1");
        loss(account, "r2");
        win(account, "r3");

        assertThat(of(account)).isEqualTo(new ReadViews.CrowdStats(3, 2, Enums.CrowdOutcome.WIN));

        loss(account, "r4");
        assertThat(of(account)).isEqualTo(new ReadViews.CrowdStats(4, 2, Enums.CrowdOutcome.LOSS));

        liquidation(account, "r5");
        assertThat(of(account)).isEqualTo(new ReadViews.CrowdStats(5, 2, Enums.CrowdOutcome.LIQUIDATED));
        assertThat(account.history().get(4).liquidated()).isTrue();
    }

    @Test
    void aFlatTradedRoundIsTradedButNeitherWonNorLostAndReadsAsFlat() {
        CrowdAccount account = new CrowdAccount();
        flat(account, "r1");

        CrowdAccount.RoundResult result = account.history().get(0);
        assertThat(result.traded()).isTrue();
        assertThat(result.capitalAfter()).isEqualByComparingTo(result.capitalBefore());
        assertThat(of(account)).as("traded, not won, and not a loss either: FLAT (contract.ts)")
                .isEqualTo(new ReadViews.CrowdStats(1, 0, Enums.CrowdOutcome.FLAT));
    }

    @Test
    void theLastOutcomeFollowsTheLatestTradedRoundThroughFlatAndBack() {
        CrowdAccount account = new CrowdAccount();
        win(account, "r1");
        flat(account, "r2");
        assertThat(of(account)).isEqualTo(new ReadViews.CrowdStats(2, 1, Enums.CrowdOutcome.FLAT));

        untraded(account, "r3");
        assertThat(of(account)).as("an untraded round leaves it alone")
                .isEqualTo(new ReadViews.CrowdStats(2, 1, Enums.CrowdOutcome.FLAT));

        loss(account, "r4");
        assertThat(of(account)).isEqualTo(new ReadViews.CrowdStats(3, 1, Enums.CrowdOutcome.LOSS));
    }
}
