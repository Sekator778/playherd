package crowdvsmarket.server.core;

import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.jfr.HerdWriterBatch;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

/**
 * The one writer (architecture plan F6, docs/research/lmax_roadmap.md step 3): every change of the game is a {@link Command}
 * that one thread runs, in the order the commands arrived. The loop:
 * <ol>
 *   <li>take a command (waiting for it), then every command that is already queued, up to {@code maxBatch}; with
 *       {@code maxWaitMs} above zero and more than one command in hand, wait up to that long for more;
 *   <li>under the engine write lock run the commands one after the other: each validates against the state the ones before it
 *       left and applies its events to the engine ({@link WriteContext#record}); a refusal is answered at once and leaves
 *       nothing behind;
 *   <li>release the engine lock, then ONE append of the whole batch to the journal, and its sync to disk ({@link EventStore#appendAll}:
 *       one excerpt of a Chronicle Queue, all or nothing - Р-107; Postgres is not touched);
 *   <li>after the append, publish the read views, run the commands' after-commit actions and answer the callers: <b>a caller is answered
 *       only when its command is durable</b>.
 * </ol>
 * The engine write lock is therefore held for the commands' own work (memory, tens of microseconds each) and never across a
 * disk or database round trip; the append happens with the lock released, and the appends of successive batches are in the
 * order of the batches, so the journal is in the order the events were applied.
 *
 * <p><b>Fail-stop.</b> An append (or its sync) that fails leaves the engine ahead of the journal by the batch's events: every command of
 * the batch is answered 503 {@code ENGINE_NOT_READY} (the failure attached as its cause), the engine is rebuilt from the
 * journal ({@link Host#recover}), and the writer goes on with the commands behind. A command that throws after it has applied
 * something has the same effect for itself: what the commands before it in the batch had accepted is committed, the engine is
 * rebuilt, the command gets its own exception, and the commands after it run in the next batch.
 *
 * <p><b>The gate.</b> {@link Host#gate()} is held for a whole cycle, from the first command to the answers. Whoever replaces
 * the engine's state (a rebuild) or takes what the engine has produced (the ledger rows) takes it first, so they never see a
 * batch half way: applied in memory, not yet in the journal.
 *
 * <p><b>No database in the turn.</b> A command touches the engine and stages events, nothing else: a command that needs the database
 * (the reservation of an on-chain deposit coin, a market's row) has its database work done by its caller before it is submitted, and
 * the rows that are derived from its events (a withdrawal's row, the result row of a cancelled round, a market's row) are written
 * by its caller or by {@code DerivedRows} after the batch is durable, and are repaired from the journal by the next rebuild.
 * The only database access of this class's thread is the rebuild after a failed commit ({@link Host#recover}).
 */
public final class CoreWriter implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(CoreWriter.class);

    /** What the writer needs of the game. */
    public interface Host {
        /** Held for a whole cycle of the writer; see the class comment. Reentrant. */
        ReentrantLock gate();

        /** Takes the engine's write lock (reentrant). */
        void lockEngine();

        void unlockEngine();

        /** Whether the engine's memory can be trusted ({@code EngineRebuildService#isReady}). */
        boolean ready();

        /** Applies the event to the live engine. */
        Object apply(GameEvent event);

        /** The engine may be ahead of the journal: rebuild it from the journal. Called holding the engine write lock; never
         *  throws (a rebuild that fails leaves the engine not ready). */
        void recover(Throwable cause, String key, long nowMs);

        /**
         * A batch has committed: {@code events} are its events in the order of the journal, {@code version} the id of the last
         * event of the journal (negative when the batch wrote none). Called on the writer thread, holding the gate, before any
         * caller is answered; the engine holds the batch and nothing else. The host publishes its read views here (F8). Must not
         * throw.
         */
        void committed(List<GameEvent> events, long version);
    }

    private final AtomicInteger inFlight = new AtomicInteger();

    private final class Slot<T> {
        final Command<T> command;
        final boolean needsReady;
        final String key;
        final long nowMs;
        final long submittedNanos = System.nanoTime();
        final CompletableFuture<T> future = new CompletableFuture<>();
        final WriteContext.Staged staged = new WriteContext.Staged();
        T result;

        Slot(Command<T> command, boolean needsReady, String key, long nowMs) {
            this.command = command;
            this.needsReady = needsReady;
            this.key = key == null ? "command" : key;
            this.nowMs = nowMs;
        }

        void fail(Throwable cause) {
            if (future.completeExceptionally(cause)) {
                inFlight.decrementAndGet();
            }
        }

        void succeed() {
            if (future.complete(result)) {
                inFlight.decrementAndGet();
            }
        }
    }

    private final Host host;
    private final EventStore eventStore;
    private final int maxBatch;
    private final long maxWaitMs;
    private final BlockingQueue<Slot<?>> queue;
    private final WriteContext ctx;
    private final Thread thread;
    private volatile boolean running = true;

    public CoreWriter(Host host, EventStore eventStore, int maxBatch, long maxWaitMs, int queueCapacity) {
        this.host = host;
        this.eventStore = eventStore;
        this.maxBatch = Math.max(1, maxBatch);
        this.maxWaitMs = Math.max(0, maxWaitMs);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.ctx = new WriteContext(host);
        this.thread = new Thread(this::loop, IoGuard.WRITER_THREAD);
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /** Whether the calling thread is the writer (a command that calls another command runs it in place). */
    public boolean onWriterThread() {
        return Thread.currentThread() == thread;
    }

    /**
     * Queues {@code command} and waits for its answer: until the batch it is in has committed. Exceptions of the command
     * come out as they were thrown. Called from the writer thread itself, the command runs in place, as part of the one that
     * is running.
     *
     * @param needsReady the command is refused with 503 while the engine cannot be trusted
     * @param key        what to call the command in the log of a rebuild it causes
     * @param nowMs      the clock of the command (a rebuild seeds the series as of that instant)
     */
    public <T> T submit(Command<T> command, boolean needsReady, String key, long nowMs) {
        if (onWriterThread()) {
            return command.run(ctx);
        }
        if (!running) {
            throw ApiException.engineNotReady();
        }
        Slot<T> slot = new Slot<>(command, needsReady, key, nowMs);
        inFlight.incrementAndGet();
        try {
            queue.put(slot);
        } catch (InterruptedException e) {
            inFlight.decrementAndGet();
            Thread.currentThread().interrupt();
            throw ApiException.engineNotReady();
        }
        if (!running && queue.remove(slot)) {
            inFlight.decrementAndGet();
            throw ApiException.engineNotReady();
        }
        try {
            return slot.future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }

    /** The number of commands waiting. */
    public int queued() {
        return queue.size();
    }

    /** The number of commands that have been submitted and not answered yet: waiting, or in the batch being run. */
    public int inFlight() {
        return inFlight.get();
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Slot<?>> rest = new ArrayList<>();
        queue.drainTo(rest);
        for (Slot<?> s : rest) {
            s.fail(ApiException.engineNotReady());
        }
    }

    // ---------------------------------------------------------------------------------------

    private void loop() {
        List<Slot<?>> carry = new ArrayList<>();
        while (running) {
            List<Slot<?>> batch = new ArrayList<>(carry);
            carry.clear();
            try {
                if (batch.isEmpty()) {
                    Slot<?> first = queue.poll(250, TimeUnit.MILLISECONDS);
                    if (first == null) {
                        continue;
                    }
                    batch.add(first);
                }
                fill(batch);
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
                continue;
            }
            try {
                cycle(batch, carry);
            } catch (Throwable e) {
                LOG.error("the core writer's cycle failed - the commands it had are answered with the failure", e);
                for (Slot<?> s : batch) {
                    if (!s.future.isDone()) {
                        s.fail(e);
                    }
                }
                carry.clear();
            }
        }
        for (Slot<?> s : carry) {
            s.fail(ApiException.engineNotReady());
        }
    }

    /** Adds to {@code batch} what is queued already, up to {@code maxBatch}; waits up to {@code maxWaitMs} for more when
     *  there is more than one command in hand. */
    private void fill(List<Slot<?>> batch) throws InterruptedException {
        queue.drainTo(batch, maxBatch - batch.size());
        if (maxWaitMs <= 0 || batch.size() < 2) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        while (batch.size() < maxBatch) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            Slot<?> next = queue.poll(left, TimeUnit.NANOSECONDS);
            if (next == null) {
                return;
            }
            batch.add(next);
            queue.drainTo(batch, maxBatch - batch.size());
        }
    }

    /** Runs {@code batch} as one stretch (one batch). What is left after a command made the engine be rebuilt goes to {@code carry},
     *  for the next cycle. */
    private void cycle(List<Slot<?>> batch, List<Slot<?>> carry) {
        int consumed = runStretch(batch);
        if (consumed < batch.size()) {
            carry.addAll(batch.subList(consumed, batch.size()));
        }
    }

    private <T> void runAndKeep(Slot<T> s) {
        s.result = s.command.run(ctx);
    }

    /** One batch: returns how many of {@code stretch}'s commands it dealt with (fewer than all when one of them made the
     *  engine be rebuilt: the rest has not run). */
    private int runStretch(List<Slot<?>> stretch) {
        HerdWriterBatch jfr = new HerdWriterBatch();
        boolean rec = jfr.isEnabled();
        if (rec) {
            jfr.begin();
            jfr.queued = queue.size();
            jfr.outcome = "COMMITTED";
        }
        host.gate().lock();
        try {
            List<Slot<?>> accepted = new ArrayList<>(stretch.size());
            Slot<?> tainted = null;
            Throwable taint = null;
            int consumed = 0;
            long lockedAt;
            host.lockEngine();
            lockedAt = System.nanoTime();
            try {
                boolean ready = host.ready();
                long maxWait = 0;
                for (Slot<?> s : stretch) {
                    consumed++;
                    if (s.needsReady && !ready) {
                        s.fail(ApiException.engineNotReady());
                        continue;
                    }
                    long waited = lockedAt - s.submittedNanos;
                    maxWait = Math.max(maxWait, waited);
                    ctx.begin(s.staged, waited);
                    try {
                        runAndKeep(s);
                        accepted.add(s);
                    } catch (Throwable e) {
                        if (s.staged.touched || e instanceof Error) {
                            tainted = s;
                            taint = e;
                            break;
                        }
                        s.fail(e); // a refusal: nothing was applied, nothing staged is kept
                    }
                }
                if (rec) {
                    jfr.maxQueueWaitNs = maxWait;
                }
            } finally {
                host.unlockEngine();
                if (rec) {
                    jfr.lockedNs = System.nanoTime() - lockedAt;
                }
            }

            List<EventStore.Pending> events = new ArrayList<>();
            for (Slot<?> s : accepted) {
                events.addAll(s.staged.events);
            }
            Throwable commitFailure = null;
            long commitNs = 0;
            long version = -1;
            if (!events.isEmpty()) {
                long t0 = System.nanoTime();
                try {
                    version = eventStore.appendAll(events); // the durability point: the excerpt is on disk when this returns
                } catch (Throwable e) {
                    commitFailure = e;
                }
                commitNs = System.nanoTime() - t0;
            }
            ctx.commitTook(commitNs);
            if (rec) {
                jfr.commands = accepted.size() + (tainted == null ? 0 : 1);
                jfr.events = events.size();
                jfr.commitNs = commitNs;
            }

            if (commitFailure == null) {
                if (tainted != null) {
                    // the engine holds what the failing command applied before it threw: the views must not (nor may a caller who
                    // is answered meanwhile find nothing of its own change) - the rebuild from the journal publishes the committed
                    // state in full, so it comes first
                    recover(tainted, taint, List.of(tainted));
                } else if (!accepted.isEmpty()) {
                    publish(events, version);
                }
                for (Slot<?> s : accepted) {
                    for (Runnable action : s.staged.afterCommit) {
                        try {
                            action.run();
                        } catch (Throwable e) {
                            LOG.error("an action after the commit of the batch failed", e);
                        }
                    }
                    s.succeed();
                }
                if (tainted != null) {
                    tainted.fail(taint);
                    if (rec) {
                        jfr.outcome = "TAINTED";
                    }
                }
            } else {
                LOG.errorf(commitFailure, "the batch of %d command(s) could not be committed - none of them is durable; "
                        + "rebuilding the engine from the journal", accepted.size());
                Slot<?> first = accepted.isEmpty() ? tainted : accepted.get(0);
                List<Slot<?>> undone = new ArrayList<>(accepted);
                if (tainted != null) {
                    undone.add(tainted);
                }
                recover(first, commitFailure, undone);
                for (Slot<?> s : accepted) {
                    ApiException notReady = ApiException.engineNotReady();
                    notReady.initCause(commitFailure);
                    s.fail(notReady);
                }
                if (tainted != null) {
                    taint.addSuppressed(commitFailure);
                    tainted.fail(taint);
                }
                if (rec) {
                    jfr.outcome = "FAILED";
                }
            }
            return consumed;
        } finally {
            host.gate().unlock();
            if (rec) {
                jfr.end();
                jfr.commit();
            }
        }
    }

    /** Hands the committed batch to the host, which publishes its read views; a failure is logged, never thrown. */
    private void publish(List<EventStore.Pending> events, long version) {
        try {
            List<GameEvent> committed = new ArrayList<>(events.size());
            for (EventStore.Pending p : events) {
                committed.add(p.event());
            }
            host.committed(committed, version);
        } catch (Throwable e) {
            LOG.error("the read views could not be published after the commit of the batch", e);
        }
    }

    /** The engine may be ahead of the journal: tells what the commands changed outside it that it did not commit, and
     *  rebuilds, holding the engine lock. */
    private void recover(Slot<?> cause, Throwable failure, List<Slot<?>> undone) {
        host.lockEngine();
        try {
            for (Slot<?> s : undone) {
                for (Runnable action : s.staged.onRollback) {
                    try {
                        action.run();
                    } catch (Throwable e) {
                        LOG.error("an action after the rollback of the batch failed", e);
                    }
                }
            }
            host.recover(failure, cause == null ? "batch" : cause.key, cause == null ? System.currentTimeMillis() : cause.nowMs);
        } finally {
            host.unlockEngine();
        }
    }
}
