package crowdvsmarket.server.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.persistence.Db;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The core writer on its own (architecture plan F6), with a fake engine and a fake database: what batches, in what order,
 * what is answered when, and what happens when the commit or a command fails.
 */
class CoreWriterTest {

    /** The engine and the database as the writer sees them. */
    private static final class World implements CoreWriter.Host {
        final ReentrantLock gate = new ReentrantLock();
        final ReentrantLock engine = new ReentrantLock();
        volatile boolean ready = true;
        /** The events applied to the "engine", in order. */
        final List<GameEvent> applied = Collections.synchronizedList(new ArrayList<>());
        /** The events that reached the "journal", in order, per transaction. */
        final List<List<GameEvent>> journalBatches = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger recoveries = new AtomicInteger();
        final AtomicInteger transactions = new AtomicInteger();
        volatile RuntimeException commitFailure;
        volatile CountDownLatch commitGate;

        final Db db = mock(Db.class);
        final EventStore store = mock(EventStore.class);

        World() throws Exception {
            Connection connection = mock(Connection.class);
            when(db.tx(any())).thenAnswer(inv -> {
                transactions.incrementAndGet();
                CountDownLatch hold = commitGate;
                if (hold != null) {
                    hold.await();
                }
                RuntimeException failure = commitFailure;
                Db.SqlWork<?> work = inv.getArgument(0);
                if (failure != null) {
                    throw failure;
                }
                return work.run(connection);
            });
            org.mockito.Mockito.doAnswer(inv -> {
                List<EventStore.Pending> events = inv.getArgument(1);
                journalBatches.add(events.stream().map(EventStore.Pending::event).toList());
                return null;
            }).when(store).appendAll(any(), any());
        }

        List<GameEvent> journal() {
            List<GameEvent> all = new ArrayList<>();
            synchronized (journalBatches) {
                journalBatches.forEach(all::addAll);
            }
            return all;
        }

        @Override
        public ReentrantLock gate() {
            return gate;
        }

        @Override
        public void lockEngine() {
            engine.lock();
        }

        @Override
        public void unlockEngine() {
            engine.unlock();
        }

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public Object apply(GameEvent event) {
            applied.add(event);
            return event;
        }

        @Override
        public void recover(Throwable cause, String key, long nowMs) {
            recoveries.incrementAndGet();
            assertThat(engine.isHeldByCurrentThread()).as("a rebuild runs under the engine lock").isTrue();
            applied.clear();
            applied.addAll(journal()); // the rebuilt engine is what the journal has
        }
    }

    private World world;
    private CoreWriter writer;
    private ExecutorService callers;

    @BeforeEach
    void start() throws Exception {
        world = new World();
        writer = new CoreWriter(world, world.db, world.store, 256, 0, 1000);
        callers = Executors.newCachedThreadPool();
    }

    @AfterEach
    void stop() {
        callers.shutdownNow();
        writer.close();
    }

    private static GameEvent event(long n) {
        return new GameEvent.EconomyCutover(n, n);
    }

    private Future<Long> submitting(long n) {
        return submittingCommand(ctx -> {
            ctx.record(event(n), n);
            return n;
        }, false, "t" + n, n);
    }

    /** Submits from a thread of its own and returns once the writer has the command (waiting or in its batch), so that
     *  commands submitted one after the other are in the writer's order. */
    private Future<Long> submittingCommand(Command<Long> command, boolean solo, String key, long nowMs) {
        int before = writer.inFlight();
        Future<Long> future = callers.submit(() -> writer.submit(command, true, solo, key, nowMs));
        await().atMost(Duration.ofSeconds(10)).until(() -> writer.inFlight() > before);
        return future;
    }

    /** Parks the writer in front of its gate and queues {@code n} commands behind it; releasing the gate runs them. */
    private List<Future<Long>> queueBehindTheGate(int n) {
        world.gate.lock();
        List<Future<Long>> futures = new ArrayList<>();
        for (long i = 1; i <= n; i++) {
            futures.add(submitting(i));
        }
        return futures;
    }

    @Test
    void commandsQueuedTogetherAreOneTransactionAndTheJournalIsInTheOrderTheyWereApplied() throws Exception {
        List<Future<Long>> futures = queueBehindTheGate(10);
        world.gate.unlock();

        for (int i = 0; i < futures.size(); i++) {
            assertThat(futures.get(i).get(10, TimeUnit.SECONDS)).isEqualTo(i + 1L);
        }
        assertThat(world.journal()).as("the journal is the order of the applying").containsExactlyElementsOf(world.applied);
        assertThat(world.journal()).hasSize(10);
        assertThat(world.transactions.get()).as("ten commands, two transactions at the most (the first one may have been "
                + "taken alone)").isLessThanOrEqualTo(2);
    }

    @Test
    void aCallerIsAnsweredOnlyWhenItsBatchHasCommitted() throws Exception {
        world.commitGate = new CountDownLatch(1);
        Future<Long> call = submitting(1);

        await().atMost(Duration.ofSeconds(10)).until(() -> world.transactions.get() == 1);
        assertThat(call.isDone()).as("the commit is under way: no answer yet").isFalse();
        assertThat(world.applied).as("the engine has the event already").hasSize(1);
        assertThat(world.engine.isLocked()).as("and the engine lock is not held across the commit").isFalse();

        world.commitGate.countDown();
        assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
    }

    @Test
    void aRefusalIsAnsweredAndWritesNothingWhileTheOthersOfTheBatchAreWritten() throws Exception {
        world.gate.lock();
        Future<Long> good1 = submitting(1);
        Future<Long> refused = submittingCommand(ctx -> {
            ctx.work(c -> null); // staged, then refused: dropped with the command
            throw ApiException.badRequest("no");
        }, false, "refused", 2);
        Future<Long> good2 = submitting(3);
        world.gate.unlock();

        assertThat(good1.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
        assertThat(good2.get(10, TimeUnit.SECONDS)).isEqualTo(3L);
        assertThatThrownBy(() -> refused.get(10, TimeUnit.SECONDS)).hasRootCauseInstanceOf(ApiException.class);
        assertThat(world.journal()).containsExactly(event(1), event(3));
        assertThat(world.recoveries.get()).as("a refusal changes nothing, so nothing is rebuilt").isZero();
    }

    @Test
    void aFailedCommitAnswers503ToEveryCommandOfTheBatchRebuildsAndTheNextBatchWorks() throws Exception {
        List<Future<Long>> futures = new ArrayList<>();
        world.gate.lock();
        world.commitFailure = new IllegalStateException("injected commit failure");
        List<Runnable> rollbacks = new ArrayList<>();
        for (long i = 1; i <= 4; i++) {
            long n = i;
            futures.add(submittingCommand(ctx -> {
                ctx.record(event(n), n);
                ctx.onRollback(() -> rollbacks.add(() -> { }));
                return n;
            }, false, "t" + n, n));
        }
        world.gate.unlock();

        for (Future<Long> f : futures) {
            assertThatThrownBy(() -> f.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class).satisfies(e -> {
                ApiException api = (ApiException) e.getCause();
                assertThat(api.code()).isEqualTo(Enums.ApiErrorCode.ENGINE_NOT_READY);
                assertThat(api.httpStatus()).isEqualTo(503);
                assertThat(api.getCause()).hasMessage("injected commit failure");
            });
        }
        assertThat(world.journal()).as("nothing reached the journal").isEmpty();
        assertThat(world.applied).as("and the rebuilt engine lacks the events").isEmpty();
        assertThat(world.recoveries.get()).isGreaterThanOrEqualTo(1);
        assertThat(rollbacks).as("the commands undo what a rebuild does not").hasSize(4);

        world.commitFailure = null; // the database answers again
        Long answer = writer.submit(ctx -> {
            ctx.record(event(9), 9);
            return 9L;
        }, true, false, "t9", 9);
        assertThat(answer).isEqualTo(9L);
        assertThat(world.journal()).containsExactly(event(9));
    }

    @Test
    void aCommandThatFailsAfterApplyingCommitsTheOnesBeforeItRebuildsAndTheOnesAfterItRunAfterwards() throws Exception {
        world.gate.lock();
        Future<Long> before = submitting(1);
        Future<Long> broken = submittingCommand(ctx -> {
            ctx.record(event(2), 2);
            throw new IllegalStateException("failed after the apply");
        }, false, "broken", 2);
        Future<Long> after = submitting(3);
        world.gate.unlock();

        assertThat(before.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
        assertThatThrownBy(() -> broken.get(10, TimeUnit.SECONDS)).hasRootCauseMessage("failed after the apply");
        assertThat(after.get(10, TimeUnit.SECONDS)).isEqualTo(3L);
        assertThat(world.recoveries.get()).isEqualTo(1);
        assertThat(world.journal()).as("the broken command's event never reached the journal")
                .containsExactly(event(1), event(3));
        assertThat(world.applied).containsExactly(event(1), event(3));
    }

    @Test
    void whileTheEngineIsNotReadyACommandIsRefusedWith503() {
        world.ready = false;
        assertThatThrownBy(() -> writer.submit(ctx -> "x", true, false, "t", 0L)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ENGINE_NOT_READY));
        String answer = writer.submit(ctx -> "x", false, false, "t", 0L);
        assertThat(answer).as("one that decides for itself runs").isEqualTo("x");
    }

    @Test
    void aCommandThatCallsAnotherRunsItInPlaceAndTheyShareTheBatch() throws Exception {
        Long answer = writer.submit(ctx -> {
            ctx.record(event(1), 1);
            writer.submit(inner -> {
                inner.record(event(2), 2);
                return null;
            }, true, false, "inner", 2);
            return 5L;
        }, true, false, "outer", 1);

        assertThat(answer).isEqualTo(5L);
        assertThat(world.journalBatches).as("one transaction").hasSize(1);
        assertThat(world.journal()).containsExactly(event(1), event(2));
    }

    @Test
    void aSoloCommandRunsAloneBetweenTwoBatches() throws Exception {
        world.gate.lock();
        Future<Long> first = submitting(1);
        List<Integer> transactionsWhenSoloRan = Collections.synchronizedList(new ArrayList<>());
        Future<Long> solo = submittingCommand(ctx -> {
            transactionsWhenSoloRan.add(world.transactions.get());
            assertThat(world.engine.isHeldByCurrentThread()).isTrue();
            return 7L;
        }, true, "solo", 2);
        Future<Long> last = submitting(3);
        world.gate.unlock();

        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
        assertThat(solo.get(10, TimeUnit.SECONDS)).isEqualTo(7L);
        assertThat(last.get(10, TimeUnit.SECONDS)).isEqualTo(3L);
        assertThat(transactionsWhenSoloRan).as("the batch before the solo command had committed already").containsExactly(1);
        assertThat(world.journal()).containsExactly(event(1), event(3));
    }
}
