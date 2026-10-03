package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * {@link GapBridge}: all or nothing, the conditions one by one, and the placement of the points — the points of
 * fully covered gaps are never further apart than 20.001 s, those of a gap with a bar missing would be 80 s apart.
 */
class GapBridgeTest {

    private static final long MINUTE = 60_000L;
    /** A minute boundary. */
    private static final long M0 = 1_790_883_600_000L;
    private static final long MAX_GAP = 30_000L;
    private static final long CAP = 1_800_000L;
    private static final BigDecimal BASIS = new BigDecimal("0.0015");
    private static final Predicate<PricePoint> ANY = p -> true;

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    /** {@code count} bars of 100.00-ish from the minute {@code from}: open 100, low 99, high 101, close 100.5 (up). */
    private static List<PriceSource.Bar> bars(long from, int count) {
        List<PriceSource.Bar> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long start = from + i * MINUTE;
            out.add(new PriceSource.Bar(start, start + MINUTE - 1, bd("100"), bd("101"), bd("99"), bd("100.5")));
        }
        return out;
    }

    private static GapBridge.Gap gap(long leftAt, long rightAt) {
        return new GapBridge.Gap(leftAt, bd("100"), leftAt, rightAt, bd("100"));
    }

    private static GapBridge.Result bridge(List<PriceSource.Bar> bars, GapBridge.Gap gap) {
        return GapBridge.bridge(bars, gap, List.of(), MAX_GAP, CAP, BASIS, ANY);
    }

    /** The largest distance between neighbouring points, the two ends of the gap counted. */
    private static long largestSpacing(GapBridge.Gap gap, List<PricePoint> points) {
        long largest = 0;
        long previous = gap.fillFromMs();
        for (PricePoint p : points) {
            largest = Math.max(largest, p.atMs() - previous);
            previous = p.atMs();
        }
        return Math.max(largest, gap.rightAtMs() - previous);
    }

    // ---------------------------------------------------------------------------- a bar and its points

    @Test
    void aBarPutsItsExtremesAThirdAndTwoThirdsIntoItsMinuteAndItsCloseAtItsEndTheLowFirstWhenItClosedUp() {
        PriceSource.Bar up = new PriceSource.Bar(M0, M0 + 59_999, bd("100"), bd("101"), bd("99"), bd("100.5"));
        PriceSource.Bar down = new PriceSource.Bar(M0, M0 + 59_999, bd("100"), bd("101"), bd("99"), bd("99.5"));

        assertThat(up.shapePoints()).extracting(PricePoint::atMs).containsExactly(M0 + 20_000, M0 + 40_000, M0 + 59_999);
        assertThat(up.shapePoints()).extracting(p -> p.raw().toPlainString()).containsExactly("99", "101", "100.5");
        assertThat(down.shapePoints()).extracting(p -> p.raw().toPlainString()).containsExactly("101", "99", "99.5");
        assertThat(up.widthMs()).isEqualTo(60_000L);
        assertThat(up.contains(M0)).isTrue();
        assertThat(up.contains(M0 + 59_999)).isTrue();
        assertThat(up.contains(M0 + 60_000)).isFalse();
        assertThat(up.contains(M0 - 1)).isFalse();
        // a flat bar closed "up" (close == open): the low first, harmless
        assertThat(new PriceSource.Bar(M0, M0 + 59_999, bd("5"), bd("5"), bd("5"), bd("5")).closedUp()).isTrue();
    }

    // ------------------------------------------------------------------------------- full coverage works

    @Test
    void aFullyCoveredGapGivesPointsNeverFurtherApartThanTwentySecondsWhereverItStartsAndEnds() {
        List<PriceSource.Bar> bars = bars(M0 - 5 * MINUTE, 30);
        int bridged = 0;
        for (long leftOffset = 0; leftOffset < MINUTE; leftOffset += 1_700) {
            for (long length = 31_000; length <= 20 * MINUTE; length += 13_700) {
                long left = M0 + leftOffset;
                long right = left + length;
                GapBridge.Gap gap = gap(left, right);

                GapBridge.Result result = bridge(bars, gap);

                assertThat(result.ok()).as("left +%d ms, %d ms long: %s", leftOffset, length, result.refusal()).isTrue();
                assertThat(result.points()).allSatisfy(p -> assertThat(p.atMs()).isStrictlyBetween(left, right));
                assertThat(largestSpacing(gap, result.points())).as("left +%d ms, %d ms long", leftOffset, length)
                        .isLessThanOrEqualTo(20_001L);
                bridged++;
            }
        }
        assertThat(bridged).isGreaterThan(500);
    }

    @Test
    void aBarMissingFromTheAnswerBreaksTheGapEvenWithTheRestPresent() {
        List<PriceSource.Bar> all = bars(M0 - 2 * MINUTE, 12);
        long left = M0 + 20_000L;
        long right = M0 + 7 * MINUTE + 10_000L;
        GapBridge.Gap gap = gap(left, right);
        assertThat(bridge(all, gap).ok()).isTrue();

        for (int missing = 0; missing < all.size(); missing++) {
            PriceSource.Bar gone = all.get(missing);
            List<PriceSource.Bar> without = new ArrayList<>(all);
            without.remove(missing);
            boolean inTheChain = gone.endMs() >= left && gone.startMs() <= right;

            GapBridge.Result result = bridge(without, gap);

            assertThat(result.ok()).as("without the bar of minute %d", gone.startMs() / MINUTE - M0 / MINUTE)
                    .isEqualTo(!inTheChain);
            if (inTheChain && gone.startMs() > left && gone.endMs() < right) {
                // and the placement is what makes the rule agree: merging what is left anyway would leave 80 s
                List<PricePoint> leftovers = new ArrayList<>();
                for (PriceSource.Bar bar : without) {
                    for (PricePoint p : bar.shapePoints()) {
                        if (p.atMs() > left && p.atMs() < right) {
                            leftovers.add(p);
                        }
                    }
                }
                assertThat(largestSpacing(gap, leftovers)).isGreaterThan(MAX_GAP); // 80.001 s, 70.001 s next to the end
            }
        }
    }

    @Test
    void theBarOfTheLeftLiveTickIsNeededToCheckItEvenWhenTheMinutesAfterItAreAllThere() {
        List<PriceSource.Bar> bars = bars(M0, 5);
        GapBridge.Gap gap = new GapBridge.Gap(M0 - 5_000L, bd("100"), M0 + 1_000L, M0 + 150_000L, bd("100"));

        GapBridge.Result result = bridge(bars, gap); // the live tick at M0 - 5 s belongs to a minute with no bar

        assertThat(result.ok()).isFalse();
        assertThat(result.refusal()).contains("no candle with trades for the minute of the live tick");
    }

    // ------------------------------------------------------------------------------------------- the basis

    @Test
    void aLivePriceInsideTheRangeOfItsOwnMinuteAgrees() {
        // range 99..101: 99, 100 and 101 are all in it
        for (String price : List.of("99", "100", "101")) {
            GapBridge.Gap gap = new GapBridge.Gap(M0 + 10_000L, bd(price), M0 + 10_000L, M0 + 100_000L, bd(price));
            assertThat(bridge(bars(M0, 3), gap).ok()).as(price).isTrue();
        }
    }

    @Test
    void theLimitIsRelativeToTheLivePriceAndInclusive() {
        // 15 bp of 101.15 is 0.151725: 101.15 is 0.15 above the high of 101, inside; 101.16 is 0.16 above, outside
        List<PriceSource.Bar> bars = bars(M0, 3);
        long left = M0 + 10_000L;
        long right = M0 + 100_000L;

        GapBridge.Result inside = bridge(bars, new GapBridge.Gap(left, bd("101.15"), left, right, bd("100")));
        GapBridge.Result outside = bridge(bars, new GapBridge.Gap(left, bd("101.16"), left, right, bd("100")));

        assertThat(inside.ok()).isTrue();
        assertThat(outside.ok()).isFalse();
        assertThat(outside.refusal()).contains("left live price 101.16").contains("bp outside").contains("limit is 15.0 bp");
        // exactly on the limit: the live price 90 is 9 under the low of 99, 9 / 90 = 0.1 of the price
        GapBridge.Gap far = new GapBridge.Gap(left, bd("90"), left, right, bd("100"));
        assertThat(GapBridge.bridge(bars, far, List.of(), MAX_GAP, CAP, new BigDecimal("0.1"), ANY).ok()).isTrue();
        assertThat(GapBridge.bridge(bars, far, List.of(), MAX_GAP, CAP, new BigDecimal("0.0999999999"), ANY).ok())
                .isFalse();
    }

    @Test
    void theRightTickIsCheckedAgainstItsOwnMinuteToo() {
        GapBridge.Gap gap = new GapBridge.Gap(M0 + 10_000L, bd("100"), M0 + 10_000L, M0 + 100_000L, bd("98"));

        GapBridge.Result result = bridge(bars(M0, 3), gap);

        assertThat(result.ok()).isFalse();
        assertThat(result.refusal()).contains("right live price 98").contains("bp outside");
    }

    @Test
    void aZeroToleranceIsStrictButLegal() {
        GapBridge.Gap gap = new GapBridge.Gap(M0 + 10_000L, bd("101.0001"), M0 + 10_000L, M0 + 100_000L, bd("100"));

        assertThat(GapBridge.bridge(bars(M0, 3), gap, List.of(), MAX_GAP, CAP, BigDecimal.ZERO, ANY).ok()).isFalse();
        assertThat(GapBridge.bridge(bars(M0, 3), gap(M0 + 10_000L, M0 + 100_000L), List.of(), MAX_GAP, CAP,
                BigDecimal.ZERO, ANY).ok()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------- the cap

    @Test
    void aGapOnTheCapIsBridgedOneMillisecondLongerIsNot() {
        List<PriceSource.Bar> bars = bars(M0 - MINUTE, 40);
        long left = M0 + 5_000L;

        assertThat(bridge(bars, gap(left, left + CAP)).ok()).isTrue();
        GapBridge.Result over = bridge(bars, gap(left, left + CAP + 1));

        assertThat(over.ok()).isFalse();
        assertThat(over.refusal()).contains("longer than the 1800 s");
    }

    @Test
    void theCapIsMeasuredBetweenTheTwoLiveTicksNotFromTheStartOfThePartThatIsFilled() {
        // a pinned boundary 10 minutes into a 35-minute gap leaves 25 minutes to fill, but the gap is 35 minutes
        List<PriceSource.Bar> bars = bars(M0 - MINUTE, 45);
        long left = M0 + 5_000L;
        GapBridge.Gap gap = new GapBridge.Gap(left, bd("100"), left + 10 * MINUTE, left + 35 * MINUTE, bd("100"));

        assertThat(bridge(bars, gap).ok()).isFalse();
    }

    @Test
    void aGapThatBeginsBeforeTheCandlesTheSourceKeepsIsRefusedAtOnceAndNotForMissingBars() {
        // Kraken keeps the latest 720 minutes: a live tick older than the first of them has no candles, and none are "missing"
        GapBridge.Gap gap = gap(M0 + 10_000L, M0 + 100_000L);
        long keptFrom = M0 + MINUTE; // the source's first candle is the one after the left tick's minute

        GapBridge.Result withBars = GapBridge.bridge(bars(M0, 3), gap, List.of(), MAX_GAP, CAP, BASIS, ANY, keptFrom);
        GapBridge.Result withoutBars = GapBridge.bridge(List.of(), gap, List.of(), MAX_GAP, CAP, BASIS, ANY, keptFrom);

        assertThat(withBars.ok()).as("whatever bars the answer held").isFalse();
        assertThat(withBars.refusal()).contains("older than the candles the source keeps").contains("2026-");
        assertThat(withoutBars.refusal()).as("the reason is the limit, not a bar that is not there")
                .contains("older than the candles the source keeps").doesNotContain("no candle with trades");
    }

    @Test
    void aGapThatBeginsWhereTheSourceKeepsItsFirstCandleIsBridgedAndNoLimitIsTheOldBehaviour() {
        GapBridge.Gap gap = gap(M0 + 10_000L, M0 + 100_000L);

        assertThat(GapBridge.bridge(bars(M0, 3), gap, List.of(), MAX_GAP, CAP, BASIS, ANY, M0 + 10_000L).ok())
                .as("the candles are kept from the very moment of the left tick").isTrue();
        assertThat(GapBridge.bridge(bars(M0, 3), gap, List.of(), MAX_GAP, CAP, BASIS, ANY, M0).ok()).isTrue();
        assertThat(GapBridge.bridge(bars(M0, 3), gap, List.of(), MAX_GAP, CAP, BASIS, ANY, PriceSource.History.NO_LIMIT).ok())
                .isTrue();
        assertThat(GapBridge.bridge(bars(M0, 3), gap, List.of(), MAX_GAP, CAP, BASIS, ANY, M0 + 10_001L).ok())
                .as("a millisecond later it is not").isFalse();
    }

    @Test
    void aCapOfZeroSwitchesBridgingOff() {
        GapBridge.Result result = GapBridge.bridge(bars(M0, 3), gap(M0 + 10_000L, M0 + 100_000L), List.of(), MAX_GAP, 0,
                BASIS, ANY);

        assertThat(result.ok()).isFalse();
        assertThat(result.refusal()).contains("switched off");
    }

    // --------------------------------------------------------------------------- the rest of the placement

    @Test
    void aFeedWhoseMaximumGapIsBelowTheSpacingOfAMinutesPointsBridgesNothing() {
        GapBridge.Gap gap = gap(M0 + 10_000L, M0 + 150_000L);

        GapBridge.Result tight = GapBridge.bridge(bars(M0, 4), gap, List.of(), 20_000L, CAP, BASIS, ANY);
        GapBridge.Result enough = GapBridge.bridge(bars(M0, 4), gap, List.of(), 20_001L, CAP, BASIS, ANY);

        assertThat(tight.ok()).isFalse();
        assertThat(tight.refusal()).contains("would leave").contains("without a point");
        assertThat(enough.ok()).isTrue();
    }

    @Test
    void nothingBehindTheStartOfThePartToFillIsMergedAndTheSpacingIsMeasuredFromThere() {
        // the gap began at M0 + 10 s; a boundary pinned at M0 + 50 s means only what comes after it may be merged
        GapBridge.Gap gap = new GapBridge.Gap(M0 + 10_000L, bd("100"), M0 + 50_000L, M0 + 100_000L, bd("100"));

        GapBridge.Result result = bridge(bars(M0, 3), gap);

        assertThat(result.ok()).isTrue();
        assertThat(result.points()).extracting(PricePoint::atMs).containsExactly(M0 + 59_999L, M0 + 80_000L);
        assertThat(largestSpacing(gap, result.points())).isLessThanOrEqualTo(20_001L);
    }

    @Test
    void theBoundaryPricesTheSourcePricedInsideTheGapAreKeptTheOnesOutsideAreNot() {
        GapBridge.Gap gap = gap(M0 + 10_000L, M0 + 100_000L);
        PricePoint inside = new PricePoint(M0 + 30_000L, bd("100.2"));
        PricePoint before = new PricePoint(M0 + 5_000L, bd("100.3"));
        PricePoint after = new PricePoint(M0 + 100_000L, bd("100.4")); // at the right live tick itself: that is a tick

        GapBridge.Result result = GapBridge.bridge(bars(M0, 3), gap, List.of(before, inside, after), MAX_GAP, CAP, BASIS,
                ANY);

        assertThat(result.ok()).isTrue();
        assertThat(result.points()).extracting(PricePoint::atMs).containsExactly(M0 + 20_000L, M0 + 30_000L,
                M0 + 40_000L, M0 + 59_999L, M0 + 80_000L);
        assertThat(result.points().get(1)).isEqualTo(inside);
    }

    @Test
    void aBoundaryPriceBeatsAWickThatFallsOnTheVeryTimeOfTheBoundary() {
        // a boundary at +20 s into a minute — where the low of that minute is placed: the price at the boundary is the
        // close of the candle before it (what the source priced), not the low
        GapBridge.Gap gap = gap(M0 + 10_000L, M0 + 100_000L);
        PricePoint boundary = new PricePoint(M0 + 20_000L, bd("100.25"));

        GapBridge.Result result = GapBridge.bridge(bars(M0, 3), gap, List.of(boundary), MAX_GAP, CAP, BASIS, ANY);

        assertThat(result.ok()).isTrue();
        assertThat(result.points().stream().filter(p -> p.atMs() == M0 + 20_000L).toList()).containsExactly(boundary);
    }

    @Test
    void aPriceTheEngineCannotHoldRefusesTheGapAsAWhole() {
        GapBridge.Gap gap = gap(M0 + 10_000L, M0 + 100_000L);

        GapBridge.Result result = GapBridge.bridge(bars(M0, 3), gap, List.of(), MAX_GAP, CAP, BASIS,
                p -> p.raw().compareTo(bd("99")) != 0); // the lows of the bars are 99

        assertThat(result.ok()).isFalse();
        assertThat(result.refusal()).contains("engine cannot hold");
    }

    @Test
    void aBridgeIsMadeOfTheBarsThatOverlapTheGapOnlyAndNeverOfTheBarsNextToIt() {
        // 20 bars; the gap is inside minutes 3..5: the others are not looked at (their prices are absurd)
        List<PriceSource.Bar> bars = new ArrayList<>(bars(M0, 20));
        for (int i : new int[] {0, 1, 2, 6, 7}) {
            long start = M0 + i * MINUTE;
            bars.set(i, new PriceSource.Bar(start, start + MINUTE - 1, bd("1"), bd("2"), bd("0.5"), bd("1")));
        }

        GapBridge.Result result = bridge(bars, gap(M0 + 3 * MINUTE + 10_000L, M0 + 5 * MINUTE + 50_000L));

        assertThat(result.ok()).isTrue();
        assertThat(result.points()).allSatisfy(p -> assertThat(p.raw()).isGreaterThanOrEqualTo(bd("99")));
    }

    @Test
    void aBarAtTheEdgeOfTheGapGivesOnlyThePointsThatFallInsideItTheLiveTicksSawTheRestOfItsMinute() {
        // the left tick is 45 s into its minute: of that bar only the close is after it; the right tick is 25 s into
        // its minute: of that bar only the first extreme (at +20 s) is before it. A trade wick the bar's minute had at
        // +55 s of the right bar is not merged: the marks of the live ticks after the right tick cover that time
        List<PriceSource.Bar> bars = bars(M0, 3);
        GapBridge.Gap gap = gap(M0 + 45_000L, M0 + 2 * MINUTE + 25_000L);

        GapBridge.Result result = bridge(bars, gap);

        assertThat(result.ok()).isTrue();
        assertThat(result.points()).extracting(PricePoint::atMs).containsExactly(M0 + 59_999L, M0 + 80_000L,
                M0 + 100_000L, M0 + 119_999L, M0 + 140_000L);
        assertThat(result.points().get(4).raw()).isEqualByComparingTo("99"); // the low of the right bar, first (it closed up)
    }

    @Test
    void theBarThatStartsExactlyAtTheRightTickIsNeededForItsCheckButGivesNoPoint() {
        List<PriceSource.Bar> bars = bars(M0, 3);
        long right = M0 + 2 * MINUTE; // the minute that starts here holds the right tick
        GapBridge.Gap gap = gap(M0 + 70_000L, right);

        GapBridge.Result result = bridge(bars, gap);

        assertThat(result.ok()).isTrue();
        assertThat(result.points()).extracting(PricePoint::atMs).containsExactly(M0 + 80_000L, M0 + 100_000L,
                M0 + 119_999L);
        assertThat(bridge(bars(M0, 2), gap).ok()).isFalse(); // no bar of the minute of the right tick
    }
}
