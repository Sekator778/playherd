package crowdvsmarket.server.core;

import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.jfr.HerdWriterSync;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jboss.logging.Logger;

/**
 * The journal stage of {@link CoreWriter}: the one thread that appends to the journal and waits for the disk. It takes the batches the logic
 * thread has applied, in the order of the hand-over, and writes whatever is waiting as <b>one</b> excerpt with <b>one</b> sync (group commit:
 * while a sync is under way the next batches queue up, so the group grows by itself with the load and is a single batch when the load is
 * light). The events keep the order in which the logic thread applied them. Durable batches go on to the {@link OutputStage}.
 *
 * <p><b>A write that fails</b> makes the whole group fail, and every batch after it until the logic thread has rebuilt the engine: they are
 * handed on {@linkplain WriterBatch#poisoned poisoned} without being written (a later batch was applied on top of the state the failed one
 * left, so it must not reach the journal), and the logic thread is woken to answer them 503 and recover. {@link #failure()} is the flag: set
 * here, cleared by the logic thread ({@link #resume}) after every batch it handed over has come back.
 */
final class JournalStage implements Runnable {

    private static final Logger LOG = Logger.getLogger(JournalStage.class);

    /** The name of the thread: the only one that syncs the journal. */
    static final String THREAD = "herd-core-journal";

    private final EventStore eventStore;
    private final BlockingQueue<WriterBatch> in = new LinkedBlockingQueue<>();
    private final BlockingQueue<WriterSlot<?>> wakeLogic;
    private final OutputStage output;
    private volatile Throwable failure;
    private volatile boolean stopping;

    // what the admin status and the benches read
    final AtomicLong syncs = new AtomicLong();
    final AtomicLong syncedBatches = new AtomicLong();
    final AtomicLong syncNanos = new AtomicLong();
    final AtomicLong maxGroup = new AtomicLong();

    JournalStage(EventStore eventStore, OutputStage output, BlockingQueue<WriterSlot<?>> wakeLogic) {
        this.eventStore = eventStore;
        this.output = output;
        this.wakeLogic = wakeLogic;
    }

    void hand(WriterBatch batch) {
        in.add(batch);
    }

    /** The failure of the write that stopped the stage, {@code null} while it is working. */
    Throwable failure() {
        return failure;
    }

    /** Called by the logic thread once every batch it handed over has come back: the stage writes again. */
    void resume() {
        failure = null;
    }

    int waiting() {
        return in.size();
    }

    void stop() {
        stopping = true;
    }

    @Override
    public void run() {
        List<WriterBatch> group = new ArrayList<>();
        while (true) {
            group.clear();
            try {
                WriterBatch first = in.poll(250, TimeUnit.MILLISECONDS);
                if (first == null) {
                    if (stopping) {
                        return;
                    }
                    continue;
                }
                group.add(first);
                in.drainTo(group);
            } catch (InterruptedException e) {
                if (stopping) {
                    return;
                }
                continue;
            }
            try {
                write(group);
            } catch (Throwable e) {
                // not reached: write() answers for its own failures
                LOG.error("the journal stage of the core writer failed outside a write", e);
                fail(group, e);
            }
        }
    }

    private void write(List<WriterBatch> group) {
        if (failure != null) {
            for (WriterBatch b : group) {
                b.poisoned = true;
                b.failure = failure;
            }
            pass(group);
            return;
        }
        List<EventStore.Pending> events = new ArrayList<>();
        for (WriterBatch b : group) {
            events.addAll(b.events);
        }
        HerdWriterSync jfr = new HerdWriterSync();
        boolean rec = jfr.isEnabled();
        if (rec) {
            jfr.begin();
            jfr.batches = group.size();
            jfr.events = events.size();
            jfr.waitingAfter = in.size();
        }
        long t0 = System.nanoTime();
        long last = -1;
        Throwable error = null;
        if (!events.isEmpty()) {
            try {
                last = eventStore.appendAll(events); // the durability point: the excerpt is on disk when this returns
            } catch (Throwable e) {
                error = e;
            }
        }
        long took = System.nanoTime() - t0;
        if (error != null) {
            if (rec) {
                jfr.outcome = "FAILED";
                jfr.commit();
            }
            fail(group, error);
            return;
        }
        syncs.incrementAndGet();
        syncedBatches.addAndGet(group.size());
        syncNanos.addAndGet(took);
        maxGroup.accumulateAndGet(group.size(), Math::max);
        // each batch's version is the last seq of its own events, not of the group: the excerpt numbers them one after the other
        long after = 0;
        for (int i = group.size() - 1; i >= 0; i--) {
            WriterBatch b = group.get(i);
            b.version = b.events.isEmpty() || last < 0 ? -1 : last - after;
            after += b.events.size();
            b.syncNanos = took;
            b.syncGroup = group.size();
            b.journaledAtNanos = t0;
        }
        if (rec) {
            jfr.outcome = "SYNCED";
            jfr.commit();
        }
        pass(group);
    }

    /** The write failed: the group and everything behind it is poisoned, and the logic thread is woken to deal with it. */
    private void fail(List<WriterBatch> group, Throwable error) {
        LOG.errorf(error, "the journal could not be written for %d batch(es) of the core writer - none of them is durable; the batches "
                + "behind them are dropped and the engine is rebuilt from the journal", group.size());
        failure = error; // before the batches go on: the logic thread must find it when the first of them comes back
        for (WriterBatch b : group) {
            b.poisoned = true;
            b.failure = error;
        }
        pass(group);
        wakeLogic.offer(WriterSlot.WAKE);
    }

    private void pass(List<WriterBatch> group) {
        for (WriterBatch b : group) {
            output.hand(b);
        }
    }
}
