package crowdvsmarket.engine.crowd;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.Direction;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrowdAccountTest {

    private static Bet bet(String player, Direction direction, int leverage, long stakePlay) {
        return new Bet(player, "r1", direction, new Leverage(leverage), PlayAmount.ofWholePlay(stakePlay));
    }

    private static Candle candle(String open, String high, String low, String close) {
        return new Candle(0L, 3_600_000L - 1, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low), new BigDecimal(close),
                BigDecimal.ZERO, 0L);
    }

    @Test
    void noBetsMakesNoTradeAndLeavesCapitalUnchanged() {
        CrowdAccount account = new CrowdAccount();
        CrowdAccount.RoundResult result = account.processRound("r1", List.of(), new BigDecimal("50000.00"),
                List.of(candle("50000.00", "50100.00", "49900.00", "50050.00")));

        assertThat(result.traded()).isFalse();
        assertThat(account.capital()).isEqualByComparingTo(CrowdAccount.STARTING_CAPITAL);
        assertThat(account.history()).containsExactly(result);
    }

    @Test
    void tiedStakesMakeNoTrade() {
        CrowdAccount account = new CrowdAccount();
        List<Bet> bets = List.of(bet("alice", Direction.UP, 10, 500), bet("bob", Direction.DOWN, 10, 500));

        CrowdAccount.RoundResult result = account.processRound("r1", bets, new BigDecimal("50000.00"),
                List.of(candle("50000.00", "50100.00", "49900.00", "50050.00")));

        assertThat(result.traded()).isFalse();
        assertThat(account.capital()).isEqualByComparingTo(CrowdAccount.STARTING_CAPITAL);
    }

    @Test
    void majorityIsBySumOfStakesNotByCount() {
        CrowdAccount account = new CrowdAccount();
        // Three DOWN bets outnumber one UP bet, but UP's stake sum is far larger.
        List<Bet> bets = List.of(
                bet("alice", Direction.UP, 10, 3_000),
                bet("bob", Direction.DOWN, 10, 100),
                bet("carol", Direction.DOWN, 10, 100),
                bet("dave", Direction.DOWN, 10, 100));

        CrowdAccount.RoundResult result = account.processRound("r1", bets, new BigDecimal("50000.00"),
                List.of(candle("50000.00", "50100.00", "49900.00", "50050.00")));

        assertThat(result.traded()).isTrue();
        assertThat(result.direction()).isEqualTo(Direction.UP);
    }

    @Test
    void leverageIsStakeWeightedAverageRoundedHalfUp() {
        CrowdAccount account = new CrowdAccount();
        // weighted avg = (10*300 + 20*100) / 400 = 5000/400 = 12.5 -> HALF_UP -> 13.
        List<Bet> bets = List.of(bet("alice", Direction.UP, 10, 300), bet("bob", Direction.UP, 20, 100));

        CrowdAccount.RoundResult result = account.processRound("r1", bets, new BigDecimal("50000.00"),
                List.of(candle("50000.00", "50100.00", "49900.00", "50050.00")));

        assertThat(result.leverage()).isEqualTo(13);
    }

    @Test
    void marginIsTenPercentOfCurrentCapitalAndEntersAtOpenPrice() {
        CrowdAccount account = new CrowdAccount();
        List<Bet> bets = List.of(bet("alice", Direction.UP, 10, 300));

        CrowdAccount.RoundResult result = account.processRound("r1", bets, new BigDecimal("50000.00"),
                List.of(candle("50000.00", "50100.00", "49900.00", "50050.00")));

        assertThat(result.margin()).isEqualByComparingTo(CrowdAccount.STARTING_CAPITAL.multiply(new BigDecimal("0.10")));
        assertThat(result.entryPrice()).isEqualByComparingTo("50000.00");
    }

    @Test
    void liquidationSubtractsExactlyTheMarginFromCapital() {
        CrowdAccount account = new CrowdAccount(new BigDecimal("100000.00"));
        // margin = 10000.00, leverage 40 -> long liq price close to entry; a deep low breaches it.
        List<Bet> bets = List.of(bet("alice", Direction.UP, 40, 300));

        CrowdAccount.RoundResult result = account.processRound("r1", bets, new BigDecimal("50000.00"),
                List.of(candle("50000.00", "50100.00", "30000.00", "45000.00")));

        assertThat(result.liquidated()).isTrue();
        assertThat(account.capital()).isEqualByComparingTo(result.capitalBefore().subtract(result.margin()));
    }

    @Test
    void settlementAddsPnlAndCompoundsAcrossRounds() {
        CrowdAccount account = new CrowdAccount(new BigDecimal("100000.00"));
        List<Bet> bets = List.of(bet("alice", Direction.UP, 10, 300));

        CrowdAccount.RoundResult first = account.processRound("r1", bets, new BigDecimal("50000.00"),
                List.of(candle("50000.00", "51000.00", "49900.00", "51000.00")));

        assertThat(first.liquidated()).isFalse();
        BigDecimal capitalAfterFirst = account.capital();
        assertThat(capitalAfterFirst).isNotEqualByComparingTo(CrowdAccount.STARTING_CAPITAL);

        // Second round's margin must be 10% of the NEW capital, not the original.
        CrowdAccount.RoundResult second = account.processRound("r2", bets, new BigDecimal("51000.00"),
                List.of(candle("51000.00", "51500.00", "50900.00", "51200.00")));

        assertThat(second.margin()).isEqualByComparingTo(capitalAfterFirst.multiply(new BigDecimal("0.10")));
        assertThat(account.history()).hasSize(2);
    }
}
