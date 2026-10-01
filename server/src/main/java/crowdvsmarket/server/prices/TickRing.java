package crowdvsmarket.server.prices;

import java.util.Arrays;

/**
 * One market's ticks, oldest first, as two parallel {@code long} arrays (time in ms, price as
 * {@link Px}). Appends at the end are the normal case (a poll's answer is always the newest tick);
 * an older tick (history merged into a hole) is inserted at its place; a tick with the time of an
 * existing one replaces it ({@link #append}) or yields to it ({@link #insertIfAbsent}).
 *
 * <p>Thread-safe with one monitor per ring: a writer holds it for an append or one shifted insert,
 * a reader only for as long as it takes to copy out the slice it wants ({@link #window},
 * {@link #range}) and then works on the copy — nobody holds it while folding or converting, so
 * readers never delay the poller for long (docs/research/markets_architecture.md §3.2).
 */
final class TickRing {

    /** A window of ticks, copied out of the ring: the last tick at or before {@code from} (the
     *  boundary price's source) and the ticks after it. */
    record Window(boolean hasFloor, long floorAtMs, long floorPx, long[] atMs, long[] px) {

        static final Window EMPTY = new Window(false, 0L, 0L, new long[0], new long[0]);
    }

    private long[] at = new long[64];
    private long[] px = new long[64];
    private int from;
    private int to; // live ticks are [from, to)

    synchronized int size() {
        return to - from;
    }

    /** Adds a tick; a tick already at {@code atMs} is replaced. */
    synchronized void append(long atMs, long price) {
        if (to > from && atMs <= at[to - 1]) {
            int i = lowerBound(atMs); // first index with at >= atMs
            if (i < to && at[i] == atMs) {
                px[i] = price;
            } else {
                insertAt(i, atMs, price);
            }
            return;
        }
        ensureRoomAtEnd();
        at[to] = atMs;
        px[to] = price;
        to++;
    }

    /** Adds a tick unless one is already at {@code atMs}; true when it was added. */
    synchronized boolean insertIfAbsent(long atMs, long price) {
        int i = lowerBound(atMs);
        if (i < to && at[i] == atMs) {
            return false;
        }
        if (i == to) {
            ensureRoomAtEnd();
            at[to] = atMs;
            px[to] = price;
            to++;
        } else {
            insertAt(i, atMs, price);
        }
        return true;
    }

    /** The newest tick as {@code {atMs, px}}, or {@code null} when the ring is empty. */
    synchronized long[] last() {
        return to > from ? new long[] {at[to - 1], px[to - 1]} : null;
    }

    /** The last tick with {@code at <= t} as {@code {atMs, px}}, or {@code null} when there is none. */
    synchronized long[] floor(long t) {
        int i = upperBound(t) - 1;
        return i >= from ? new long[] {at[i], px[i]} : null;
    }

    /** The last tick at or before {@code fromExclusive}, and the ticks in {@code (fromExclusive, toInclusive]}. */
    synchronized Window window(long fromExclusive, long toInclusive) {
        int i0 = upperBound(fromExclusive);
        int i1 = Math.max(i0, upperBound(toInclusive));
        boolean hasFloor = i0 > from;
        return new Window(hasFloor, hasFloor ? at[i0 - 1] : 0L, hasFloor ? px[i0 - 1] : 0L,
                Arrays.copyOfRange(at, i0, i1), Arrays.copyOfRange(px, i0, i1));
    }

    /** The ticks with {@code fromInclusive <= at < toExclusive}; the floor fields are unused. */
    synchronized Window range(long fromInclusive, long toExclusive) {
        int i0 = lowerBound(fromInclusive);
        int i1 = Math.max(i0, lowerBound(toExclusive));
        return new Window(false, 0L, 0L, Arrays.copyOfRange(at, i0, i1), Arrays.copyOfRange(px, i0, i1));
    }

    /** Drops the ticks older than {@code cutoffMs}; returns how many. */
    synchronized int trimBefore(long cutoffMs) {
        int i = lowerBound(cutoffMs);
        int dropped = i - from;
        from = i;
        if (from == to) {
            from = 0;
            to = 0;
        }
        return dropped;
    }

    private void insertAt(int i, long atMs, long price) {
        int oldFrom = from;
        ensureRoomAtEnd(); // may slide the live part down to index 0, which moves every index
        i -= oldFrom - from;
        System.arraycopy(at, i, at, i + 1, to - i);
        System.arraycopy(px, i, px, i + 1, to - i);
        at[i] = atMs;
        px[i] = price;
        to++;
    }

    private void ensureRoomAtEnd() {
        if (to < at.length) {
            return;
        }
        if (from >= at.length / 4) { // enough dead room at the front: slide down instead of growing
            int n = to - from;
            System.arraycopy(at, from, at, 0, n);
            System.arraycopy(px, from, px, 0, n);
            from = 0;
            to = n;
            return;
        }
        int capacity = at.length * 2;
        at = Arrays.copyOf(at, capacity);
        px = Arrays.copyOf(px, capacity);
    }

    /** First index in {@code [from, to]} whose time is {@code >= t}. */
    private int lowerBound(long t) {
        int lo = from;
        int hi = to;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (at[mid] < t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** First index in {@code [from, to]} whose time is {@code > t}. */
    private int upperBound(long t) {
        int lo = from;
        int hi = to;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (at[mid] <= t) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
