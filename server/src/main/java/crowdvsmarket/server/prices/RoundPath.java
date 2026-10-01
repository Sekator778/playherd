package crowdvsmarket.server.prices;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.server.markets.Market;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Folds a round window's ticks into the path the engine replays for liquidation
 * (docs/research/markets_architecture.md §3.2 rule 4): at most {@link #MAX_CANDLES} OHLC candles of
 * equal length (the last one shorter when the window does not divide evenly), candle {@code i}
 * covering the ticks in {@code (start + i·step, start + (i+1)·step]}.
 *
 * <ul>
 *   <li>The first candle opens at the round's open price and every other candle opens at the
 *       previous candle's close, so the path is continuous: a jump between the last tick of one
 *       candle and the first of the next lands in the later candle's range (open to that tick) and
 *       its wick is kept.</li>
 *   <li>{@code low}/{@code high} of a candle cover every tick in it and its open; the close is its
 *       last tick, so the last candle closes at the round's close price.</li>
 *   <li>A candle with no ticks (a hole shorter than the feed's maximum gap) is flat at the price
 *       carried over from the previous one; it can never trigger a liquidation the previous
 *       candle did not already show.</li>
 * </ul>
 *
 * <p>The candle grid is that of the whole window, so a path read while the round is still running
 * ({@code upTo < end}) is the prefix of the final one: candles already shown do not change when
 * time passes, only the last, partial candle grows. Prices are converted to engine (lot) prices
 * with {@link Market#toEngine} after the folding, which is monotonic, so open, close, low and high
 * keep their order.
 */
final class RoundPath {

    static final int MAX_CANDLES = 30;

    private RoundPath() {
    }

    /** What {@link #fold} produced: the path plus the close price in {@link Px} units. */
    record Folded(List<Candle> candles, long closePx) {
    }

    /**
     * @param upTo   the path ends here: {@code end} for a settled round, {@code now} for a running one
     * @param atMs   ticks in {@code (start, upTo]}, oldest first
     * @throws IllegalArgumentException when a price converts to an engine candle the engine rejects
     *                                  (a lot price that rounds to zero)
     */
    static Folded fold(Market market, long start, long end, long upTo, long openPx, long[] atMs, long[] px) {
        long step = Math.max(1L, ceilDiv(end - start, MAX_CANDLES));
        long covered = Math.max(0L, Math.min(upTo, end) - start);
        int count = (int) Math.max(1L, ceilDiv(covered, step));
        List<Candle> candles = new ArrayList<>(count);
        long previousClose = openPx;
        int next = 0;
        for (int i = 0; i < count; i++) {
            long candleStart = start + i * step;
            long candleEnd = Math.min(Math.min(candleStart + step, end), Math.max(upTo, candleStart));
            long open = previousClose;
            long high = open;
            long low = open;
            long close = open;
            while (next < atMs.length && atMs[next] <= candleEnd) {
                long p = px[next];
                high = Math.max(high, p);
                low = Math.min(low, p);
                close = p;
                next++;
            }
            candles.add(new Candle(candleStart, candleEnd, engine(market, open), engine(market, high),
                    engine(market, low), engine(market, close), BigDecimal.ZERO, 0));
            previousClose = close;
        }
        return new Folded(candles, previousClose);
    }

    /** A {@link Px} price as the engine's lot price of the market. */
    static BigDecimal engine(Market market, long px) {
        return market.toEngine(Px.decode(px));
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }
}
