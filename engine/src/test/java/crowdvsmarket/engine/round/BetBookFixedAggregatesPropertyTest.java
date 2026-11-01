package crowdvsmarket.engine.round;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * D-106 on top of F2 ({@link BetBookAggregatesPropertyTest}): in rounds that hold fixed-payout and percentage bets together the book's
 * aggregates still equal a brute-force recomputation after every step of long random sequences - the open notional counts the
 * percentage-mode bets only (a fixed bet has no leverage), the open fixed stake counts the fixed bets only.
 */
class BetBookFixedAggregatesPropertyTest {

    private static final List<String> ROUNDS = List.of("1h-1", "1h-2", "ETH-1h-1", "ETH-1d-1");
    private static final List<String> PLAYERS = List.of("p1", "p2", "p3", "p4", "p5", "p6");

    private static BigDecimal bruteNotional(BetBook book, String roundId, Direction direction, String exceptPlayer) {
        BigDecimal total = BigDecimal.ZERO;
        for (Bet bet : book.betsFor(roundId)) {
            if (!bet.isFixed() && bet.direction() == direction && !bet.playerId().equals(exceptPlayer)) {
                total = total.add(bet.stake().toMoney().multiply(BigDecimal.valueOf(bet.leverage().value())));
            }
        }
        return total;
    }

    private static BigDecimal bruteFixedStake(BetBook book, String roundId, Direction direction, String exceptPlayer) {
        BigDecimal total = BigDecimal.ZERO;
        for (Bet bet : book.betsFor(roundId)) {
            if (bet.isFixed() && bet.direction() == direction && !bet.playerId().equals(exceptPlayer)) {
                total = total.add(bet.stake().toMoney());
            }
        }
        return total;
    }

    private static void assertAggregatesMatch(BetBook book, String context) {
        for (String roundId : ROUNDS) {
            assertThat(book.count(roundId)).as(context + " count " + roundId).isEqualTo(book.betsFor(roundId).size());
            for (Direction direction : Direction.values()) {
                for (String except : Arrays.asList(null, "p1", "p2", "p3", "p4", "p5", "p6", "nobody")) {
                    String at = context + " " + roundId + " " + direction + " except " + except;
                    BigDecimal notional = bruteNotional(book, roundId, direction, except);
                    assertThat(book.openNotional(roundId, direction, except)).as(at + " notional").isEqualByComparingTo(notional);
                    BigDecimal fixed = bruteFixedStake(book, roundId, direction, except);
                    assertThat(book.openFixedStake(roundId, direction, except)).as(at + " fixed stake").isEqualByComparingTo(fixed);
                }
            }
        }
    }

    private static Bet randomBet(Random random, String roundId, String playerId) {
        Direction direction = random.nextBoolean() ? Direction.UP : Direction.DOWN;
        PlayAmount stake = new PlayAmount(100_000L + random.nextInt(2_000_000) * 7L); // odd mojos too
        Fees fees = EconomyRules.fixedFees(stake);
        if (random.nextBoolean()) {
            return Bet.fixed(playerId, roundId, direction, stake, fees);
        }
        return new Bet(playerId, roundId, direction, new Leverage(2 + random.nextInt(99)), stake, fees, null);
    }

    @Test
    void aggregatesEqualBruteForceAfterEveryStepOfRandomMixedSequences() {
        for (long seed = 1; seed <= 40; seed++) {
            Random random = new Random(seed);
            BetBook book = new BetBook();
            for (int step = 0; step < 300; step++) {
                String roundId = ROUNDS.get(random.nextInt(ROUNDS.size()));
                String playerId = PLAYERS.get(random.nextInt(PLAYERS.size()));
                int op = random.nextInt(10);
                if (op < 6) {
                    Optional<Bet> before = book.find(roundId, playerId);
                    assertThat(book.place(randomBet(random, roundId, playerId))).isEqualTo(before);
                } else if (op < 9) {
                    Optional<Bet> before = book.find(roundId, playerId);
                    assertThat(book.cancel(roundId, playerId)).isEqualTo(before);
                } else {
                    book.clear(roundId);
                }
                assertAggregatesMatch(book, "seed " + seed + " step " + step);
            }
        }
    }

    @Test
    void aFixedBetAddsNothingToTheNotionalAndAPercentageBetNothingToTheFixedStake() {
        BetBook book = new BetBook();
        book.place(Bet.fixed("p1", "r", Direction.UP, new PlayAmount(5_000), EconomyRules.fixedFees(new PlayAmount(5_000))));
        assertThat(book.openNotional("r", Direction.UP, null)).isEqualTo(BigDecimal.ZERO);
        assertThat(book.openFixedStake("r", Direction.UP, null)).isEqualByComparingTo("5");
        assertThat(book.openFixedStake("r", Direction.DOWN, null)).isEqualTo(BigDecimal.ZERO);
        book.place(new Bet("p1", "r", Direction.UP, new Leverage(10), new PlayAmount(2_000), Fees.ZERO, null)); // replaces it
        assertThat(book.openFixedStake("r", Direction.UP, null)).isEqualTo(BigDecimal.ZERO);
        assertThat(book.openNotional("r", Direction.UP, null)).isEqualByComparingTo("20");
        assertThat(book.openFixedStake("nothing", Direction.UP, null)).isEqualTo(BigDecimal.ZERO);
    }
}
