package crowdvsmarket.server.prices;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Bridges a gap between two live ticks with the bars (1-minute candles) of a source whose history is trade prices —
 * all or nothing. A bridge answers what a redeploy does to a running round: the server is down for a few minutes
 * (no poll, no tick), the market kept trading, and the candles say how.
 *
 * <p><b>What a bridge is made of.</b> Every bar that overlaps the gap puts its two extremes and its close into it
 * ({@link PriceSource.Bar#shapePoints}: the extremes a third and two thirds into the bar, the close at the bar's
 * end — a minute gives points at +20 s, +40 s and +59.999 s), as far as they fall strictly inside the gap. That is what
 * the liquidation check needs from the gap, the highest and the lowest price of every minute, and nothing else: the
 * bar's open is the first trade of its minute, not the price at its start, and is left out (the previous bar's close
 * one millisecond earlier is that price). A boundary price of a round that falls inside the gap stays what it was
 * made by the previous fix, the close of the bar before the boundary ({@code extra}: the points the source already
 * priced at the boundaries).
 *
 * <p><b>The conditions</b>, each a reason to leave the gap a hole (the round is retried, and refunded when the minutes of
 * its source are over, an hour after its end at the latest, as without a bridge):
 * <ol>
 *   <li><b>Both neighbours are live ticks</b> (the caller's business: a gap with no live tick on one side has nothing
 *       to check the trade prices against).</li>
 *   <li><b>The gap is not longer than {@code maxHealMs}</b> (measured between the two live ticks). The checks below
 *       look at the two ends only; the longer the gap, the less they say about the middle and the more minutes of the
 *       round rest on trade prices instead of marks.</li>
 *   <li><b>The source keeps candles that old.</b> Kraken serves the latest 720 minutes and nothing before them: a gap that
 *       begins before the first candle there is has no candles — they do not exist, which is not the same as a minute
 *       without trades — and is refused at once.</li>
 *   <li><b>Every minute that overlaps the gap has a bar</b>: a closed candle with trades, from the minute of the part
 *       to fill to the minute of the right tick, with none missing. A minute without trades (a filler {@code n = 0},
 *       a row that is absent) is not covered: the asset was quiet, the quiet-carry rule is the one that may carry a
 *       flat price across it, and a trade price from before the quiet minutes is never put there. With every minute
 *       covered the points are at most {@code 20.001 s} apart, inside the feed's maximum gap of 30 s; with one bar
 *       missing there are 80 s between them and the gap would still be a hole — the placement and the rule fit, and
 *       {@link #bridge} checks the spacing itself as well, so a feed configured with a maximum gap below 20.001 s
 *       bridges nothing instead of leaving holes behind.</li>
 *   <li><b>The basis</b>: each live neighbour's price lies inside the [low, high] of the bar of its own minute, widened by
 *       {@code maxBasis} (relative to the live price). Marks and trades are two prices of one market — the mark is a
 *       median of the oracle, the book and other exchanges, a trade is a trade — and they agree within a few basis
 *       points; a neighbour that sits outside the trades of its own minute by more than {@code maxBasis} says that
 *       this market's marks and trades have come apart, and splicing one into the other would put prices into the
 *       round that its marks never had. The comparison is with the <i>range</i> of the minute, not with its open or
 *       with the previous close: a mark and a trade of the same minute differ by the minute's own movement
 *       (measured 2026-10-02 over 41 minutes of the stand's marks, against the open of the minute: median 1.7 bp,
 *       95th percentile 13 bp, maximum 45 bp), while the mark's distance to the range of its own minute was never
 *       above 5.2 bp (docs/research/hl_multi_asset_live.md §13.4).</li>
 * </ol>
 */
final class GapBridge {

    /**
     * The gap.
     *
     * @param leftAtMs   the time of the live tick on the left (the real one, when the left neighbour in the ring is a copy
     *                   of it) — the bar of its minute is what its price is checked against
     * @param fillFromMs where the part to fill starts: the left neighbour's time in the ring (a window's start when the
     *                   left tick is fresh at it, a pinned boundary, behind which nothing is merged)
     * @param rightAtMs  the time of the live tick on the right
     */
    record Gap(long leftAtMs, BigDecimal leftPx, long fillFromMs, long rightAtMs, BigDecimal rightPx) {
    }

    /** The points that bridge the gap, or why the gap was left alone. */
    record Result(List<PricePoint> points, String refusal) {

        boolean ok() {
            return refusal == null;
        }

        static Result bridged(List<PricePoint> points) {
            return new Result(points, null);
        }

        static Result refused(String why) {
            return new Result(List.of(), why);
        }
    }

    private GapBridge() {
    }

    /**
     * @param bars        the closed bars with trades the source answered with, oldest first
     * @param extra       points the source priced exactly at round boundaries; those inside the gap are added
     * @param maxGapMs    the feed's maximum gap: no two neighbouring points of the result (the gap's ends included) may
     *                    be further apart
     * @param maxHealMs   the longest gap that is bridged; 0 switches bridging off
     * @param maxBasis    how far a live price may lie outside the range of its own bar, relative to the live price
     * @param usable      whether the engine can hold a price (the same check a history point passes)
     */
    static Result bridge(List<PriceSource.Bar> bars, Gap gap, List<PricePoint> extra, long maxGapMs, long maxHealMs,
                         BigDecimal maxBasis, Predicate<PricePoint> usable) {
        return bridge(bars, gap, extra, maxGapMs, maxHealMs, maxBasis, usable, PriceSource.History.NO_LIMIT);
    }

    /**
     * @param coveredFromMs the oldest moment the source keeps candles for ({@link PriceSource.History#coveredFromMs}): a
     *                      gap whose left live tick is older than that has no candles to be bridged with, and is refused
     *                      at once, before the bars are looked at — they are not missing, they do not exist
     */
    static Result bridge(List<PriceSource.Bar> bars, Gap gap, List<PricePoint> extra, long maxGapMs, long maxHealMs,
                         BigDecimal maxBasis, Predicate<PricePoint> usable, long coveredFromMs) {
        long length = gap.rightAtMs() - gap.leftAtMs();
        if (maxHealMs <= 0) {
            return Result.refused("bridging is switched off (max-heal-ms is 0)");
        }
        if (length > maxHealMs) {
            return Result.refused("the gap of " + seconds(length) + " is longer than the " + seconds(maxHealMs)
                    + " a gap may be bridged");
        }
        if (gap.leftAtMs() < coveredFromMs) {
            return Result.refused("the live tick at " + at(gap.leftAtMs()) + " is older than the candles the source keeps, "
                    + "which begin at " + at(coveredFromMs) + ": there is nothing to bridge the gap with");
        }
        PriceSource.Bar leftBar = barAt(bars, gap.leftAtMs());
        if (leftBar == null) {
            return Result.refused("no candle with trades for the minute of the live tick at " + at(gap.leftAtMs()));
        }
        PriceSource.Bar rightBar = barAt(bars, gap.rightAtMs());
        if (rightBar == null) {
            return Result.refused("no candle with trades for the minute of the live tick at " + at(gap.rightAtMs()));
        }

        // every minute from the one that holds the start of the part to fill to the one that holds the right tick
        List<PriceSource.Bar> chain = new ArrayList<>();
        long cursor = gap.fillFromMs();
        while (true) {
            PriceSource.Bar bar = barAt(bars, cursor);
            if (bar == null) {
                return Result.refused("the candle of the minute at " + at(cursor)
                        + " is missing (no trades, or not closed yet)");
            }
            chain.add(bar);
            if (bar.endMs() >= gap.rightAtMs()) {
                break;
            }
            cursor = bar.endMs() + 1;
        }

        String basis = basisProblem(gap.leftPx(), leftBar, "left", gap.leftAtMs(), maxBasis);
        if (basis == null) {
            basis = basisProblem(gap.rightPx(), rightBar, "right", gap.rightAtMs(), maxBasis);
        }
        if (basis != null) {
            return Result.refused(basis);
        }

        TreeMap<Long, PricePoint> points = new TreeMap<>();
        for (PricePoint p : extra) { // first: at a boundary the price of the candle before it beats a wick at that very time
            if (inside(gap, p.atMs())) {
                points.putIfAbsent(p.atMs(), p);
            }
        }
        for (PriceSource.Bar bar : chain) {
            for (PricePoint p : bar.shapePoints()) {
                if (inside(gap, p.atMs())) {
                    points.putIfAbsent(p.atMs(), p);
                }
            }
        }
        long previous = gap.fillFromMs();
        for (Map.Entry<Long, PricePoint> e : points.entrySet()) {
            if (!usable.test(e.getValue())) {
                return Result.refused("the price " + e.getValue().raw().toPlainString() + " at " + at(e.getKey())
                        + " is one the engine cannot hold");
            }
            if (e.getKey() - previous > maxGapMs) {
                return Result.refused("the bridge would leave " + seconds(e.getKey() - previous) + " between "
                        + at(previous) + " and " + at(e.getKey()) + " without a point (the maximum gap is "
                        + seconds(maxGapMs) + ")");
            }
            previous = e.getKey();
        }
        if (gap.rightAtMs() - previous > maxGapMs) {
            return Result.refused("the bridge would leave " + seconds(gap.rightAtMs() - previous) + " before the live "
                    + "tick at " + at(gap.rightAtMs()) + " without a point (the maximum gap is " + seconds(maxGapMs)
                    + ")");
        }
        return Result.bridged(List.copyOf(points.values()));
    }

    /** How far {@code price} lies outside {@code [low, high]} of the bar, relative to the price; 0 inside. */
    static BigDecimal distanceOutside(BigDecimal price, PriceSource.Bar bar) {
        BigDecimal outside = bar.low().subtract(price).max(price.subtract(bar.high())).max(BigDecimal.ZERO);
        return outside.divide(price, 10, RoundingMode.HALF_UP);
    }

    private static String basisProblem(BigDecimal price, PriceSource.Bar bar, String side, long atMs,
                                       BigDecimal maxBasis) {
        BigDecimal distance = distanceOutside(price, bar);
        if (distance.compareTo(maxBasis) <= 0) {
            return null;
        }
        return "the " + side + " live price " + price.toPlainString() + " at " + at(atMs) + " is "
                + distance.movePointRight(4).setScale(1, RoundingMode.HALF_UP).toPlainString()
                + " bp outside the " + bar.low().toPlainString() + " to " + bar.high().toPlainString()
                + " its own minute traded at (the limit is "
                + maxBasis.movePointRight(4).setScale(1, RoundingMode.HALF_UP).toPlainString() + " bp)";
    }

    private static boolean inside(Gap gap, long atMs) {
        return atMs > gap.fillFromMs() && atMs < gap.rightAtMs();
    }

    /** The bar that holds {@code atMs} (bars are sorted by their start and do not overlap), or null. */
    static PriceSource.Bar barAt(List<PriceSource.Bar> bars, long atMs) {
        int lo = 0;
        int hi = bars.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            PriceSource.Bar bar = bars.get(mid);
            if (atMs < bar.startMs()) {
                hi = mid - 1;
            } else if (atMs > bar.endMs()) {
                lo = mid + 1;
            } else {
                return bar;
            }
        }
        return null;
    }

    private static String at(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }

    private static String seconds(long ms) {
        return ms % 1000 == 0 ? (ms / 1000) + " s" : (ms / 1000.0) + " s";
    }
}
