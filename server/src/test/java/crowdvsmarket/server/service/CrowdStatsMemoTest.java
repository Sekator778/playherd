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
 * ended — and that they are counted once per round, not once per call.
 */
class CrowdStatsMemoTest {

    private final CrowdStatsMemo memo = new CrowdStatsMemo();

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
        CrowdStatsMemo.Stats stats = memo.of("M", new CrowdAccount());

        assertThat(stats).isEqualTo(new CrowdStatsMemo.Stats(0, 0, null));
    }

    @Test
    void anUntradedRoundCountsNowhereAndLeavesTheLastOutcomeAlone() {
        CrowdAccount account = new CrowdAccount();
        untraded(account, "r1");
        assertThat(memo.of("M", account)).isEqualTo(new CrowdStatsMemo.Stats(0, 0, null));

        win(account, "r2");
        untraded(account, "r3");
        assertThat(memo.of("M", account)).as("the last TRADED round says how it ended")
                .isEqualTo(new CrowdStatsMemo.Stats(1, 1, Enums.CrowdOutcome.WIN));
    }

    @Test
    void wonMeansMoreCapitalThanBeforeAndTheLastOutcomeFollowsTheLastTradedRound() {
        CrowdAccount account = new CrowdAccount();
        win(account, "r1");
        loss(account, "r2");
        win(account, "r3");

        assertThat(memo.of("M", account)).isEqualTo(new CrowdStatsMemo.Stats(3, 2, Enums.CrowdOutcome.WIN));

        loss(account, "r4");
        assertThat(memo.of("M", account)).isEqualTo(new CrowdStatsMemo.Stats(4, 2, Enums.CrowdOutcome.LOSS));

        liquidation(account, "r5");
        assertThat(memo.of("M", account)).isEqualTo(new CrowdStatsMemo.Stats(5, 2, Enums.CrowdOutcome.LIQUIDATED));
        assertThat(account.history().get(4).liquidated()).isTrue();
    }

    @Test
    void aFlatTradedRoundIsTradedButNeitherWonNorLostAndReadsAsFlat() {
        CrowdAccount account = new CrowdAccount();
        flat(account, "r1");

        CrowdAccount.RoundResult result = account.history().get(0);
        assertThat(result.traded()).isTrue();
        assertThat(result.capitalAfter()).isEqualByComparingTo(result.capitalBefore());
        assertThat(memo.of("M", account)).as("traded, not won, and not a loss either: FLAT (contract.ts)")
                .isEqualTo(new CrowdStatsMemo.Stats(1, 0, Enums.CrowdOutcome.FLAT));
    }

    @Test
    void theLastOutcomeFollowsTheLatestTradedRoundThroughFlatAndBack() {
        CrowdAccount account = new CrowdAccount();
        win(account, "r1");
        flat(account, "r2");
        assertThat(memo.of("M", account)).isEqualTo(new CrowdStatsMemo.Stats(2, 1, Enums.CrowdOutcome.FLAT));

        untraded(account, "r3");
        assertThat(memo.of("M", account)).as("an untraded round leaves it alone")
                .isEqualTo(new CrowdStatsMemo.Stats(2, 1, Enums.CrowdOutcome.FLAT));

        loss(account, "r4");
        assertThat(memo.of("M", account)).isEqualTo(new CrowdStatsMemo.Stats(3, 1, Enums.CrowdOutcome.LOSS));
        // counting from the beginning gives the same answer as the incremental count above
        assertThat(new CrowdStatsMemo().of("M", account)).isEqualTo(memo.of("M", account));
    }

    @Test
    void aGrowingHistoryIsCountedFromWhereItWasLeftOff() {
        CrowdAccount account = new CrowdAccount();
        win(account, "r1");
        CrowdStatsMemo.Stats first = memo.of("M", account);
        assertThat(first).isEqualTo(new CrowdStatsMemo.Stats(1, 1, Enums.CrowdOutcome.WIN));
        assertThat(memo.of("M", account)).as("nothing new: the very same counters, not a recount").isSameAs(first);

        loss(account, "r2");
        untraded(account, "r3");
        loss(account, "r4");
        CrowdStatsMemo.Stats grown = memo.of("M", account);

        assertThat(grown).isEqualTo(new CrowdStatsMemo.Stats(3, 1, Enums.CrowdOutcome.LOSS));
        // the incremental count agrees with counting from the beginning
        assertThat(new CrowdStatsMemo().of("M", account)).isEqualTo(grown);
    }

    @Test
    void anotherAccountObjectForTheSameMarketIsCountedFromTheBeginning() {
        CrowdAccount before = new CrowdAccount();
        win(before, "r1");
        win(before, "r2");
        assertThat(memo.of("M", before)).isEqualTo(new CrowdStatsMemo.Stats(2, 2, Enums.CrowdOutcome.WIN));

        // an engine rebuild leaves a new account object behind. One whose history is as long as the old one's but
        // not the same is not mistaken for it ...
        CrowdAccount sameSize = new CrowdAccount();
        loss(sameSize, "r1");
        loss(sameSize, "r2");
        assertThat(memo.of("M", sameSize)).isEqualTo(new CrowdStatsMemo.Stats(2, 0, Enums.CrowdOutcome.LOSS));

        // ... nor is a longer one, whose first rounds are others than the ones counted before ...
        CrowdAccount longer = new CrowdAccount();
        win(longer, "r1");
        loss(longer, "r2");
        loss(longer, "r3");
        assertThat(memo.of("M", longer)).isEqualTo(new CrowdStatsMemo.Stats(3, 1, Enums.CrowdOutcome.LOSS));

        // ... and a shorter one is simply counted
        CrowdAccount shorter = new CrowdAccount();
        win(shorter, "r1");
        assertThat(memo.of("M", shorter)).isEqualTo(new CrowdStatsMemo.Stats(1, 1, Enums.CrowdOutcome.WIN));
    }

    @Test
    void marketsAreCountedApart() {
        CrowdAccount a = new CrowdAccount();
        CrowdAccount b = new CrowdAccount();
        win(a, "r1");
        loss(b, "r1");

        assertThat(memo.of("A", a)).isEqualTo(new CrowdStatsMemo.Stats(1, 1, Enums.CrowdOutcome.WIN));
        assertThat(memo.of("B", b)).isEqualTo(new CrowdStatsMemo.Stats(1, 0, Enums.CrowdOutcome.LOSS));
        assertThat(memo.of("A", a)).isEqualTo(new CrowdStatsMemo.Stats(1, 1, Enums.CrowdOutcome.WIN));
    }
}
