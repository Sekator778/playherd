package crowdvsmarket.server.domain;

import crowdvsmarket.engine.ledger.LedgerEntry;
import crowdvsmarket.engine.ledger.LedgerListener;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

/**
 * The movements of the ledger that are not in the {@code ledger_entries} table yet (architecture plan F3): the ledger
 * book keeps no history of its own, it hands every entry to this listener, and {@code GameService} writes what is
 * collected here after the engine lock ({@code flushLedgerRows}). The size is O(the movements since the last write):
 * a few entries in a healthy server, everything a long failure of the database held back otherwise.
 *
 * <p>A hub that is built aside for a rebuild is told which sequence numbers the table already has
 * ({@link #skipStored}), so replaying the whole journal collects only the movements the table lacks, and
 * {@link #spillTo} lets it hand them over in batches while it replays, so a first start with an empty table does not hold
 * the whole history at once.
 *
 * <p>An entry is in here from the moment its event was applied, which may be before the event's transaction commits (the
 * settlement applies inside it). The reader that drains ({@code GameService}) does so under the engine read lock, which is
 * only free between two operations: an operation that failed has by then been rolled back and its hub replaced, with this
 * outbox, by the rebuild.
 */
public final class LedgerOutbox implements LedgerListener {

    /** Writes a batch of movements to the table; throws when it cannot. */
    @FunctionalInterface
    public interface Sink {
        void write(List<LedgerEntry> batch);
    }

    private final ArrayDeque<LedgerEntry> pending = new ArrayDeque<>();
    private LongPredicate stored = seq -> false;
    private Sink spill;
    private int spillThreshold;
    private boolean spillFailed;

    /** Drops, from now on, the entries {@code stored} says the table has. */
    public synchronized void skipStored(LongPredicate stored) {
        this.stored = stored;
    }

    /** While replaying: hands the collected movements to {@code sink} whenever {@code threshold} are waiting. A write that
     *  fails keeps them here (and stops spilling); {@link #endReplay} ends it. */
    public synchronized void spillTo(Sink sink, int threshold) {
        this.spill = sink;
        this.spillThreshold = threshold;
        this.spillFailed = false;
    }

    /** The replay is over: nothing is skipped or handed over any more, every later entry is new. */
    public synchronized void endReplay() {
        this.spill = null;
        this.stored = seq -> false;
    }

    @Override
    public synchronized void onEntry(LedgerEntry entry) {
        if (stored.test(entry.sequence())) {
            return;
        }
        pending.addLast(entry);
        if (spill != null && !spillFailed && pending.size() >= spillThreshold) {
            List<LedgerEntry> batch = drain();
            try {
                spill.write(batch);
            } catch (RuntimeException e) {
                requeueFront(batch);
                spillFailed = true;
            }
        }
    }

    /** Takes everything collected, oldest first. */
    public synchronized List<LedgerEntry> drain() {
        List<LedgerEntry> out = new ArrayList<>(pending);
        pending.clear();
        return out;
    }

    /** Puts back, before whatever was collected since, a batch whose write failed. */
    public synchronized void requeueFront(List<LedgerEntry> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            pending.addFirst(batch.get(i));
        }
    }

    /** How many movements wait to be written. */
    public synchronized int size() {
        return pending.size();
    }
}
