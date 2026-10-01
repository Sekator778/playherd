package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.server.markets.Market;
import java.math.BigDecimal;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The path of a round window (docs/research/markets_architecture.md §3.2 rule 4): at most 30 candles
 * of equal length, continuous, low/high covering every tick, wicks kept — checked on hand-made
 * cases and on random walks.
 */
class RoundPathTest {

    private static final Market BTC = TestMarkets.btc();

    private static long px(String raw) {
        return Px.encode(new BigDecimal(raw));
    }

    private static BigDecimal bd(String raw) {
        return BTC.toEngine(new BigDecimal(raw));
    }

    private static RoundPath.Folded fold(long start, long end, long upTo, String open, long[] at, String[] prices) {
        long[] px = new long[prices.length];
        for (int i = 0; i < px.length; i++) {
            px[i] = px(prices[i]);
        }
        return RoundPath.fold(BTC, start, end, upTo, px(open), at, px);
    }

    @Test
    void aStandHourlyRoundIsThirtyCandlesOfSixSeconds() {
        RoundPath.Folded folded = fold(0, 180_000, 180_000, "100", new long[0], new String[0]);

        List<Candle> path = folded.candles();
        assertThat(path).hasSize(30);
        assertThat(path.get(0).openTimeMs()).isZero();
        assertThat(path.get(0).closeTimeMs()).isEqualTo(6_000);
        assertThat(path.get(29).closeTimeMs()).isEqualTo(180_000);
        assertThat(path).allSatisfy(c -> {
            assertThat(c.open()).isEqualByComparingTo("100");
            assertThat(c.high()).isEqualByComparingTo("100");
            assertThat(c.low()).isEqualByComparingTo("100");
            assertThat(c.close()).isEqualByComparingTo("100");
        });
        assertThat(Px.decode(folded.closePx())).isEqualByComparingTo("100");
    }

    @Test
    void aWindowThatDoesNotDivideByThirtyEndsWithAShorterCandle() {
        // 100 s over 30 candles: 3333.33 ms rounds up to 3334 ms, so the 30th has 3314 ms left
        RoundPath.Folded folded = fold(1_000, 101_000, 101_000, "100", new long[] {50_000}, new String[] {"101"});

        List<Candle> path = folded.candles();
        assertThat(path).hasSize(30);
        assertThat(path.get(0).closeTimeMs() - path.get(0).openTimeMs()).isEqualTo(3_334);
        assertThat(path.get(28).closeTimeMs() - path.get(28).openTimeMs()).isEqualTo(3_334);
        assertThat(path.get(29).openTimeMs()).isEqualTo(1_000 + 29 * 3_334L);
        assertThat(path.get(29).closeTimeMs()).isEqualTo(101_000);
        assertThat(path.get(29).closeTimeMs() - path.get(29).openTimeMs()).isEqualTo(3_314);
    }

    @Test
    void aVeryShortWindowGivesFewerCandlesNeverMoreThanThirty() {
        RoundPath.Folded folded = fold(0, 31, 31, "100", new long[0], new String[0]);

        assertThat(folded.candles()).hasSize(16); // 2 ms each, the 16th has 1 ms
        assertThat(folded.candles().get(15).closeTimeMs()).isEqualTo(31);
    }

    @Test
    void aJumpBetweenTwoCandlesKeepsItsWickInTheLaterCandle() {
        // candle 0 is (0, 6000], candle 1 is (6000, 12000]: 100 at 5 s, then 130 at 7 s
        RoundPath.Folded folded = fold(0, 180_000, 180_000, "100", new long[] {5_000, 7_000}, new String[] {"100", "130"});

        Candle second = folded.candles().get(1);
        assertThat(second.open()).isEqualByComparingTo("100"); // continuity: the previous close
        assertThat(second.high()).isEqualByComparingTo("130");
        assertThat(second.low()).isEqualByComparingTo("100");
        assertThat(second.close()).isEqualByComparingTo("130");
        assertThat(folded.candles().get(2).open()).isEqualByComparingTo("130");
    }

    @Test
    void aSpikeInTheMiddleOfACandleIsKeptAsAWick() {
        RoundPath.Folded folded = fold(0, 180_000, 180_000, "100", new long[] {1_000, 3_000, 5_000},
                new String[] {"100", "200", "100"});

        Candle first = folded.candles().get(0);
        assertThat(first.open()).isEqualByComparingTo("100");
        assertThat(first.high()).isEqualByComparingTo("200");
        assertThat(first.low()).isEqualByComparingTo("100");
        assertThat(first.close()).isEqualByComparingTo("100");
    }

    @Test
    void aDipBelowBothOpenAndCloseIsKeptAsALowWick() {
        RoundPath.Folded folded = fold(0, 180_000, 180_000, "100", new long[] {1_000, 2_000, 3_000},
                new String[] {"100", "80", "100"});

        assertThat(folded.candles().get(0).low()).isEqualByComparingTo("80");
    }

    @Test
    void candlesWithoutTicksAreFlatAtTheCarriedPrice() {
        // ticks only in candle 0 (price 105) and candle 5 (price 95): candles 1-4 carry 105, 6+ carry 95
        RoundPath.Folded folded = fold(0, 180_000, 180_000, "100", new long[] {2_000, 33_000},
                new String[] {"105", "95"});

        List<Candle> path = folded.candles();
        assertThat(path.get(0).close()).isEqualByComparingTo("105");
        for (int i = 1; i <= 4; i++) {
            Candle c = path.get(i);
            assertThat(c.open()).isEqualByComparingTo("105");
            assertThat(c.high()).isEqualByComparingTo("105");
            assertThat(c.low()).isEqualByComparingTo("105");
            assertThat(c.close()).isEqualByComparingTo("105");
        }
        assertThat(path.get(5).open()).isEqualByComparingTo("105"); // 33 s is in (30 s, 36 s]
        assertThat(path.get(5).low()).isEqualByComparingTo("95");
        assertThat(path.get(29).close()).isEqualByComparingTo("95");
    }

    @Test
    void aTickExactlyOnACandleEdgeBelongsToTheCandleThatEndsThere() {
        RoundPath.Folded folded = fold(0, 180_000, 180_000, "100", new long[] {6_000}, new String[] {"110"});

        assertThat(folded.candles().get(0).close()).isEqualByComparingTo("110");
        assertThat(folded.candles().get(1).open()).isEqualByComparingTo("110");
    }

    @Test
    void aPathReadWhileTheRoundRunsIsThePrefixOfTheFinalOne() {
        Random random = new Random(7);
        long[] at = new long[60];
        String[] prices = new String[60];
        double price = 84_000;
        for (int i = 0; i < 60; i++) {
            at[i] = 3_000L * (i + 1);
            price += random.nextGaussian() * 20;
            prices[i] = Double.toString(Math.round(price * 100) / 100.0);
        }
        List<Candle> whole = fold(0, 180_000, 180_000, "84000", at, prices).candles();

        for (long now : new long[] {0, 1_000, 6_000, 45_500, 100_000, 179_999}) {
            int known = (int) java.util.Arrays.stream(at).filter(t -> t <= now).count();
            List<Candle> part = fold(0, 180_000, now, "84000", java.util.Arrays.copyOf(at, known),
                    java.util.Arrays.copyOf(prices, known)).candles();

            int whole6 = (int) Math.max(1, Math.ceil(now / 6_000.0));
            assertThat(part).hasSize(whole6);
            for (int i = 0; i < part.size() - 1; i++) { // finished candles do not change when time passes
                assertThat(part.get(i)).isEqualTo(whole.get(i));
            }
            assertThat(part.get(part.size() - 1).closeTimeMs()).isEqualTo(Math.min(now, part.get(part.size() - 1).openTimeMs() + 6_000));
        }
    }

    @Test
    void randomWalksKeepEveryPropertyOfThePath() {
        Random random = new Random(2026_10_01);
        long[] windows = {180_000L, 900_000L, 3_600_000L, 86_400_000L, 100_000L, 7_777_000L, 59_000L};
        for (long length : windows) {
            for (int trial = 0; trial < 20; trial++) {
                long start = 1_790_000_000_000L + random.nextInt(1_000_000);
                long end = start + length;
                int ticks = 1 + random.nextInt((int) Math.min(5_000, length / 1_000));
                long[] at = new long[ticks];
                long[] px = new long[ticks];
                long t = start;
                double price = 1 + random.nextInt(100_000);
                for (int i = 0; i < ticks; i++) {
                    t += 1 + random.nextInt((int) Math.max(1, (end - t) / (ticks - i)) * 2);
                    at[i] = Math.min(t, end);
                    price = Math.max(0.01, price * (1 + random.nextGaussian() * 0.002));
                    px[i] = px(BigDecimal.valueOf(price).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
                }
                java.util.Arrays.sort(at);
                long open = px(Double.toString(price * (1 + random.nextGaussian() * 0.001) + 0.01));

                RoundPath.Folded folded = RoundPath.fold(BTC, start, end, end, open, at, px);

                assertProperties(folded, start, end, open, at, px);
            }
        }
    }

    private static void assertProperties(RoundPath.Folded folded, long start, long end, long open, long[] at, long[] px) {
        List<Candle> path = folded.candles();
        assertThat(path.size()).isBetween(1, RoundPath.MAX_CANDLES);
        // first candle opens at the open price, last closes at the close price
        assertThat(path.get(0).open()).isEqualByComparingTo(BTC.toEngine(Px.decode(open)));
        long expectedClose = at.length == 0 ? open : px[px.length - 1];
        assertThat(path.get(path.size() - 1).close()).isEqualByComparingTo(BTC.toEngine(Px.decode(expectedClose)));
        assertThat(folded.closePx()).isEqualTo(expectedClose);
        // equal length (the last one may be shorter), contiguous, ending at the end of the window
        long step = path.get(0).closeTimeMs() - path.get(0).openTimeMs();
        for (int i = 0; i < path.size(); i++) {
            Candle c = path.get(i);
            assertThat(c.openTimeMs()).isEqualTo(start + i * step);
            assertThat(c.closeTimeMs() - c.openTimeMs()).isLessThanOrEqualTo(step);
            if (i < path.size() - 1) {
                assertThat(c.closeTimeMs() - c.openTimeMs()).isEqualTo(step);
                // continuity: each candle opens where the previous one closed
                assertThat(path.get(i + 1).open()).isEqualByComparingTo(c.close());
            }
            assertThat(c.open()).isBetween(c.low(), c.high());
            assertThat(c.close()).isBetween(c.low(), c.high());
        }
        assertThat(path.get(path.size() - 1).closeTimeMs()).isEqualTo(end);
        // every tick is covered by the candle whose interval holds it, and the candle's extremes are exactly those of its ticks
        for (int i = 0; i < at.length; i++) {
            int index = (int) Math.min(path.size() - 1, Math.max(0, (at[i] - start - 1) / step));
            Candle c = path.get(index);
            BigDecimal price = BTC.toEngine(Px.decode(px[i]));
            assertThat(price).as("tick %d at %d in candle %d", i, at[i], index).isBetween(c.low(), c.high());
        }
        for (int i = 0; i < path.size(); i++) {
            Candle c = path.get(i);
            BigDecimal low = c.open();
            BigDecimal high = c.open();
            for (int k = 0; k < at.length; k++) {
                if (at[k] > c.openTimeMs() && at[k] <= c.closeTimeMs()) {
                    BigDecimal price = BTC.toEngine(Px.decode(px[k]));
                    low = low.min(price);
                    high = high.max(price);
                }
            }
            assertThat(c.low()).isEqualByComparingTo(low);
            assertThat(c.high()).isEqualByComparingTo(high);
        }
    }
}
