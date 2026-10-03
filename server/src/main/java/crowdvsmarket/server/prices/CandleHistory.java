package crowdvsmarket.server.prices;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What a source whose history is 1-minute trade candles ({@link PriceSource.HistoryKind#TRADE_PRICES}) says about a
 * range, whichever venue the candles come from: the points of its closed candles with trades, the price at each round
 * boundary, the last trade before the range, and the candles themselves as {@link PriceSource.Bar bars} for
 * {@link GapBridge}. The venue's own JSON is read elsewhere ({@link HyperliquidPriceSource#candles},
 * {@link CoinbasePriceSource}); both end in a list of {@link Candle}.
 *
 * <p><b>What a candle gives.</b> Only a candle <i>with trades</i> holds a price: Hyperliquid's own filler candles for
 * quiet minutes ({@code n = 0}, {@code o = h = l = c =} the previous close, hl_multi_asset_live.md §4.3) say nothing
 * and are skipped; Coinbase has no filler rows, a minute without trades is simply not in its answer. A candle that is
 * still open ({@code T} not yet past — its high and low change by the second) is skipped as well, so that neither a
 * seed nor a bridge quotes the high of the minute in progress as a price.
 *
 * <p><b>Boundary convention: the price at time {@code t} is the last trade at or before {@code t}</b>, the same as
 * for a live tick ({@link LiveMarketPrices}: {@code P(t)} is the last tick with {@code at <= t}). A candle
 * {@code [t, T]} says: its close is the last trade up to {@code T} (a point at {@code T = t + 59 999 ms}); its low
 * and high happened some time inside the minute (two shape points at {@code t + 20 s} and {@code t + 40 s}, the
 * low first when the candle closed up, the high first when it closed down — a chart wants the extremes, not
 * their exact time, which nobody knows: {@link PriceSource.Bar#shapePoints}); its open is the <i>first</i> trade
 * at or after {@code t}, so it is <b>not</b> the price at {@code t}: that is the previous candle's close, one
 * millisecond earlier. Hence a candle that has a predecessor contributes three points; only the first row of an
 * answer, which has no predecessor to take the price at its start from, also contributes its open.
 *
 * <p><b>The boundary price comes from the closed candle before the boundary, not from a shape point.</b> A shape
 * point is a candle's low or high at an invented time; a boundary price taken from one is a wick, and one that
 * falls between two shape points is stale. So {@link #answer} adds, for every boundary it is given, a point
 * at exactly that time with the close of the last candle with trades that closed at or before it — when that
 * was within one candle (60 s) of it; on a minute-aligned boundary that is the close one millisecond earlier.
 * Round boundaries are minute-aligned ({@link RoundAlignmentCheck} refuses to start otherwise).
 */
final class CandleHistory {

    static final long CANDLE_MS = 60_000L;

    private CandleHistory() {
    }

    /**
     * One whole candle. {@code trades} is the venue's count of trades in it ({@code -1} when the venue gives none:
     * only an explicit 0 makes a row a filler).
     */
    record Candle(long t, long closeAt, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                  long trades) {

        /** A minute with trades holds a price; a filler ({@code n = 0}) repeats the previous close. */
        boolean hasTrades() {
            return trades != 0;
        }

        PriceSource.Bar toBar() {
            return new PriceSource.Bar(t, closeAt, open, high, low, close);
        }

        /** {@code T} is past: no trade will change the row any more (hl_multi_asset_live.md §4.1: closed means T < now). */
        boolean closed(long nowMs) {
            return closeAt < nowMs;
        }
    }

    /** The candles sorted by their own time, which is the order the rest of this class needs. */
    static List<Candle> sorted(List<Candle> candles) {
        List<Candle> out = new ArrayList<>(candles);
        out.sort((a, b) -> Long.compare(a.t(), b.t()));
        return out;
    }

    /**
     * What the candles say about {@code [fromMs, toMs]}: the points of its closed candles with trades
     * ({@link #points}), a point at each boundary that the close of a candle before it can price
     * ({@link #boundaryPoint}), the last trade before the range ({@link #lastTradeBefore}), and those
     * candles themselves, all of them ({@link #bars}), for a bridge over a gap between live ticks.
     *
     * @param nowMs the clock: a candle whose {@code T} is not before it is still open and says nothing yet
     */
    static PriceSource.History answer(List<Candle> candles, long fromMs, long toMs, long nowMs, long... boundariesMs) {
        List<Candle> sorted = sorted(candles);
        List<PricePoint> points = points(sorted, fromMs, toMs, nowMs);
        for (long boundary : boundariesMs) {
            if (boundary < fromMs || boundary > toMs) {
                continue;
            }
            Optional<PricePoint> priced = boundaryPoint(sorted, boundary, nowMs);
            if (priced.isPresent() && !priceAlreadyAt(points, boundary)) {
                points.add(priced.get());
            }
        }
        points.sort((a, b) -> Long.compare(a.atMs(), b.atMs()));
        return new PriceSource.History(points, lastTradeBefore(sorted, fromMs, nowMs), bars(sorted, nowMs));
    }

    /** The closed candles with trades as bars, oldest first: what a minute of the market traded at. */
    static List<PriceSource.Bar> bars(List<Candle> candles, long nowMs) {
        List<PriceSource.Bar> bars = new ArrayList<>();
        for (Candle c : candles) {
            if (c.closed(nowMs) && c.hasTrades()) {
                bars.add(c.toBar());
            }
        }
        return bars;
    }

    /**
     * On a minute-aligned boundary the close of the candle before it sits one millisecond earlier: that point
     * <i>is</i> the price at the boundary, with its own true time, and nothing is added (the settlement's
     * {@code sourceTs} stays the time of that close). Anywhere else the last point at or before the boundary is a
     * wick of the minute around it, or a close that is too old to be the price now: a point is added.
     */
    private static boolean priceAlreadyAt(List<PricePoint> points, long boundaryMs) {
        return points.stream().anyMatch(p -> p.atMs() >= boundaryMs - 1 && p.atMs() <= boundaryMs);
    }

    /**
     * The candles as price points inside {@code [fromMs, toMs]}, oldest first, by the boundary convention of the
     * class comment: the close at {@code T}, the two extremes inside the minute, and — for the first row only — the
     * open. The first row's open is not made into a point at or after {@code toMs}. A filler without trades and a
     * candle that is still open are skipped.
     */
    static List<PricePoint> points(List<Candle> candles, long fromMs, long toMs, long nowMs) {
        List<PricePoint> out = new ArrayList<>();
        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            if (!c.closed(nowMs) || !c.hasTrades()) {
                continue;
            }
            if (i == 0 && c.t() < toMs) {
                add(out, c.t(), c.open(), fromMs, toMs); // no earlier candle to take the price at t from
            }
            for (PricePoint p : c.toBar().shapePoints()) { // the extremes inside the minute, the close at its end
                add(out, p.atMs(), p.raw(), fromMs, toMs);
            }
        }
        out.sort((a, b) -> Long.compare(a.atMs(), b.atMs()));
        return out;
    }

    /**
     * The price at {@code boundaryMs} from the candle <i>before</i> it: the close of the newest closed candle
     * with trades that closed at or before the boundary, when that was at most one candle width earlier — "the
     * last trade at or before the boundary", known from the minute candles up to the minute the boundary falls
     * in. Empty when there is none that recent (a quiet asset has no price at the boundary).
     */
    static Optional<PricePoint> boundaryPoint(List<Candle> candles, long boundaryMs, long nowMs) {
        Candle best = null;
        for (Candle c : candles) {
            if (c.closed(nowMs) && c.hasTrades() && c.closeAt() <= boundaryMs && (best == null || c.t() > best.t())) {
                best = c;
            }
        }
        if (best == null || boundaryMs - best.closeAt() > CANDLE_MS) {
            return Optional.empty();
        }
        return Optional.of(new PricePoint(boundaryMs, best.close()));
    }

    /** The close of the newest closed candle with trades that closed before {@code fromMs}, at its closing time. */
    static Optional<PricePoint> lastTradeBefore(List<Candle> candles, long fromMs, long nowMs) {
        Candle best = null;
        for (Candle c : candles) {
            if (c.closed(nowMs) && c.hasTrades() && c.closeAt() < fromMs && (best == null || c.t() > best.t())) {
                best = c;
            }
        }
        return best == null ? Optional.empty() : Optional.of(new PricePoint(best.closeAt(), best.close()));
    }

    private static void add(List<PricePoint> out, long atMs, BigDecimal price, long fromMs, long toMs) {
        if (atMs >= fromMs && atMs <= toMs && price.signum() > 0) {
            out.add(new PricePoint(atMs, price));
        }
    }
}
