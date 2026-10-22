package crowdvsmarket.server.core;

import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.jfr.HerdWriterBatch;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import org.jboss.logging.Logger;

/**
 * The one writer (architecture plan F6, docs/research/lmax_roadmap.md step 3), built as the stages of the LMAX architecture
 * (https://martinfowler.com/articles/lmax.html): every change of the game is a {@link Command} that ONE thread runs, in the order the
 * commands arrived, and that thread <b>never waits for the disk</b>. Three threads, three stages, each handing the batch to the next:
 * <ol>
 *   <li><b>The logic thread</b> (this class, {@link IoGuard#WRITER_THREAD}): takes a command, then every command that is already queued, up
 *       to {@code maxBatch}; under the engine write lock runs them one after the other - each validates against the state the ones before it
 *       left and applies its events to the engine ({@link WriteContext#record}), a refusal is answered at once and leaves nothing behind -
 *       and builds the read views of the state the batch left ({@link Host#prepareViews}). It then hands the batch over and starts the
 *       next one. It touches no disk, no database and no network (the rebuild after a failure is the one exception), and waits only for
 *       room in the pipeline ({@code pipelineDepth} batches applied and not yet through the output stage: back-pressure).
 *   <li><b>The journal stage</b> ({@link JournalStage}): appends the batches to the journal in the order they were applied and syncs them
 *       to disk, writing whatever is waiting as one excerpt with one sync (group commit - the group grows by itself while a sync is under
 *       way). The only place where the journal is written and synced.
 *   <li><b>The output stage</b> ({@link OutputStage}): for a durable batch publishes the read views, runs the commands' after-commit actions
 *       (the push notices) and answers the callers.
 * </ol>
 * Between the hand-over and the sync the engine is <i>ahead</i> of the journal. That state is speculative and stays inside the pipeline:
 * <b>a caller is answered, a view published and a push sent only when the events of its batch are synced</b>, the journal is in the order the
 * events were applied, and nothing that reads the engine's memory while it is ahead (a snapshot, the ledger rows, a rebuild) runs before
 * the pipeline is drained (the gate, below). The events journaled are the events the commands produced, not the commands.
 *
 * <p><b>Fail-stop.</b> An append (or its sync) that fails makes the journal stage drop everything behind it, and the logic thread - woken -
 * stops applying, waits until every batch it handed over has come back, tells what the commands changed outside the journal
 * ({@link WriteContext#onRollback}), rebuilds the engine from the journal ({@link Host#recover}) - so the speculative state, including the
 * batches applied after the failing one, never leaks - and answers every command not yet synced with 503 {@code ENGINE_NOT_READY} (the
 * failure attached as its cause). Then it goes on with the commands behind. A command that throws after it has applied something has the
 * same effect for itself: what the commands before it in the batch had accepted is committed (the logic thread waits for the pipeline to
 * drain, then rebuilds), the command gets its own exception, and the commands after it run in the next batch.
 *
 * <p><b>A thread that dies.</b> Each stage answers for the failures of its own work, so a thread of the writer that lets an exception out
 * (an {@link Error} where nothing expects one) is a writer that can no longer move: the batches behind it would wait forever and so would
 * their callers. The writer then stops at once - every command waiting is answered 503, new ones are refused - and tells the host
 * ({@link Host#stageDied}), which stops the server so that it starts again from the snapshot and the journal (LMAX: the state in memory is
 * only a cache of the journal).
 *
 * <p><b>The gate.</b> {@link Host#gate()} is held by the logic thread for the length of its turn, from the first command to the hand-over of
 * the batch. Whoever replaces the engine's state (a rebuild), takes a snapshot of it or takes what the engine has produced (the ledger rows)
 * enters through {@link #enterGate()}: it takes the gate (so no batch is half way in memory) and waits until the pipeline is drained (so the
 * engine is exactly what the journal holds, and every view is published). The gate is a fair lock so that the logic thread, which takes it
 * again at once, cannot starve them.
 *
 * <p><b>No database in the turn.</b> A command touches the engine and stages events, nothing else: a command that needs the database
 * (the reservation of an on-chain deposit coin, a market's row) has its database work done by its caller before it is submitted, and
 * the rows that are derived from its events (a withdrawal's row, the result row of a cancelled round, a market's row) are written
 * by its caller or by {@code DerivedRows} after the batch is durable, and are repaired from the journal by the next rebuild.
 * The only access to the disk or the database of the logic thread is the rebuild after a failure ({@link Host#recover}).
 */
public final class CoreWriter implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(CoreWriter.class);

    /** How many batches may be applied and not yet through the output stage, when the caller does not say. */
    public static final int DEFAULT_PIPELINE_DEPTH = 8;

    /** How long a caller waits for room in a full queue, when the caller does not say (milliseconds): then it is answered 503. */
    public static final long DEFAULT_OFFER_TIMEOUT_MS = 300;

    /** What the writer needs of the game. */
    public interface Host {
        /** Held by the logic thread for its turn, see the class comment. Reentrant. */
        ReentrantLock gate();

        /** Takes the engine's write lock (reentrant). */
        void lockEngine();

        void unlockEngine();

        /** Whether the engine's memory can be trusted ({@code EngineRebuildService#isReady}). */
        boolean ready();

        /** Applies the event to the live engine. */
        Object apply(GameEvent event);

        /** A thread of the writer has died (see the class comment): the writer has stopped and answered whoever waited with 503. The host
         *  stops the server so that it starts again from the journal. Called once, on a thread of its own. */
        void stageDied(String thread, Throwable cause);

        /** The engine may be ahead of the journal: rebuild it from the journal. Called holding the gate and the engine write lock, with the
         *  pipeline drained; never throws (a rebuild that fails leaves the engine not ready). */
        void recover(Throwable cause, String key, long nowMs);

        /**
         * A batch has been applied: {@code events} are its events in the order of the journal. Called on the logic thread, holding the gate
         * and the engine write lock, right after the batch's last command - the engine holds the batch and everything before it, nothing
         * else. The host builds the read views (F8) of that state and returns them <b>without publishing</b>: they become visible through
         * {@link #publishViews} once the batch is durable. The result is opaque to the writer; {@code null} means there is nothing to
         * publish. Must not throw.
         */
        Object prepareViews(List<GameEvent> events);

        /**
         * The batch whose views {@link #prepareViews} returned is durable: make them visible. {@code version} is the id of the last event of
         * the journal the batch includes (negative when it wrote none). Called on the output stage, in the order of the batches, before any
         * caller of the batch is answered. Reads no state of the engine. Must not throw.
         */
        void publishViews(Object prepared, long version);
    }

    /** What the pipeline has done so far; the admin status shows it. */
    public record Stats(int pipelineDepth, int depthNow, int maxDepthSeen, long batches, long syncs, long syncedBatches,
                        long maxSyncGroup, long syncNanos, long backPressureNanos, long backPressureWaits, long logicBusyNanos,
                        int journalWaiting) {
    }

    private final AtomicInteger inFlight = new AtomicInteger();
    private final Host host;
    private final int maxBatch;
    private final long maxWaitMs;
    private final long offerTimeoutMs;
    private final BlockingQueue<WriterSlot<?>> queue;
    private final WriteContext ctx;
    private final int pipelineDepth;
    /** One permit per batch that may be in the pipeline: taken by the logic thread at the hand-over, given back by the output stage. */
    private final Semaphore room;
    private final JournalStage journal;
    private final OutputStage output;
    private final Thread thread;
    private final Thread journalThread;
    private final Thread outputThread;
    /** The batches handed over and not yet cleared, oldest first. The logic thread's; {@link #close} reads it after that thread has ended. */
    private final ArrayDeque<WriterBatch> inPipeline = new ArrayDeque<>();
    private volatile boolean running = true;
    /** What killed a thread of the writer; {@code null} while all three are alive. */
    private volatile Throwable dead;
    private final AtomicBoolean stopping = new AtomicBoolean();
    /** For tests: the logic thread throws this once it has taken the commands of its next batch. */
    private volatile Error crash;
    /** The commands the logic thread has taken from the queue for the batch it is building, and the ones it carries to the next batch:
     *  the logic thread's, read by {@link #close} once it has ended (or given up on it) so that none of them is left unanswered. */
    private volatile List<WriterSlot<?>> inHand = List.of();
    private final List<WriterSlot<?>> carry = new ArrayList<>();

    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong backPressureNanos = new AtomicLong();
    private final AtomicLong backPressureWaits = new AtomicLong();
    private final AtomicLong logicBusyNanos = new AtomicLong();
    private final AtomicInteger maxDepthSeen = new AtomicInteger();

    public CoreWriter(Host host, EventStore eventStore, int maxBatch, long maxWaitMs, int queueCapacity) {
        this(host, eventStore, maxBatch, maxWaitMs, queueCapacity, DEFAULT_PIPELINE_DEPTH);
    }

    public CoreWriter(Host host, EventStore eventStore, int maxBatch, long maxWaitMs, int queueCapacity, int pipelineDepth) {
        this(host, eventStore, maxBatch, maxWaitMs, queueCapacity, pipelineDepth, DEFAULT_OFFER_TIMEOUT_MS);
    }

    public CoreWriter(Host host, EventStore eventStore, int maxBatch, long maxWaitMs, int queueCapacity, int pipelineDepth,
                      long offerTimeoutMs) {
        this.host = host;
        this.offerTimeoutMs = Math.max(0, offerTimeoutMs);
        this.maxBatch = Math.max(1, maxBatch);
        this.maxWaitMs = Math.max(0, maxWaitMs);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.ctx = new WriteContext(host);
        this.pipelineDepth = Math.max(1, pipelineDepth);
        this.room = new Semaphore(this.pipelineDepth, true);
        this.output = new OutputStage(host, ctx, room);
        this.journal = new JournalStage(eventStore, output, queue);
        this.journalThread = daemon(guarded(journal, JournalStage.THREAD), JournalStage.THREAD);
        this.outputThread = daemon(guarded(output, OutputStage.THREAD), OutputStage.THREAD);
        this.thread = daemon(guarded(this::loop, IoGuard.WRITER_THREAD), IoGuard.WRITER_THREAD);
        this.journalThread.start();
        this.outputThread.start();
        this.thread.start();
    }

    private static Thread daemon(Runnable body, String name) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }

    /** The thread's body, with whatever escapes it reported as the death of the writer. */
    private Runnable guarded(Runnable body, String name) {
        return () -> {
            try {
                body.run();
            } catch (Throwable e) {
                died(name, e);
            }
        };
    }

    /**
     * A thread of the writer let an exception out and is gone: nothing behind it moves any more. Stops the writer - new commands are
     * refused, every command waiting is answered 503 - and tells the host, which stops the server. Once, whichever thread dies first.
     */
    private void died(String name, Throwable e) {
        if (!running && dead == null) {
            LOG.errorf(e, "the %s thread of the core writer ended with an exception while the writer was closing", name);
            return;
        }
        if (dead == null) {
            dead = e;
        }
        running = false;
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        LOG.errorf(e, "the %s thread of the core writer died - the writer stops, the commands waiting are answered 503 and the server is "
                + "stopped so that it starts again from the snapshot and the journal", name);
        Thread stopper = new Thread(() -> {
            close();
            try {
                host.stageDied(name, e);
            } catch (Throwable t) {
                LOG.error("the host could not be told that the core writer died", t);
            }
        }, "herd-core-stop");
        stopper.setDaemon(true);
        stopper.start();
    }

    /** Whether a thread of the writer has died: the writer then answers nothing but 503. */
    public boolean dead() {
        return dead != null;
    }

    /** For tests: the named thread of the writer throws {@code e} as soon as it has work in hand (a crash). */
    void crashForTests(String thread, Error e) {
        switch (thread) {
            case JournalStage.THREAD -> journal.crash = e;
            case OutputStage.THREAD -> output.crash = e;
            case IoGuard.WRITER_THREAD -> crash = e;
            default -> throw new IllegalArgumentException(thread);
        }
    }

    /** Whether the calling thread is the logic thread (a command that calls another command runs it in place). */
    public boolean onWriterThread() {
        return Thread.currentThread() == thread;
    }

    /** Whether the calling thread is one of the writer's own threads: the logic thread, the journal stage or the output stage. */
    public boolean onPipelineThread() {
        Thread t = Thread.currentThread();
        return t == thread || t == journalThread || t == outputThread;
    }

    /**
     * Queues {@code command} and waits for its answer: until the batch it is in is durable. Exceptions of the command
     * come out as they were thrown. Called from the logic thread itself, the command runs in place, as part of the one that
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
        WriterSlot<T> slot = new WriterSlot<>(command, needsReady, key, nowMs, inFlight);
        inFlight.incrementAndGet();
        try {
            if (!queue.offer(slot, offerTimeoutMs, TimeUnit.MILLISECONDS)) {
                // a full queue must not park every writing request of the server: the caller is told to retry
                inFlight.decrementAndGet();
                throw new ApiException(Enums.ApiErrorCode.ENGINE_NOT_READY, 503,
                        "the server is busy - nothing was changed, please try again in a moment");
            }
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

    /** The number of commands that have been submitted and not answered yet: waiting, in the batch being run, or applied and not durable. */
    public int inFlight() {
        return inFlight.get();
    }

    /** The number of batches applied and not yet through the output stage. */
    public int pipelineDepthNow() {
        return pipelineDepth - room.availablePermits();
    }

    public Stats stats() {
        return new Stats(pipelineDepth, pipelineDepthNow(), maxDepthSeen.get(), batches.get(), journal.syncs.get(),
                journal.syncedBatches.get(), journal.maxGroup.get(), journal.syncNanos.get(), backPressureNanos.get(),
                backPressureWaits.get(), logicBusyNanos.get(), journal.waiting());
    }

    // ---------------------------------------------------------------------------------------
    // The gate
    // ---------------------------------------------------------------------------------------

    /**
     * Takes the gate and waits until the pipeline is drained: every batch the logic thread applied is durable and answered, and the logic
     * thread starts none while the caller holds the gate. From here the engine is exactly what the journal holds and the views are published
     * up to it. If a sync has failed, the gate is let go and tried again once the logic thread has rebuilt the engine (the speculative state
     * is not for anyone to see). Release with {@code host.gate().unlock()}. Reentrant; from the writer's own threads it is the plain lock.
     */
    public void enterGate() {
        ReentrantLock gate = host.gate();
        while (true) {
            gate.lock();
            if (dead != null && !onPipelineThread()) {
                // the engine may hold batches that never reached the journal and never will: nobody may read it (a snapshot of it would
                // carry them into the next start)
                gate.unlock();
                throw ApiException.engineNotReady();
            }
            if (!running || onPipelineThread() || gate.getHoldCount() > 1) {
                return;
            }
            if (!drain()) {
                gate.unlock();
                throw ApiException.engineNotReady(); // a thread of the writer died: the pipeline never drains, the server is stopping
            }
            if (journal.failure() == null) {
                return;
            }
            gate.unlock();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    /** Waits until no batch is in the pipeline; {@code false} when a thread of the writer died meanwhile (the pipeline then never drains).
     *  The caller keeps the logic thread from handing over more (it holds the gate, or is the logic thread between two hand-overs). */
    private boolean drain() {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    if (room.tryAcquire(pipelineDepth, 100, TimeUnit.MILLISECONDS)) {
                        room.release(pipelineDepth);
                        return true;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
                if (dead != null) {
                    return false;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        join(thread, 2_000L);
        // what the logic thread handed over is written and answered as usual; the stages end when their queue is empty
        journal.stop();
        join(journalThread, 3_000L);
        output.stop();
        join(outputThread, 3_000L);
        List<WriterSlot<?>> left = new ArrayList<>();
        try {
            for (WriterBatch b : inPipeline) {
                left.addAll(b.accepted);
                if (b.tainted != null) {
                    left.add(b.tainted);
                }
            }
            left.addAll(inHand);
            left.addAll(carry);
        } catch (RuntimeException e) {
            // the logic thread did not end in time and is still changing them: answer what could be read, the rest times out on its own
            LOG.error("the commands held by the core writer's logic thread could not all be read while closing", e);
        }
        for (WriterSlot<?> s : left) {
            s.fail(ApiException.engineNotReady()); // no effect on a command already answered
        }
        List<WriterSlot<?>> rest = new ArrayList<>();
        queue.drainTo(rest);
        for (WriterSlot<?> s : rest) {
            s.fail(ApiException.engineNotReady());
        }
    }

    private static void join(Thread t, long ms) {
        try {
            t.join(ms);
            if (t.isAlive()) {
                t.interrupt();
                t.join(1_000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------------------------------

    private void loop() {
        while (running) {
            if (journal.failure() != null) {
                host.gate().lock();
                try {
                    handleFailure();
                } finally {
                    host.gate().unlock();
                }
            }
            List<WriterSlot<?>> batch = new ArrayList<>(carry);
            inHand = batch;
            carry.clear();
            try {
                if (batch.isEmpty()) {
                    WriterSlot<?> first = queue.poll(250, TimeUnit.MILLISECONDS);
                    if (first == null || first == WriterSlot.WAKE) {
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
            Error c = crash;
            if (c != null) {
                throw c;
            }
            try {
                cycle(batch, carry);
            } catch (Throwable e) {
                LOG.error("the core writer's cycle failed - the commands it had are answered with the failure", e);
                for (WriterSlot<?> s : batch) {
                    if (!s.future.isDone()) {
                        s.fail(e);
                    }
                }
                carry.clear();
            }
            inHand = List.of(); // answered, in the pipeline (inPipeline) or carried
        }
        for (WriterSlot<?> s : inHand) {
            s.fail(ApiException.engineNotReady());
        }
        for (WriterSlot<?> s : carry) {
            s.fail(ApiException.engineNotReady());
        }
    }

    /** Adds to {@code batch} what is queued already, up to {@code maxBatch}; waits up to {@code maxWaitMs} for more when
     *  there is more than one command in hand. */
    private void fill(List<WriterSlot<?>> batch) throws InterruptedException {
        queue.drainTo(batch, maxBatch - batch.size());
        batch.removeIf(s -> s == WriterSlot.WAKE);
        if (maxWaitMs <= 0 || batch.size() < 2) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        while (batch.size() < maxBatch) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            WriterSlot<?> next = queue.poll(left, TimeUnit.NANOSECONDS);
            if (next == null) {
                return;
            }
            if (next != WriterSlot.WAKE) {
                batch.add(next);
            }
            queue.drainTo(batch, maxBatch - batch.size());
            batch.removeIf(s -> s == WriterSlot.WAKE);
        }
    }

    /** Runs {@code batch} as one stretch (one batch). What is left after a command made the engine be rebuilt goes to {@code carry},
     *  for the next cycle. */
    private void cycle(List<WriterSlot<?>> batch, List<WriterSlot<?>> carry) {
        int consumed = runStretch(batch);
        if (consumed < batch.size()) {
            carry.addAll(batch.subList(consumed, batch.size()));
        }
    }

    private <T> void runAndKeep(WriterSlot<T> s) {
        s.result = s.command.run(ctx);
    }

    /** One batch: returns how many of {@code stretch}'s commands it dealt with (fewer than all when one of them made the
     *  engine be rebuilt: the rest has not run). */
    private int runStretch(List<WriterSlot<?>> stretch) {
        HerdWriterBatch jfr = new HerdWriterBatch();
        boolean rec = jfr.isEnabled();
        long turnStart = System.nanoTime();
        if (rec) {
            jfr.begin();
            jfr.queued = queue.size();
            jfr.outcome = "COMMITTED";
        }
        host.gate().lock();
        boolean handed = false;
        try {
            if (journal.failure() != null) {
                handleFailure(); // the batch about to be applied would sit on a state that is doomed
            }
            if (dead != null) {
                for (WriterSlot<?> s : stretch) {
                    s.fail(ApiException.engineNotReady());
                }
                return stretch.size();
            }
            trim();
            List<WriterSlot<?>> accepted = new ArrayList<>(stretch.size());
            List<EventStore.Pending> events = new ArrayList<>();
            WriterSlot<?> tainted = null;
            Throwable taint = null;
            Object views = null;
            int consumed = 0;
            long lockedAt;
            host.lockEngine();
            lockedAt = System.nanoTime();
            try {
                boolean ready = host.ready();
                long maxWait = 0;
                for (WriterSlot<?> s : stretch) {
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
                for (WriterSlot<?> s : accepted) {
                    events.addAll(s.staged.events);
                }
                if (tainted == null && !accepted.isEmpty()) {
                    // the engine is exactly what the batch left; nothing of it is visible until the batch is durable
                    List<GameEvent> applied = new ArrayList<>(events.size());
                    for (EventStore.Pending p : events) {
                        applied.add(p.event());
                    }
                    views = host.prepareViews(applied);
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

            if (accepted.isEmpty() && tainted == null) {
                if (rec) {
                    jfr.outcome = "NOTHING";
                    jfr.logicNs = System.nanoTime() - turnStart;
                    jfr.end();
                    jfr.commit();
                }
                return consumed;
            }
            WriterBatch batch = new WriterBatch(accepted, events, views, tainted, taint, rec ? jfr : null);
            if (rec) {
                jfr.commands = accepted.size() + (tainted == null ? 0 : 1);
                jfr.events = events.size();
            }

            // the hand-over: the only wait of the logic thread, and only when the pipeline is full
            long waitFrom = System.nanoTime();
            if (!room.tryAcquire()) {
                try {
                    room.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    for (WriterSlot<?> s : accepted) {
                        s.fail(ApiException.engineNotReady());
                    }
                    if (tainted != null) {
                        tainted.fail(ApiException.engineNotReady());
                    }
                    return consumed;
                }
                long waited = System.nanoTime() - waitFrom;
                batch.backPressureNanos = waited;
                backPressureNanos.addAndGet(waited);
                backPressureWaits.incrementAndGet();
            }
            int depth = pipelineDepth - room.availablePermits();
            maxDepthSeen.accumulateAndGet(depth, Math::max);
            batches.incrementAndGet();
            batch.handedAtNanos = System.nanoTime();
            inPipeline.addLast(batch);
            handed = true;
            if (rec) {
                jfr.backPressureNs = batch.backPressureNanos;
                jfr.pipelineDepth = depth - 1;
                jfr.logicNs = batch.handedAtNanos - turnStart;
            }
            logicBusyNanos.addAndGet(batch.handedAtNanos - turnStart - batch.backPressureNanos);
            if (journal.failure() != null) {
                // the journal has failed meanwhile: this batch was applied on top of the failed ones and is dropped with them
                batch.poisoned = true;
                batch.failure = journal.failure();
                output.hand(batch);
                handleFailure();
            } else {
                journal.hand(batch);
                if (tainted != null) {
                    finishTainted(batch);
                }
            }
            return consumed;
        } finally {
            host.gate().unlock();
            if (!handed && rec && jfr.outcome != null && jfr.outcome.equals("COMMITTED")) {
                // left without handing anything over (interrupted): nothing more to say
                jfr.outcome = "INTERRUPTED";
                jfr.end();
                jfr.commit();
            }
        }
    }

    /** Clears from the front of {@code inPipeline} the batches that are through. */
    private void trim() {
        while (!inPipeline.isEmpty() && inPipeline.peekFirst().done && !inPipeline.peekFirst().poisoned) {
            inPipeline.pollFirst();
        }
    }

    /**
     * The batch ends in a command that left the engine half changed: waits for the pipeline to drain (the batch's commands before the
     * failing one are then durable, as are the batches before), rebuilds the engine from the journal, and answers. Holding the gate.
     */
    private void finishTainted(WriterBatch batch) {
        if (!drain()) {
            return; // a thread of the writer died: closing answers the batch
        }
        if (batch.poisoned || journal.failure() != null) {
            handleFailure(); // the journal failed on the way: this batch is among those that are dropped
            return;
        }
        // the engine holds what the failing command applied before it threw: the views must not (nor may a caller who is answered meanwhile
        // find nothing of its own change) - the rebuild from the journal publishes the committed state in full, so it comes first
        recover(batch.tainted, batch.taint, List.of(batch.tainted));
        ctx.commitTook(batch.syncNanos);
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
        batch.tainted.fail(batch.taint);
        if (batch.jfr != null) {
            batch.jfr.outcome = "TAINTED";
        }
        trim();
    }

    /**
     * The journal stage failed: waits until every batch handed over has come back, rebuilds the engine from the journal, and answers
     * the batches that did not make it with 503. Holding the gate, on the logic thread.
     */
    private void handleFailure() {
        if (!drain()) {
            return; // a thread of the writer died: closing answers the batches
        }
        Throwable failure = journal.failure();
        if (failure == null) {
            return;
        }
        List<WriterBatch> undone = new ArrayList<>();
        for (WriterBatch b : inPipeline) {
            if (b.poisoned) {
                undone.add(b);
            }
        }
        int commands = 0;
        List<WriterSlot<?>> slots = new ArrayList<>();
        for (WriterBatch b : undone) {
            slots.addAll(b.accepted);
            if (b.tainted != null) {
                slots.add(b.tainted);
            }
            commands += b.accepted.size();
        }
        LOG.errorf(failure, "%d batch(es) of %d command(s) could not be committed - none of them is durable; rebuilding the engine from "
                + "the journal", undone.size(), commands);
        WriterSlot<?> first = slots.isEmpty() ? null : slots.get(0);
        recover(first, failure, slots);
        for (WriterBatch b : undone) {
            for (WriterSlot<?> s : b.accepted) {
                ApiException notReady = ApiException.engineNotReady();
                notReady.initCause(failure);
                s.fail(notReady);
            }
            if (b.tainted != null) {
                b.taint.addSuppressed(failure);
                b.tainted.fail(b.taint);
            }
        }
        inPipeline.clear();
        journal.resume();
    }

    /** The engine may be ahead of the journal: tells what the commands changed outside it that it did not commit, and
     *  rebuilds, holding the engine lock. The pipeline is drained. */
    private void recover(WriterSlot<?> cause, Throwable failure, List<WriterSlot<?>> undone) {
        host.lockEngine();
        try {
            for (WriterSlot<?> s : undone) {
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
