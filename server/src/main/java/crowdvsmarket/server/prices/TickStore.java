package crowdvsmarket.server.prices;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
     * Stores one poll of {@code source} (a feed id): each price becomes a tick of its market at
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
            ring(e.getKey()).append(atMs, Px.encode(e.getValue()));
            feedOf.put(e.getKey(), source);
        }
        try {
            snapshots.save(source, atMs, accepted);
            snapshotFailures.success("save", "writing price snapshots");
        } catch (RuntimeException e) {
            snapshotFailures.failure("save", "could not write a price snapshot (the tick is in memory only)", e);
        }
    }

    /** Rebuilds the rings from the table: every row of the last {@code retention}; returns the row count. */
    public int restore() {
        long cutoff = clock.getAsLong() - retentionMs;
        int rows = snapshots.replaySince(cutoff, snapshot -> {
            for (Map.Entry<String, BigDecimal> e : snapshot.prices().entrySet()) {
                if (Px.isStorable(e.getValue())) {
                    ring(e.getKey()).append(snapshot.atMs(), Px.encode(e.getValue()));
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

    /** The newest tick of the market. */
    public Optional<PricePoint> last(String marketId) {
        TickRing ring = rings.get(marketId);
        long[] last = ring == null ? null : ring.last();
        return last == null ? Optional.empty() : Optional.of(point(last[0], last[1]));
    }

    /** Ticks with {@code fromMs <= atMs < toMs}, oldest first. */
    public List<PricePoint> series(String marketId, long fromMs, long toMs) {
        TickRing ring = rings.get(marketId);
        if (ring == null) {
            return List.of();
        }
        TickRing.Window w = ring.range(fromMs, toMs);
        List<PricePoint> out = new ArrayList<>(w.atMs().length);
        for (int i = 0; i < w.atMs().length; i++) {
            out.add(point(w.atMs()[i], w.px()[i]));
        }
        return out;
    }

    /** How many ticks the market holds; for tests and diagnostics. */
    public int size(String marketId) {
        TickRing ring = rings.get(marketId);
        return ring == null ? 0 : ring.size();
    }

    /** The last tick at or before {@code atMs}, however old: the raw material of a boundary price. */
    public Optional<PricePoint> floor(String marketId, long atMs) {
        TickRing ring = rings.get(marketId);
        long[] tick = ring == null ? null : ring.floor(atMs);
        return tick == null ? Optional.empty() : Optional.of(point(tick[0], tick[1]));
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
     * Adds history to the market's ring without overwriting a tick that is already there, and
     * without a snapshot row — a restart that needs it again asks the source again. Returns how many
     * were added.
     */
    int mergeHistory(String marketId, List<PricePoint> points) {
        TickRing ring = ring(marketId);
        int added = 0;
        for (PricePoint p : points) {
            if (Px.isStorable(p.raw()) && ring.insertIfAbsent(p.atMs(), Px.encode(p.raw()))) {
                added++;
            }
        }
        return added;
    }

    /** The ticks of a round window, see {@link TickRing#window}; empty when the market has no ring. */
    TickRing.Window window(String marketId, long fromExclusive, long toInclusive) {
        TickRing ring = rings.get(marketId);
        return ring == null ? TickRing.Window.EMPTY : ring.window(fromExclusive, toInclusive);
    }

    private TickRing ring(String marketId) {
        return rings.computeIfAbsent(marketId, id -> new TickRing());
    }

    private static PricePoint point(long atMs, long px) {
        return new PricePoint(atMs, Px.decode(px));
    }
}
