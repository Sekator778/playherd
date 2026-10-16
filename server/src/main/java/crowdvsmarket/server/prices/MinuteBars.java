package crowdvsmarket.server.prices;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The candles read-model of one market (docs/research/bots_module_design.md section 3, architecture_review_2026-10-16.md section 5):
 * 1-minute open/high/low/close bars folded from the live ticks the price feed already delivers, in a ring of {@link #CAPACITY}
 * minutes - a fixed amount of memory (seven {@code long} arrays), whatever the tick rate.
 *
 * <p><b>Cost.</b> {@link #add} is O(1): the minute of the tick picks its slot, the slot is started or merged in place. No
 * scan, no allocation. {@link #finished} copies out at most {@link #CAPACITY} finished bars under the monitor, which is held
 * for that copy only; everything else (turning into strings, sorting out) is done by the caller on its own copy. Nothing here
 * takes the engine's lock and nothing needs it.
 *
 * <p><b>Rules.</b> A bar is the ticks with {@code atMs} inside its minute: the open is the earliest tick, the close the
 * latest (a tick that arrives later with an earlier time does not move the close); a minute without ticks has no bar (a
 * hole, not a flat bar - the consumers know how to treat a hole). A tick older than the ring's window is dropped.
 * A minute is <i>finished</i> once the clock is past it; {@link #finished} never returns the minute in progress.
 * Prices are the ring's scaled longs ({@link Px}).
 */
final class MinuteBars {

    /** How many minutes the ring remembers: three hours, the 120 minutes the bots' longest rule needs plus a margin. */
    static final int CAPACITY = 180;
    static final long MINUTE_MS = 60_000L;

    /** One finished bar, prices as {@link Px} longs. */
    record Bar(long openTimeMs, long open, long high, long low, long close) {
    }

    private final long[] minute = new long[CAPACITY]; // absolute minute number (atMs / 60000), -1 = empty slot
    private final long[] open = new long[CAPACITY];
    private final long[] high = new long[CAPACITY];
    private final long[] low = new long[CAPACITY];
    private final long[] close = new long[CAPACITY];
    private final long[] openAt = new long[CAPACITY];
    private final long[] closeAt = new long[CAPACITY];
    private long newest = Long.MIN_VALUE; // the newest minute ever added

    MinuteBars() {
        Arrays.fill(minute, -1L);
    }

    /** Folds one tick in; O(1). */
    synchronized void add(long atMs, long px) {
        long m = Math.floorDiv(atMs, MINUTE_MS);
        if (m < 0) {
            return;
        }
        if (newest != Long.MIN_VALUE && m <= newest - CAPACITY) {
            return; // older than the window: its slot belongs to a newer minute
        }
        int i = (int) (m % CAPACITY);
        if (minute[i] > m) {
            return; // the slot already holds a newer minute (cannot happen inside the window; defensive)
        }
        if (minute[i] < m) { // a new bar replaces whatever minute this slot held
            minute[i] = m;
            open[i] = px;
            high[i] = px;
            low[i] = px;
            close[i] = px;
            openAt[i] = atMs;
            closeAt[i] = atMs;
        } else {
            if (px > high[i]) {
                high[i] = px;
            }
            if (px < low[i]) {
                low[i] = px;
            }
            if (atMs < openAt[i]) {
                open[i] = px;
                openAt[i] = atMs;
            }
            if (atMs >= closeAt[i]) {
                close[i] = px;
                closeAt[i] = atMs;
            }
        }
        if (m > newest) {
            newest = m;
        }
    }

    /**
     * The finished bars among the last {@code minutes} minutes before the minute {@code nowMs} is in, oldest first: at most
     * {@code minutes} (and {@code CAPACITY - 1}), fewer when some minutes had no ticks or the history is short.
     */
    synchronized List<Bar> finished(long nowMs, int minutes) {
        int span = Math.min(Math.max(minutes, 0), CAPACITY - 1);
        long current = Math.floorDiv(nowMs, MINUTE_MS);
        List<Bar> out = new ArrayList<>(Math.min(span, 64));
        for (long m = current - span; m < current; m++) {
            if (m < 0) {
                continue;
            }
            int i = (int) (m % CAPACITY);
            if (minute[i] == m) {
                out.add(new Bar(m * MINUTE_MS, open[i], high[i], low[i], close[i]));
            }
        }
        return out;
    }
}
