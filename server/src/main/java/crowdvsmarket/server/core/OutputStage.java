package crowdvsmarket.server.core;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * The output stage of {@link CoreWriter}: what happens to a batch once its events are durable, in the order of the batches. It publishes
 * the read views the logic thread prepared for the batch (F8: a view never shows a version that is not on disk), runs the commands'
 * after-commit actions (the push notices among them) and answers the callers. Nothing here touches the engine's state: the views were built
 * from it by the logic thread, at the moment the batch was applied.
 *
 * <p>A batch that is {@linkplain WriterBatch#poisoned poisoned}, or ends in a command that left the engine half changed
 * ({@linkplain WriterBatch#tainted tainted}), is only counted off here: the logic thread answers it after it has rebuilt the engine.
 */
final class OutputStage implements Runnable {

    private static final Logger LOG = Logger.getLogger(OutputStage.class);

    /** The name of the thread. */
    static final String THREAD = "herd-core-output";

    private final CoreWriter.Host host;
    private final WriteContext ctx;
    private final Semaphore room;
    private final BlockingQueue<WriterBatch> in = new LinkedBlockingQueue<>();
    private volatile boolean stopping;
    /** For tests: thrown once the next batch is in hand (a crash of the thread). */
    volatile Error crash;

    OutputStage(CoreWriter.Host host, WriteContext ctx, Semaphore room) {
        this.host = host;
        this.ctx = ctx;
        this.room = room;
    }

    void hand(WriterBatch batch) {
        in.add(batch);
    }

    void stop() {
        stopping = true;
    }

    @Override
    public void run() {
        while (true) {
            WriterBatch batch;
            try {
                batch = in.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (stopping) {
                    return;
                }
                continue;
            }
            if (batch == null) {
                if (stopping) {
                    return;
                }
                continue;
            }
            Error c = crash;
            if (c != null) {
                throw c;
            }
            process(batch);
        }
    }

    private void process(WriterBatch batch) {
        long t0 = System.nanoTime();
        String outcome = "COMMITTED";
        try {
            if (batch.poisoned) {
                outcome = "FAILED";
            } else if (batch.tainted != null) {
                outcome = "TAINTED"; // the logic thread rebuilds the engine, then answers
            } else {
                ctx.commitTook(batch.syncNanos);
                publish(batch);
                for (WriterSlot<?> s : batch.accepted) {
                    for (Runnable action : s.staged.afterCommit) {
                        try {
                            action.run();
                        } catch (Throwable e) {
                            LOG.error("an action after the commit of the batch failed", e);
                        }
                    }
                    s.succeed();
                }
            }
        } catch (Throwable e) {
            LOG.error("the output stage of the core writer failed - the callers of the batch are answered with the failure", e);
            for (WriterSlot<?> s : batch.accepted) {
                s.fail(e);
            }
        } finally {
            if (batch.jfr != null) {
                batch.jfr.outcome = outcome;
                batch.jfr.commitNs = batch.syncNanos;
                batch.jfr.syncGroup = batch.syncGroup;
                batch.jfr.journalWaitNs = batch.journaledAtNanos == 0 ? 0 : Math.max(0, batch.journaledAtNanos - batch.handedAtNanos);
                batch.jfr.outputNs = System.nanoTime() - t0;
                batch.jfr.end();
                batch.jfr.commit();
            }
            batch.done = true;
            room.release();
        }
    }

    /** Hands the prepared views to the host; a failure is logged, never thrown (the views then stay at the last version, and the next
     *  batch publishes on top of what was prepared for this one). */
    private void publish(WriterBatch batch) {
        if (batch.accepted.isEmpty() || batch.views == null) {
            return;
        }
        try {
            host.publishViews(batch.views, batch.version);
        } catch (Throwable e) {
            LOG.error("the read views could not be published after the commit of the batch", e);
        }
    }
}
