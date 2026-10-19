package crowdvsmarket.engine.round;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Architecture plan F2: the aggregates {@link BetBook} keeps (per-side open notional, count, lookup) equal a brute-force
 * recomputation from {@link BetBook#betsFor} after every step of long random sequences of place (new and replacing),
 * cancel and clear over several rounds and both sides. The brute force below is the code the aggregates replaced
 * ({@code GameService.openNotional}); it lives only here.
 */
class BetBookAggregatesPropertyTest {

    private static final List<String> ROUNDS = List.of("1h-1", "1h-2", "ETH-1h-1", "ETH-1d-1");
    private static final List<String> PLAYERS = List.of("p1", "p2", "p3", "p4", "p5", "p6");

    /** The old O(bets) computation, bit for bit. */
    private static BigDecimal bruteForceOpenNotional(BetBook book, String roundId, Direction direction, String exceptPlayer) {
        BigDecimal total = BigDecimal.ZERO;
        for (Bet bet : book.betsFor(roundId)) {
            if (bet.direction() == direction && !bet.playerId().equals(exceptPlayer)) {
                total = total.add(bet.stake().toMoney().multiply(BigDecimal.valueOf(bet.leverage().value())));
            }
        }
        return total;
    }

    private static void assertAggregatesMatch(BetBook book, String context) {
        for (String roundId : ROUNDS) {
            List<Bet> bets = List.copyOf(book.betsFor(roundId));
            assertThat(book.count(roundId)).as(context + " count " + roundId).isEqualTo(bets.size());
            for (Direction direction : Direction.values()) {
                for (String except : Arrays.asList(null, "p1", "p2", "p3", "p4", "p5", "p6", "nobody")) {
                    BigDecimal expected = bruteForceOpenNotional(book, roundId, direction, except);
                    BigDecimal actual = book.openNotional(roundId, direction, except);
                    assertThat(actual).as(context + " " + roundId + " " + direction + " except " + except).isEqualTo(expected);
                    assertThat(actual.scale()).as("scale, bit for bit").isEqualTo(expected.scale());
                }
            }
            for (String player : PLAYERS) {
                Optional<Bet> expected = bets.stream().filter(b -> b.playerId().equals(player)).findFirst();
                assertThat(book.find(roundId, player)).as(context + " find " + roundId + " " + player).isEqualTo(expected);
            }
        }
    }

    private static Bet randomBet(Random random, String roundId, String playerId) {
        Direction direction = random.nextBoolean() ? Direction.UP : Direction.DOWN;
        int leverage = 2 + random.nextInt(99);
        long stakeMojos = 100_000L + random.nextInt(2_000_000) * 7L; // not a round number: odd mojos too
        return new Bet(playerId, roundId, direction, new Leverage(leverage), new PlayAmount(stakeMojos));
    }

    @Test
    void aggregatesEqualBruteForceAfterEveryStepOfRandomSequences() {
        for (long seed = 1; seed <= 40; seed++) {
            Random random = new Random(seed);
            BetBook book = new BetBook();
            assertAggregatesMatch(book, "seed " + seed + " empty");
            for (int step = 0; step < 300; step++) {
                String roundId = ROUNDS.get(random.nextInt(ROUNDS.size()));
                String playerId = PLAYERS.get(random.nextInt(PLAYERS.size()));
                int op = random.nextInt(10);
                String what;
                if (op < 6) {
                    Optional<Bet> before = book.find(roundId, playerId);
                    Optional<Bet> replaced = book.place(randomBet(random, roundId, playerId));
                    assertThat(replaced).as("place returns the replaced bet").isEqualTo(before);
                    what = "place";
                } else if (op < 9) {
                    Optional<Bet> before = book.find(roundId, playerId);
                    assertThat(book.cancel(roundId, playerId)).isEqualTo(before);
                    what = "cancel";
                } else {
                    book.clear(roundId);
                    what = "clear";
                }
                assertAggregatesMatch(book, "seed " + seed + " step " + step + " " + what);
            }
        }
    }

    @Test
    void anEmptyRoundHasZeroNotionalAndNoBets() {
        BetBook book = new BetBook();
        assertThat(book.openNotional("1h-1", Direction.UP, null)).isEqualTo(BigDecimal.ZERO);
        assertThat(book.count("1h-1")).isZero();
        assertThat(book.find("1h-1", "p1")).isEmpty();
    }
}
