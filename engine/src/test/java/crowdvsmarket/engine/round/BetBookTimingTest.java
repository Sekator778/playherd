package crowdvsmarket.engine.round;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Architecture plan F2, the numbers (not part of the normal run - enable with {@code -Dherd.timing=true}): what one
 * {@code placeBet}'s book work costs with N bets already in the round. "Before" is the code GameService had (find the player's
 * own bet by streaming a copy of the round's bets, then sum the side's notional over a copy of the bets); "after" is
 * {@link BetBook#find} + {@link BetBook#openNotional}. A hand-rolled loop with a warm-up, no JMH: indicative only.
 */
@EnabledIfSystemProperty(named = "herd.timing", matches = "true")
class BetBookTimingTest {

    private static volatile long sink;

    private static BigDecimal before(BetBook book, String roundId, Direction direction, String player) {
        Optional<Bet> existing = book.betsFor(roundId).stream().filter(b -> b.playerId().equals(player)).findFirst();
        BigDecimal total = BigDecimal.ZERO;
        for (Bet bet : book.betsFor(roundId)) {
            if (bet.direction() == direction && !bet.playerId().equals(player)) {
                total = total.add(bet.stake().toMoney().multiply(BigDecimal.valueOf(bet.leverage().value())));
            }
        }
        sink += existing.isPresent() ? 1 : 0;
        return total;
    }

    private static BigDecimal after(BetBook book, String roundId, Direction direction, String player) {
        Optional<Bet> existing = book.find(roundId, player);
        sink += existing.isPresent() ? 1 : 0;
        return book.openNotional(roundId, direction, player);
    }

    private interface Check {
        BigDecimal run(BetBook book, String roundId, Direction direction, String player);
    }

    private static double nanosPerOp(Check check, BetBook book, int n, int iterations) {
        long[] batches = new long[7];
        for (int b = 0; b < batches.length; b++) {
            long t = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                sink += check.run(book, "r", i % 2 == 0 ? Direction.UP : Direction.DOWN, "p" + (i % n)).signum();
            }
            batches[b] = System.nanoTime() - t;
        }
        Arrays.sort(batches);
        return batches[batches.length / 2] / (double) iterations;
    }

    @Test
    void printTimingsPerBetCheck() {
        System.out.println("N_bets_in_round  before_ns_per_check  after_ns_per_check");
        for (int n : new int[] {100, 1_000, 10_000}) {
            Random random = new Random(7);
            BetBook book = new BetBook();
            for (int i = 0; i < n; i++) {
                book.place(new Bet("p" + i, "r", random.nextBoolean() ? Direction.UP : Direction.DOWN,
                        new Leverage(2 + random.nextInt(50)), new PlayAmount(100_000L + random.nextInt(1000) * 1_000L)));
            }
            assertThat(before(book, "r", Direction.UP, "p0")).isEqualTo(after(book, "r", Direction.UP, "p0"));
            int iterBefore = Math.max(20, 200_000 / n);
            nanosPerOp((bk, r, d, p) -> before(bk, r, d, p), book, n, iterBefore); // warm-up
            nanosPerOp((bk, r, d, p) -> after(bk, r, d, p), book, n, 200_000);
            double b = nanosPerOp((bk, r, d, p) -> before(bk, r, d, p), book, n, iterBefore);
            double a = nanosPerOp((bk, r, d, p) -> after(bk, r, d, p), book, n, 200_000);
            System.out.printf("%d  %.0f  %.0f%n", n, b, a);
        }
    }
}
