package crowdvsmarket.server.replay;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * D-46 test-mode price source: replays {@code replay/btc_1m.csv} at a fixed {@code speedup}× real
 * time, looping back to the first candle once exhausted. A {@link RoundWindow}'s wall-clock
 * {@code [startMs, endMs)} maps to a slice of the source series by <b>array index</b>, not by
 * matching absolute source timestamps — see {@code replay/PROVENANCE.md} "How the server uses
 * this file" for why (this also means the 0-gap property of the fetched CSV is a nice-to-have,
 * never a correctness requirement here).
 *
 * <p>{@code originMs} must be the same wall-clock reference instant the caller's {@code
 * RoundClock.windowContaining(..., originMs)} call used, so that {@code originMs} itself always
 * maps to source-candle index 0 (the very first round after server start opens on the first
 * candle in the file) and every later window's start is a deterministic, reproducible function of
 * how much wall-clock time (× speedup) has elapsed since then — this is what makes replaying a
 * stored {@code RoundResolved} event exact regardless of when the replay actually runs (that event
 * stores the resolved path directly and never calls back into this class at all, see
 * {@code StoredRoundPrices} — this class is only ever consulted once, live, at the moment a round
 * is first resolved).
 *
 * <p><b>Предложение, documented caveat:</b> when a round's slice would run past the end of the
 * loaded series it wraps to the start, which can introduce one synthetic price discontinuity
 * inside that round's path (the last loaded candle's close next to the first loaded candle's
 * open) — acceptable for a local test stand, not attempted to be smoothed.
 */
public final class ReplayRoundPrices implements RoundPrices {

    private final List<Candle> candles;
    private final double speedup;
    private final long originMs;

    public ReplayRoundPrices(List<Candle> candles, double speedup, long originMs) {
        if (candles.isEmpty()) {
            throw new IllegalArgumentException("replay candle series must not be empty");
        }
        if (speedup <= 0) {
            throw new IllegalArgumentException("speedup must be positive");
        }
        this.candles = candles;
        this.speedup = speedup;
        this.originMs = originMs;
    }

    private static final long SOURCE_STEP_MS = 60_000L; // the CSV is a 1-minute series

    private int startIndex(RoundWindow window) {
        return indexAt(window.startMs());
    }

    /** The source-candle index the virtual replay clock is at, at wall-clock {@code epochMs} —
     *  also used outside any round window for {@code btcReferencePrice()} (predложение). */
    public int indexAt(long epochMs) {
        long elapsedWallMs = epochMs - originMs;
        double sourceElapsedMs = elapsedWallMs * speedup;
        long sourceMinuteOffset = Math.floorDiv((long) sourceElapsedMs, SOURCE_STEP_MS);
        return Math.floorMod(sourceMinuteOffset, candles.size());
    }

    /** The current replay reference price at wall-clock {@code epochMs} (predложение — used for
     *  {@code btcReferencePrice()}/{@code previewLiquidation()}'s default reference price, not
     *  itself a round's entry price). */
    public BigDecimal priceAt(long epochMs) {
        return candles.get(indexAt(epochMs)).open();
    }

    private int candleCount(RoundWindow window) {
        long durationWallMs = window.endMs() - window.startMs();
        double sourceDurationMs = durationWallMs * speedup;
        long count = Math.round(sourceDurationMs / SOURCE_STEP_MS);
        return (int) Math.max(1, count);
    }

    @Override
    public List<Candle> path(RoundWindow window) {
        int start = startIndex(window);
        int count = candleCount(window);
        List<Candle> path = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            path.add(candles.get(Math.floorMod(start + i, candles.size())));
        }
        return path;
    }

    @Override
    public BigDecimal openPrice(RoundWindow window) {
        return candles.get(startIndex(window)).open();
    }

    @Override
    public BigDecimal closePrice(RoundWindow window) {
        List<Candle> path = path(window);
        return path.get(path.size() - 1).close();
    }
}
