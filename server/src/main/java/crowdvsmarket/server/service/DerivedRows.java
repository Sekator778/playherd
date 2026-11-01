package crowdvsmarket.server.service;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jboss.logging.Logger;

/**
 * The rows of the database that are derived from events of the journal and are written after the batch that holds the events is durable,
 * by a thread of their own - not by the core writer, whose turn never touches the database (D-107, docs/research/lmax_roadmap.md step 3).
 * Today that is the result row of a cancelled round ({@link RoundCancellations#cancel}).
 *
 * <p>Jobs run one at a time in the order they were queued. A job is idempotent (it leaves a row that is there already alone): the rebuild
 * of the engine repairs, from the journal, every row that is missing, and may run while a job is still queued. A job that fails is tried
 * again a few times with a growing pause, then dropped and counted: the next rebuild writes its row. The queue, the failures and the
 * oldest waiting job are in {@code /api/admin/status} ({@link Status}); {@link #awaitIdle} lets a caller wait, outside every lock, until
 * what was queued before the call is written.
 */
@ApplicationScoped
public class DerivedRows {

    private static final Logger LOG = Logger.getLogger(DerivedRows.class);

    /** One piece of database work; may throw. */
    @FunctionalInterface
    public interface Job {
        void run() throws Exception;
    }

    /** Tries per job before it is left to the next rebuild. */
    static final int ATTEMPTS = 4;
    /** The pause before the second try; doubles with each. */
    static final long RETRY_PAUSE_MS = 200L;

    private record Queued(String what, Job job, long queuedAtMs) {
    }

    private final LinkedBlockingQueue<Queued> queue = new LinkedBlockingQueue<>();
    private final Object progress = new Object();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong done = new AtomicLong(); // finished: written or dropped
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failedAttempts = new AtomicLong();
    private volatile String lastError;
    private volatile long lastWrittenAtMs;
    private volatile Thread thread;
    private volatile boolean running = true;

    /** Queues {@code job}; returns at once. {@code what} names it in the log. */
    public void submit(String what, Job job) {
        ensureThread();
        submitted.incrementAndGet();
        queue.add(new Queued(what, job, System.currentTimeMillis()));
    }

    private synchronized void ensureThread() {
        if (thread == null && running) {
            Thread t = new Thread(this::loop, "herd-derived-rows");
            t.setDaemon(true);
            thread = t;
            t.start();
        }
    }

    private void loop() {
        while (running) {
            Queued next;
            try {
                next = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (!running) {
                    return;
                }
                continue;
            }
            if (next == null) {
                continue;
            }
            try {
                runWithRetries(next);
            } finally {
                synchronized (progress) {
                    done.incrementAndGet();
                    progress.notifyAll();
                }
            }
        }
    }

    private void runWithRetries(Queued q) {
        long pause = RETRY_PAUSE_MS;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                q.job().run();
                written.incrementAndGet();
                lastWrittenAtMs = System.currentTimeMillis();
                return;
            } catch (Throwable e) {
                failedAttempts.incrementAndGet();
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                if (attempt == ATTEMPTS || !running) {
                    dropped.incrementAndGet();
                    LOG.errorf(e, "the row(s) of %s could not be written (%d tries) - the next rebuild of the engine writes them from the "
                            + "journal", q.what(), attempt);
                    return;
                }
                LOG.warnf("the row(s) of %s could not be written (try %d of %d): %s", q.what(), attempt, ATTEMPTS, e.toString());
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException ie) {
                    if (!running) {
                        dropped.incrementAndGet();
                        return;
                    }
                }
                pause *= 2;
            }
        }
    }

    /**
     * Waits until every job queued before this call has run (written or dropped), for at most {@code timeoutMs}; true when they have. Never
     * call it holding the engine lock or on the core writer's thread.
     */
    public boolean awaitIdle(long timeoutMs) {
        long target = submitted.get();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        synchronized (progress) {
            while (done.get() < target) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    progress.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /** The operator's view: {@code /api/admin/status} {@code derivedRows}. {@code pending}: jobs waiting or running; {@code oldestPendingMs}:
     *  how long the oldest waiting one has waited ({@code 0}: none waiting). */
    public record Status(int pending, long oldestPendingMs, long written, long dropped, long failedAttempts, String lastError,
                         long lastWrittenAtMs) {
    }

    public Status status() {
        Queued oldest = queue.peek();
        long lag = oldest == null ? 0 : Math.max(0, System.currentTimeMillis() - oldest.queuedAtMs());
        long pending = Math.max(0, submitted.get() - done.get());
        return new Status((int) pending, lag, written.get(), dropped.get(), failedAttempts.get(), lastError, lastWrittenAtMs);
    }

    void onStop(@Observes ShutdownEvent ev) {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
