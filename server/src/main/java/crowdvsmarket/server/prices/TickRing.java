package crowdvsmarket.server.prices;

import java.util.Arrays;

/**
 * One market's ticks, oldest first, as parallel arrays: time in ms, price as {@link Px}, and the
 * {@link #LIVE kind} of each tick. Appends at the end are the normal case (a poll's answer is always the
 * newest tick); an older tick (history merged into a hole) is inserted at its place; a tick with the time
 * of an existing one replaces it ({@link #append}) or yields to it ({@link #insertIfAbsent}).
 *
 * <p><b>Kinds.</b> Only a {@link #LIVE} tick is something the source said at that moment. The others are
 * made of earlier knowledge and must never pass for a fresh price: {@link #HISTORY} is a point of the
 * source's own history, {@link #CARRIED} repeats the price of the tick on its left across a quiet hole,
 * {@link #PIN} is the boundary price of a settled round, fixed at exactly the boundary. A carried tick and
 * a pin are <i>copies</i>: the observation they stand for is the nearest {@link #isReal real} tick at or
 * before them ({@link Window#floorSourceAtMs} and the same walk over the ticks of a window) — that is the
 * time a settlement reports as {@code sourceTs}, and its kind says whether the price is a live mark or a
 * history (trade) price.
 *
 * <p>Thread-safe with one monitor per ring: a writer holds it for an append or one shifted insert,
 * a reader only for as long as it takes to copy out the slice it wants ({@link #window},
 * {@link #range}) and then works on the copy — nobody holds it while folding or converting, so
 * readers never delay the poller for long (docs/research/markets_architecture.md §3.2).
 */
final class TickRing {

    /** A poll's answer: the source's price at the moment it arrived. */
    static final byte LIVE = 0;
    /** A point of the source's history (a candle's close, a midpoint bucket). */
    static final byte HISTORY = 1;
    /** The price of the tick on its left, carried across a quiet hole (see {@code LiveMarketPrices}). */
    static final byte CARRIED = 2;
    /** A settled round's boundary price at exactly the boundary: not a tick, a fact about the past. */
    static final byte PIN = 3;

    /** A tick that was observed (live or from the source's history), not made from another one. */
    static boolean isReal(byte kind) {
        return kind == LIVE || kind == HISTORY;
    }

    /**
     * A window of ticks, copied out of the ring: the last tick at or before {@code from} (the boundary
     * price's source, with its kind and the observation it stands for) and the ticks after it.
     *
     * @param floorSourceAtMs   the time of the real tick the floor's price comes from (the floor itself
     *                          when it is real; the nearest real tick before it when it is a copy)
     * @param floorSourceKind   {@link #LIVE} or {@link #HISTORY} for that real tick; the floor's own kind
     *                          when the ring holds no real tick at or before it
     */
    record Window(boolean hasFloor, long floorAtMs, long floorPx, byte floorKind, long floorSourceAtMs,
                  byte floorSourceKind, long[] atMs, long[] px, byte[] kind) {

        static final Window EMPTY = new Window(false, 0L, 0L, LIVE, 0L, LIVE, new long[0], new long[0], new byte[0]);
    }

    private long[] at = new long[64];
    private long[] px = new long[64];
    private byte[] kind = new byte[64];
    private int from;
    private int to; // live ticks are [from, to)

    synchronized int size() {
        return to - from;
    }

    /** Adds a {@link #LIVE} tick; a tick already at {@code atMs} is replaced. */
    synchronized void append(long atMs, long price) {
        append(atMs, price, LIVE);
    }

    /** Adds a tick of the given kind; a tick already at {@code atMs} is replaced (price and kind). */
    synchronized void append(long atMs, long price, byte tickKind) {
        if (to > from && atMs <= at[to - 1]) {
            int i = lowerBound(atMs); // first index with at >= atMs
            if (i < to && at[i] == atMs) {
                px[i] = price;
                kind[i] = tickKind;
            } else {
                insertAt(i, atMs, price, tickKind);
            }
            return;
        }
        ensureRoomAtEnd();
        at[to] = atMs;
        px[to] = price;
        kind[to] = tickKind;
        to++;
    }

    /** Adds a {@link #HISTORY} tick unless one is already at {@code atMs}; true when it was added. */
    synchronized boolean insertIfAbsent(long atMs, long price) {
        return insertIfAbsent(atMs, price, HISTORY);
    }

    /** Adds a tick of the given kind unless one is already at {@code atMs}; true when it was added. */
    synchronized boolean insertIfAbsent(long atMs, long price, byte tickKind) {
        int i = lowerBound(atMs);
        if (i < to && at[i] == atMs) {
            return false;
        }
        if (i == to) {
            ensureRoomAtEnd();
            at[to] = atMs;
            px[to] = price;
            kind[to] = tickKind;
            to++;
        } else {
            insertAt(i, atMs, price, tickKind);
        }
        return true;
    }

    /** The newest tick of any kind as {@code {atMs, px}}, or {@code null} when the ring is empty. */
    synchronized long[] last() {
        return to > from ? new long[] {at[to - 1], px[to - 1]} : null;
    }

    /** The newest {@link #LIVE} tick as {@code {atMs, px}}, or {@code null} when there is none. */
    synchronized long[] lastLive() {
        for (int i = to - 1; i >= from; i--) {
            if (kind[i] == LIVE) {
                return new long[] {at[i], px[i]};
            }
        }
        return null;
    }

    /** The time of the oldest {@link #LIVE} tick, or {@code Long.MAX_VALUE} when the ring holds none. */
    synchronized long oldestLiveAt() {
        for (int i = from; i < to; i++) {
            if (kind[i] == LIVE) {
                return at[i];
            }
        }
        return Long.MAX_VALUE;
    }

    /** The last tick of any kind with {@code at <= t} as {@code {atMs, px}}, or {@code null} when there is none. */
    synchronized long[] floor(long t) {
        int i = upperBound(t) - 1;
        return i >= from ? new long[] {at[i], px[i]} : null;
    }

    /** The first tick with {@code at > t} as {@code {atMs, px}}, or {@code null} when there is none. */
    synchronized long[] after(long t) {
        int i = upperBound(t);
        return i < to ? new long[] {at[i], px[i]} : null;
    }

    /** The kind of the first tick with {@code at > t}, or -1 when there is none. */
    synchronized int kindAfter(long t) {
        int i = upperBound(t);
        return i < to ? kind[i] : -1;
    }

    /** The oldest tick as {@code {atMs, px}}, or {@code null} when the ring is empty. */
    synchronized long[] first() {
        return to > from ? new long[] {at[from], px[from]} : null;
    }

    /** The times of every tick in {@code [fromInclusive, toInclusive]}, oldest first (any kind). */
    synchronized long[] timesBetween(long fromInclusive, long toInclusive) {
        int i0 = lowerBound(fromInclusive);
        int i1 = Math.max(i0, upperBound(toInclusive));
        return Arrays.copyOfRange(at, i0, i1);
    }

    /** The last tick at or before {@code fromExclusive}, and the ticks in {@code (fromExclusive, toInclusive]}. */
    synchronized Window window(long fromExclusive, long toInclusive) {
        int i0 = upperBound(fromExclusive);
        int i1 = Math.max(i0, upperBound(toInclusive));
        boolean hasFloor = i0 > from;
        if (!hasFloor) {
            return new Window(false, 0L, 0L, LIVE, 0L, LIVE, Arrays.copyOfRange(at, i0, i1),
                    Arrays.copyOfRange(px, i0, i1), Arrays.copyOfRange(kind, i0, i1));
        }
        int source = i0 - 1;
        while (source > from && !isReal(kind[source])) {
            source--; // a copy stands for the real tick before it
        }
        boolean found = isReal(kind[source]);
        return new Window(true, at[i0 - 1], px[i0 - 1], kind[i0 - 1], found ? at[source] : at[i0 - 1],
                found ? kind[source] : kind[i0 - 1], Arrays.copyOfRange(at, i0, i1), Arrays.copyOfRange(px, i0, i1),
                Arrays.copyOfRange(kind, i0, i1));
    }

    /** The ticks with {@code fromInclusive <= at < toExclusive}; the floor fields are unused. */
    synchronized Window range(long fromInclusive, long toExclusive) {
        return range(fromInclusive, toExclusive, true);
    }

    /** The same, leaving out the pins when {@code withPins} is false (a pin is not a tick: charts never show it). */
    synchronized Window range(long fromInclusive, long toExclusive, boolean withPins) {
        int i0 = lowerBound(fromInclusive);
        int i1 = Math.max(i0, lowerBound(toExclusive));
        if (withPins) {
            return new Window(false, 0L, 0L, LIVE, 0L, LIVE, Arrays.copyOfRange(at, i0, i1),
                    Arrays.copyOfRange(px, i0, i1), Arrays.copyOfRange(kind, i0, i1));
        }
        int kept = 0;
        for (int i = i0; i < i1; i++) {
            if (kind[i] != PIN) {
                kept++;
            }
        }
        long[] outAt = new long[kept];
        long[] outPx = new long[kept];
        byte[] outKind = new byte[kept];
        int n = 0;
        for (int i = i0; i < i1; i++) {
            if (kind[i] != PIN) {
                outAt[n] = at[i];
                outPx[n] = px[i];
                outKind[n] = kind[i];
                n++;
            }
        }
        return new Window(false, 0L, 0L, LIVE, 0L, LIVE, outAt, outPx, outKind);
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

    private void insertAt(int i, long atMs, long price, byte tickKind) {
        int oldFrom = from;
        ensureRoomAtEnd(); // may slide the live part down to index 0, which moves every index
        i -= oldFrom - from;
        System.arraycopy(at, i, at, i + 1, to - i);
        System.arraycopy(px, i, px, i + 1, to - i);
        System.arraycopy(kind, i, kind, i + 1, to - i);
        at[i] = atMs;
        px[i] = price;
        kind[i] = tickKind;
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
            System.arraycopy(kind, from, kind, 0, n);
            from = 0;
            to = n;
            return;
        }
        int capacity = at.length * 2;
        at = Arrays.copyOf(at, capacity);
        px = Arrays.copyOf(px, capacity);
        kind = Arrays.copyOf(kind, capacity);
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
