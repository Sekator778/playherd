package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.BetBook;
import crowdvsmarket.engine.round.Direction;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Р-106 (rules point 5) under random sequences: bets - fixed and percentage, new and replacing - are offered to one {@link BetBook} per
 * market, three of them crypto, and the decision {@link EconomyLimits#checkFixedExposure} gives, fed by the book's O(1) aggregate, equals the
 * brute-force decision over the bets (sum of the other players' fixed stakes on the side, and over the crypto markets for the bucket). An
 * accepted bet is placed; so no side of any market ever holds more than 0.1% of the treasury, nor the crypto markets together more than 0.4%.
 */
class FixedExposureLimitsPropertyTest {

    private static final PlayAmount TREASURY = PlayAmount.ofWholePlay(1_000_000); // 0.1% = 1000, 0.4% = 4000
    private static final List<String> MARKETS = List.of("BTC", "ETH", "SOL", "NVDA");
    private static final List<String> CRYPTO = List.of("BTC", "ETH", "SOL");
    private static final List<String> PLAYERS = List.of("p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8");

    private static BigDecimal bruteOpen(BetBook book, String roundId, Direction direction, String except) {
        BigDecimal total = BigDecimal.ZERO;
        for (Bet bet : book.betsFor(roundId)) {
            if (bet.isFixed() && bet.direction() == direction && !bet.playerId().equals(except)) {
                total = total.add(bet.stake().toMoney());
            }
        }
        return total;
    }

    @Test
    void theDecisionEqualsTheBruteForceAndNoSideEverGoesOverTheLimits() {
        BigDecimal marketLimit = new BigDecimal("1000");
        BigDecimal bucketLimit = new BigDecimal("4000");
        for (long seed = 1; seed <= 30; seed++) {
            Random random = new Random(seed);
            java.util.Map<String, BetBook> books = new java.util.HashMap<>();
            MARKETS.forEach(m -> books.put(m, new BetBook()));
            int refused = 0;
            int accepted = 0;
            for (int step = 0; step < 400; step++) {
                String market = MARKETS.get(random.nextInt(MARKETS.size()));
                String player = PLAYERS.get(random.nextInt(PLAYERS.size()));
                Direction direction = random.nextBoolean() ? Direction.UP : Direction.DOWN;
                PlayAmount stake = PlayAmount.fromMoney(new BigDecimal(100 + random.nextInt(900)).add(new BigDecimal(random.nextInt(1000)).movePointLeft(3)));
                BetBook book = books.get(market);
                boolean crypto = CRYPTO.contains(market);
                if (random.nextInt(4) == 0) { // a percentage bet: no check here, but it may replace a fixed one
                    book.place(new Bet(player, "r", direction, new Leverage(2 + random.nextInt(20)), stake,
                            EconomyRules.fees(crowdvsmarket.engine.market.MarketSpec.BTC, crowdvsmarket.engine.market.Interval.HOURLY, 5, stake), null));
                    continue;
                }

                BigDecimal open = book.openFixedStake("r", direction, player);
                BigDecimal bucketOpen = null;
                if (crypto) {
                    bucketOpen = BigDecimal.ZERO;
                    for (String other : CRYPTO) {
                        bucketOpen = bucketOpen.add(other.equals(market) ? open : books.get(other).openFixedStake("r", direction, null));
                    }
                }
                EconomyLimits.FixedExposure decision = EconomyLimits.checkFixedExposure(TREASURY, open, stake.toMoney(), bucketOpen);

                BigDecimal bruteOpen = bruteOpen(book, "r", direction, player);
                assertThat(open).as("seed " + seed + " step " + step + " aggregate").isEqualByComparingTo(bruteOpen);
                boolean expected = bruteOpen.add(stake.toMoney()).compareTo(marketLimit) <= 0;
                if (crypto) {
                    BigDecimal bucket = bruteOpen.add(stake.toMoney());
                    for (String other : CRYPTO) {
                        if (!other.equals(market)) {
                            bucket = bucket.add(bruteOpen(books.get(other), "r", direction, null));
                        }
                    }
                    expected &= bucket.compareTo(bucketLimit) <= 0;
                }
                assertThat(decision.ok()).as("seed " + seed + " step " + step).isEqualTo(expected);
                assertThat(decision.roomStake().signum()).isGreaterThanOrEqualTo(0);
                if (decision.ok()) {
                    book.place(Bet.fixed(player, "r", direction, stake, EconomyRules.fixedFees(stake)));
                    accepted++;
                } else {
                    refused++;
                }

                for (Direction d : Direction.values()) {
                    BigDecimal cryptoTotal = BigDecimal.ZERO;
                    for (String m : MARKETS) {
                        BigDecimal side = books.get(m).openFixedStake("r", d, null);
                        assertThat(side).as("market side").isLessThanOrEqualTo(marketLimit);
                        if (CRYPTO.contains(m)) {
                            cryptoTotal = cryptoTotal.add(side);
                        }
                    }
                    assertThat(cryptoTotal).as("crypto bucket").isLessThanOrEqualTo(bucketLimit);
                }
            }
            assertThat(accepted).as("seed " + seed + " accepts some").isPositive();
            assertThat(refused).as("seed " + seed + " refuses some").isPositive();
        }
    }
}
