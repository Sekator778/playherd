package crowdvsmarket.server.prices;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * Every market's recent ticks (docs/research/markets_architecture.md §3.2, rules 1 and 2): a
 * {@link TickRing} per market kept for {@link PricesConfig#retentionMs() 2 × the DAILY round + 10
 * minutes}, fed by {@link #append} — one call per poll of a price source, which also writes the
 * poll as ONE {@code price_snapshots} row — rebuilt from those rows by {@link #restore} at start-up
 * and trimmed (memory and table) every 10 minutes by {@link #prune}.
 *
 * <p>A tick's time is when the source's answer arrived, not a time the source claims. A price that
 * cannot be one — not positive, or beyond {@link Px#MAX_RAW} — is dropped with a warning and never
 * stored. The snapshot row is written after the memory is updated and its failure only logged: a
 * database hiccup costs the restart recovery of that poll, never the live price.
 *
 * <p><b>Only a poll makes a quote.</b> The ring also holds ticks that are no poll's answer — history
 * merged into a hole or before the first poll ({@link TickRing#HISTORY}), the price of a quiet asset
 * carried across a hole ({@link TickRing#CARRIED}), and the boundary price a settled round pinned
 * ({@link TickRing#PIN}) — and they are all kept out of what {@link #last} answers, so none of them can make a
 * stale price look fresh to a bot or a bet slip; a pin is also kept out of {@link #series}, and every kind
 * that is a copy knows the real observation behind it ({@link TickRing.Window}), which is what a settlement
 * reports as the time its price came from.
 *
 * <p><b>Nothing is merged behind a boundary a round settled on.</b> {@link #pinBoundary} remembers, per
 * market, the latest boundary pinned in this process, and every merge ({@link #mergeHistory},
 * {@link #mergeCarried}, {@link #seedHistory}, {@link #fillHoles}) drops the points at or before it: whatever
 * happens to the ticks afterwards, a price a settled round used stays the price there (D-40). A restart
 * forgets the pins; the next opening price then comes from the persisted close of the previous round (core).
 *
 * <p>Besides the ticks the store remembers, per market, which feed delivered them (the staleness
 * and gap limits are the feed's) and the 24-hour change the source reported.
 */
@ApplicationScoped
public class TickStore {

    private static final Logger LOG = Logger.getLogger(TickStore.class);

    private final SnapshotStore snapshots;
    private final long retentionMs;
    private final LongSupplier clock;
    private final ThrottledLog snapshotFailures;
    private final ConcurrentHashMap<String, TickRing> rings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> feedOf = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> change24h = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> latestPinMs = new ConcurrentHashMap<>();
    /** The 1-minute candles of each key's live ticks (the candles read-model): fed by {@link #append} and {@link #restore} only. */
    private final ConcurrentHashMap<String, MinuteBars> minuteBars = new ConcurrentHashMap<>();

    @Inject
    public TickStore(SnapshotStore snapshots, PricesConfig config) {
        this(snapshots, config.retentionMs(), System::currentTimeMillis);
    }

    TickStore(SnapshotStore snapshots, long retentionMs, LongSupplier clock) {
        this.snapshots = snapshots;
        this.retentionMs = retentionMs;
        this.clock = clock;
        this.snapshotFailures = new ThrottledLog(LOG, clock, 60_000L);
    }

    /**
     * Stores one poll of {@code source} (a feed id): each price becomes a live tick of its market at
     * {@code atMs}, and one row {@code (source, atMs, prices)} goes to {@code price_snapshots}.
     */
    public void append(String source, long atMs, Map<String, BigDecimal> prices) {
        Map<String, BigDecimal> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : prices.entrySet()) {
            if (Px.isStorable(e.getValue())) {
                accepted.put(e.getKey(), e.getValue());
            } else {
                LOG.warnf("dropped a non-positive or absurd price %s for market %s from %s", e.getValue(), e.getKey(),
                        source);
            }
        }
        if (accepted.isEmpty()) {
            return;
        }
        for (Map.Entry<String, BigDecimal> e : accepted.entrySet()) {
            long px = Px.encode(e.getValue());
            ring(e.getKey()).append(atMs, px);
            minuteBars.computeIfAbsent(e.getKey(), id -> new MinuteBars()).add(atMs, px);
            feedOf.put(e.getKey(), source);
        }
        try {
            snapshots.save(source, atMs, accepted);
            snapshotFailures.success("save", "writing price snapshots");
        } catch (RuntimeException e) {
            snapshotFailures.failure("save", "could not write a price snapshot (the tick is in memory only)", e);
        }
    }

    /** Rebuilds the rings from the table (live ticks): every row of the last {@code retention}; returns the row count. */
    public int restore() {
        long cutoff = clock.getAsLong() - retentionMs;
        int rows = snapshots.replaySince(cutoff, snapshot -> {
            for (Map.Entry<String, BigDecimal> e : snapshot.prices().entrySet()) {
                if (Px.isStorable(e.getValue())) {
                    long px = Px.encode(e.getValue());
                    ring(e.getKey()).append(snapshot.atMs(), px);
                    minuteBars.computeIfAbsent(e.getKey(), id -> new MinuteBars()).add(snapshot.atMs(), px);
                    feedOf.put(e.getKey(), snapshot.source());
                }
            }
        });
        LOG.infof("price buffer restored from %d snapshot row(s), %d market(s)", rows, rings.size());
        return rows;
    }

    /** Drops the ticks and the rows older than the retention; the periodic job of {@link PriceFeedService}. */
    public void prune() {
        long cutoff = clock.getAsLong() - retentionMs;
        int ticks = 0;
        for (TickRing ring : rings.values()) {
            ticks += ring.trimBefore(cutoff);
        }
        int rows = 0;
        try {
            rows = snapshots.pruneBefore(cutoff);
            snapshotFailures.success("prune", "pruning price snapshots");
        } catch (RuntimeException e) {
            snapshotFailures.failure("prune", "could not prune price snapshots", e);
        }
        LOG.debugf("price buffer pruned: %d tick(s), %d snapshot row(s)", ticks, rows);
    }

    /** The newest <i>live</i> tick of the market: what a source last said, never a history point, a carry or a pin. */
    public Optional<PricePoint> last(String marketId) {
        TickRing ring = rings.get(marketId);
        long[] last = ring == null ? null : ring.lastLive();
        return last == null ? Optional.empty() : Optional.of(point(last[0], last[1]));
    }

    /**
     * The finished 1-minute candles of exactly this key's live ticks among the last {@code minutes} minutes before the one
     * {@code nowMs} is in, oldest first (see {@link MinuteBars}); memory only, O(minutes).
     */
    public List<PriceBar> bars(String marketId, int minutes, long nowMs) {
        MinuteBars book = minuteBars.get(marketId);
        if (book == null) {
            return List.of();
        }
        List<MinuteBars.Bar> bars = book.finished(nowMs, minutes);
        List<PriceBar> out = new ArrayList<>(bars.size());
        for (MinuteBars.Bar b : bars) {
            out.add(new PriceBar(b.openTimeMs(), Px.decode(b.open()), Px.decode(b.high()), Px.decode(b.low()),
                    Px.decode(b.close())));
        }
        return out;
    }

    /** Ticks with {@code fromMs <= atMs < toMs}, oldest first — live, history and carried ones, not the pins. */
    public List<PricePoint> series(String marketId, long fromMs, long toMs) {
        TickRing ring = rings.get(marketId);
        if (ring == null) {
            return List.of();
        }
        TickRing.Window w = ring.range(fromMs, toMs, false);
        List<PricePoint> out = new ArrayList<>(w.atMs().length);
        for (int i = 0; i < w.atMs().length; i++) {
            out.add(point(w.atMs()[i], w.px()[i]));
        }
        return out;
    }

    /** How many ticks (of any kind) the market holds; for tests and diagnostics. */
    public int size(String marketId) {
        TickRing ring = rings.get(marketId);
        return ring == null ? 0 : ring.size();
    }

    /** The last tick (of any kind) at or before {@code atMs}, however old: the raw material of a boundary price. */
    public Optional<PricePoint> floor(String marketId, long atMs) {
        TickRing ring = rings.get(marketId);
        long[] tick = ring == null ? null : ring.floor(atMs);
        return tick == null ? Optional.empty() : Optional.of(point(tick[0], tick[1]));
    }

    /** True when a tick (of any kind) at or before {@code atMs} is at most {@code staleMs} older than it. */
    boolean hasTickFreshAt(String marketId, long atMs, long staleMs) {
        TickRing ring = rings.get(marketId);
        long[] tick = ring == null ? null : ring.floor(atMs);
        return tick != null && atMs - tick[0] <= staleMs;
    }

    /** The time of the market's oldest live tick, empty when it has none (no poll has answered for it yet). */
    OptionalLong oldestLiveAt(String marketId) {
        TickRing ring = rings.get(marketId);
        long at = ring == null ? Long.MAX_VALUE : ring.oldestLiveAt();
        return at == Long.MAX_VALUE ? OptionalLong.empty() : OptionalLong.of(at);
    }

    /** The latest boundary a settled round pinned for the market in this process, empty when none did. */
    OptionalLong latestPin(String marketId) {
        Long pin = latestPinMs.get(marketId);
        return pin == null ? OptionalLong.empty() : OptionalLong.of(pin);
    }

    /** The feed that delivered this market's latest tick. */
    public Optional<String> feedOf(String marketId) {
        return Optional.ofNullable(feedOf.get(marketId));
    }

    /** The source's relative 24-hour change (0.0098 = +0.98%); {@code null} clears it. */
    public void setChange24h(String marketId, Double change) {
        if (change == null || change.isNaN() || change.isInfinite()) {
            change24h.remove(marketId);
        } else {
            change24h.put(marketId, change);
        }
    }

    public Double change24h(String marketId) {
        return change24h.get(marketId);
    }

    /**
     * Adds history to the market's ring without overwriting a tick that is already there, without a
     * snapshot row — a restart that needs it again asks the source again — and never at or before the
     * latest boundary a round pinned. Returns how many points were added.
     */
    int mergeHistory(String marketId, List<PricePoint> points) {
        return mergeHistory(marketId, points, false);
    }

    /**
     * The same, and with {@code preHistoryOnly} only points older than every live tick of the market: the
     * rule for a source whose history is another kind of number than its ticks (Hyperliquid's candles are
     * trade prices, its ticks are mark prices) — its history may be the past of the first poll, and the points
     * of a bridge over a gap between two live ticks ({@code GapBridge} has checked those, and
     * {@code preHistoryOnly} is false for them), never a stretch between two polls otherwise (see
     * {@code HyperliquidPriceSource}).
     */
    int mergeHistory(String marketId, List<PricePoint> points, boolean preHistoryOnly) {
        TickRing ring = ring(marketId);
        long floorMs = latestPinMs.getOrDefault(marketId, Long.MIN_VALUE);
        long before = preHistoryOnly ? ring.oldestLiveAt() : Long.MAX_VALUE;
        int added = 0;
        for (PricePoint p : points) {
            if (p.atMs() > floorMs && p.atMs() < before && Px.isStorable(p.raw())
                    && ring.insertIfAbsent(p.atMs(), Px.encode(p.raw()), TickRing.HISTORY)) {
                added++;
            }
        }
        return added;
    }

    /**
     * Adds the carried points of a quiet hole ({@link TickRing#CARRIED}); like a merge it never overwrites a
     * tick, writes no row and stays behind the latest pinned boundary. Returns how many were added.
     */
    int mergeCarried(String marketId, List<PricePoint> points) {
        TickRing ring = ring(marketId);
        long floorMs = latestPinMs.getOrDefault(marketId, Long.MIN_VALUE);
        int added = 0;
        for (PricePoint p : points) {
            if (p.atMs() > floorMs && Px.isStorable(p.raw())
                    && ring.insertIfAbsent(p.atMs(), Px.encode(p.raw()), TickRing.CARRIED)) {
                added++;
            }
        }
        return added;
    }

    /**
     * Seeds a market's ring with history older than every live tick it holds (the live ticks are
     * authoritative: nothing is merged at or after the oldest of them) and later than the latest pinned
     * boundary, and remembers which feed prices the market until a live tick says so itself. Like
     * {@link #mergeHistory} it never overwrites a tick and writes no snapshot row. Returns how many points
     * were added.
     */
    int seedHistory(String marketId, String feedId, List<PricePoint> points) {
        int added = mergeHistory(marketId, points, true);
        if (added > 0) {
            feedOf.putIfAbsent(marketId, feedId);
        }
        return added;
    }

    /**
     * History into the holes of the ring — for a source whose history is the same kind of number as its
     * ticks: a point is added when it comes before the first tick, or when the two ticks around it (as they
     * were when the call began) are more than {@code maxGapMs} apart. Live ticks stay as they are and the
     * points between close ticks are left out, so a seed never interleaves with a feed that is working;
     * nothing is added after the newest tick (a poll is about to be there). Never overwrites, writes no row,
     * stays behind the latest pin. Returns how many points were added.
     */
    int fillHoles(String marketId, List<PricePoint> points, long maxGapMs) {
        TickRing ring = ring(marketId);
        long floorMs = latestPinMs.getOrDefault(marketId, Long.MIN_VALUE);
        List<PricePoint> accepted = new ArrayList<>();
        for (PricePoint p : points) {
            if (p.atMs() <= floorMs || !Px.isStorable(p.raw())) {
                continue;
            }
            long[] left = ring.floor(p.atMs());
            long[] right = ring.after(p.atMs());
            boolean inHole;
            if (left != null && left[0] == p.atMs()) {
                inHole = false; // a tick is there: history never overwrites it
            } else if (left == null) {
                inHole = true; // before the first tick
            } else {
                inHole = right != null && right[0] - left[0] > maxGapMs;
            }
            if (inHole) {
                accepted.add(p);
            }
        }
        int added = 0;
        for (PricePoint p : accepted) {
            if (ring.insertIfAbsent(p.atMs(), Px.encode(p.raw()), TickRing.HISTORY)) {
                added++;
            }
        }
        return added;
    }

    /**
     * Makes {@code px} (a {@link Px} price) a {@link TickRing#PIN} at exactly {@code atMs} unless a tick is
     * already there: the boundary price of a settled round, fixed (see {@link LiveMarketPrices#resolve}).
     * Like a merge it writes no snapshot row and never replaces a tick; it also moves the latest pinned
     * boundary of the market forward, behind which nothing is merged any more.
     */
    void pinBoundary(String marketId, long atMs, long px) {
        ring(marketId).insertIfAbsent(atMs, px, TickRing.PIN);
        latestPinMs.merge(marketId, atMs, Math::max);
    }

    /** The ticks of a round window, see {@link TickRing#window}; empty when the market has no ring. */
    TickRing.Window window(String marketId, long fromExclusive, long toInclusive) {
        TickRing ring = rings.get(marketId);
        return ring == null ? TickRing.Window.EMPTY : ring.window(fromExclusive, toInclusive);
    }

    /** The first tick after {@code atMs} as {@code {atMs, px}} ({@link Px} units), or {@code null}. */
    long[] tickAfter(String marketId, long atMs) {
        TickRing ring = rings.get(marketId);
        return ring == null ? null : ring.after(atMs);
    }

    /** Whether the first tick after {@code atMs} is a live one (false when there is none). */
    boolean liveTickAfter(String marketId, long atMs) {
        TickRing ring = rings.get(marketId);
        return ring != null && ring.kindAfter(atMs) == TickRing.LIVE;
    }

    private TickRing ring(String marketId) {
        return rings.computeIfAbsent(marketId, id -> new TickRing());
    }

    private static PricePoint point(long atMs, long px) {
        return new PricePoint(atMs, Px.decode(px));
    }
}
