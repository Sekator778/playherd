package crowdvsmarket.server.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import crowdvsmarket.server.core.CoreWriterTest.World;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.GameEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The stages of the core writer (logic thread, journal stage, output stage) with a fake engine and a journal whose sync the test holds:
 * the logic thread goes on while a sync is under way, and nothing is answered, published or pushed before the sync; a failed sync takes
 * every batch applied behind it; the gate waits for the pipeline; the logic thread waits only for room.
 */
class CoreWriterPipelineTest {

    private World world;
    private CoreWriter writer;
    private ExecutorService callers;

    @BeforeEach
    void start() throws Exception {
        world = new World();
        writer = new CoreWriter(world, world.store, 256, 0, 1000);
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

    /** Submits from a thread of its own and returns once the writer has the command. */
    private Future<Long> submitting(CoreWriter w, Command<Long> command, String key, long nowMs) {
        int before = w.inFlight();
        Future<Long> future = callers.submit(() -> w.submit(command, true, key, nowMs));
        await().atMost(Duration.ofSeconds(10)).until(() -> w.inFlight() > before);
        return future;
    }

    private Future<Long> submitting(long n, List<Long> pushed, List<Long> rolledBack) {
        return submitting(writer, ctx -> {
            ctx.record(event(n), n);
            ctx.afterCommit(() -> pushed.add(n));
            ctx.onRollback(() -> rolledBack.add(n));
            return n;
        }, "t" + n, n);
    }

    @Test
    void withTheSyncBlockedTheLogicThreadGoesOnButNothingIsAnsweredPublishedOrPushed() throws Exception {
        world.commitGate = new CountDownLatch(1);
        List<Long> pushed = Collections.synchronizedList(new ArrayList<>());
        List<Future<Long>> futures = new ArrayList<>();
        for (long n = 1; n <= 5; n++) {
            futures.add(submitting(n, pushed, new ArrayList<>()));
        }

        await().atMost(Duration.ofSeconds(10)).until(() -> world.applied.size() == 5);
        assertThat(world.transactions.get()).as("the first sync is under way and blocked").isEqualTo(1);
        assertThat(world.engine.isLocked()).as("the engine lock is free").isFalse();
        assertThat(world.prepared).as("the views of the batches are built").isNotEmpty();
        assertThat(futures).as("no caller is answered").noneMatch(Future::isDone);
        assertThat(world.published).as("no view is published").isEmpty();
        assertThat(pushed).as("no push notice goes out").isEmpty();
        assertThat(world.journal()).as("nothing is in the journal").isEmpty();
        assertThat(writer.pipelineDepthNow()).as("batches applied and not durable").isGreaterThanOrEqualTo(2);

        world.commitGate.countDown();
        for (int i = 0; i < futures.size(); i++) {
            assertThat(futures.get(i).get(10, TimeUnit.SECONDS)).isEqualTo(i + 1L);
        }
        assertThat(pushed).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(world.journal()).containsExactlyElementsOf(world.applied);
        assertThat(world.journalBatches).as("the first batch had the sync to itself, the ones that waited shared the next").hasSize(2);
        List<GameEvent> published = new ArrayList<>();
        world.published.forEach(p -> published.addAll(p.events()));
        assertThat(published).containsExactlyElementsOf(world.journal());
        assertThat(writer.stats().maxSyncGroup()).isGreaterThanOrEqualTo(2);
        assertThat(writer.stats().syncs()).isEqualTo(2);
    }

    @Test
    void aFailedSyncWithSeveralBatchesInFlightAnswers503ToAllOfThemRebuildsFromTheJournalAndTheNextCommandWorks() throws Exception {
        world.commitGate = new CountDownLatch(1);
        world.commitFailure = new IllegalStateException("injected sync failure");
        List<Long> pushed = Collections.synchronizedList(new ArrayList<>());
        List<Long> rolledBack = Collections.synchronizedList(new ArrayList<>());
        List<Future<Long>> futures = new ArrayList<>();
        for (long n = 1; n <= 5; n++) {
            futures.add(submitting(n, pushed, rolledBack));
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> world.applied.size() == 5);
        assertThat(world.transactions.get()).isEqualTo(1);

        world.commitGate.countDown(); // the sync in flight fails; the batches applied behind it are dropped with it
        for (Future<Long> f : futures) {
            assertThatThrownBy(() -> f.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class).satisfies(e -> {
                ApiException api = (ApiException) e.getCause();
                assertThat(api.code()).isEqualTo(Enums.ApiErrorCode.ENGINE_NOT_READY);
                assertThat(api.httpStatus()).isEqualTo(503);
                assertThat(api.getCause()).hasMessage("injected sync failure");
            });
        }
        assertThat(world.journal()).as("nothing reached the journal - not even the batches behind the failed one").isEmpty();
        assertThat(world.transactions.get()).as("the batches behind the failed one were never written").isEqualTo(1);
        assertThat(world.applied).as("the rebuilt engine lacks the speculative events").isEmpty();
        assertThat(world.recoveries.get()).isEqualTo(1);
        assertThat(rolledBack).as("every command undoes what a rebuild does not").containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        assertThat(pushed).as("no push notice for what was not durable").isEmpty();
        assertThat(world.published).as("no view for what was not durable").isEmpty();

        world.commitFailure = null;
        world.commitGate = null;
        Long answer = writer.submit(ctx -> {
            ctx.record(event(9), 9);
            return 9L;
        }, true, "t9", 9);
        assertThat(answer).isEqualTo(9L);
        assertThat(world.journal()).containsExactly(event(9));
        assertThat(world.applied).containsExactly(event(9));
        assertThat(world.published).hasSize(1);
        assertThat(writer.pipelineDepthNow()).isZero();
    }

    @Test
    void aFailedSyncWhileTheLogicThreadIsIdleStillAnswersTheCaller() throws Exception {
        world.commitFailure = new IllegalStateException("injected sync failure");
        Future<Long> call = callers.submit(() -> writer.submit(ctx -> {
            ctx.record(event(1), 1);
            return 1L;
        }, true, "t1", 1));

        assertThatThrownBy(() -> call.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
        assertThat(world.recoveries.get()).isEqualTo(1);
    }

    @Test
    void sixtyFourClientsAreAnsweredOnlyForWhatIsInTheJournalAndTheJournalIsInTheOrderOfTheApplying() throws Exception {
        int clients = 64;
        int each = 40;
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        List<Future<Integer>> results = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        for (int c = 0; c < clients; c++) {
            int me = c;
            results.add(pool.submit(() -> {
                go.await();
                int notDurable = 0;
                for (int k = 0; k < each; k++) {
                    GameEvent mine = event(me * 1000L + k);
                    writer.submit(ctx -> {
                        ctx.record(mine, 0);
                        return null;
                    }, true, "c" + me, 0);
                    if (!world.journaled.contains(mine)) {
                        notDurable++; // answered before its event was synced
                    }
                }
                return notDurable;
            }));
        }
        go.countDown();
        for (Future<Integer> r : results) {
            assertThat(r.get(60, TimeUnit.SECONDS)).as("answers given before the event was in the journal").isZero();
        }
        pool.shutdownNow();

        assertThat(world.journal()).hasSize(clients * each).containsExactlyElementsOf(world.applied);
        long[] last = new long[clients];
        Arrays.fill(last, -1);
        for (GameEvent e : world.journal()) {
            long n = ((GameEvent.EconomyCutover) e).atMs();
            int client = (int) (n / 1000);
            assertThat(n % 1000).as("the events of one client are in the order it sent them").isGreaterThan(last[client]);
            last[client] = n % 1000;
        }
        List<GameEvent> published = new ArrayList<>();
        long lastVersion = 0;
        for (World.Published p : world.published) {
            published.addAll(p.events());
            assertThat(p.version()).isEqualTo(published.size());
            assertThat(p.version()).isGreaterThan(lastVersion);
            lastVersion = p.version();
        }
        assertThat(published).as("the views follow the journal, batch by batch").containsExactlyElementsOf(world.journal());
        assertThat(writer.pipelineDepthNow()).isZero();
    }

    @Test
    void whoEntersTheGateWaitsUntilTheEngineIsWhatTheJournalHolds() throws Exception {
        world.commitGate = new CountDownLatch(1);
        Future<Long> call = submitting(1, new ArrayList<>(), new ArrayList<>());
        await().atMost(Duration.ofSeconds(10)).until(() -> world.transactions.get() == 1);
        assertThat(world.applied).as("applied, not durable: the engine is ahead of the journal").hasSize(1);

        Future<List<Integer>> entered = callers.submit(() -> {
            writer.enterGate();
            try {
                return List.of(world.applied.size(), world.journal().size(), world.published.size());
            } finally {
                world.gate.unlock();
            }
        });
        Thread.sleep(300);
        assertThat(entered.isDone()).as("a snapshot, a rebuild, a ledger flush wait for the sync").isFalse();

        world.commitGate.countDown();
        assertThat(entered.get(10, TimeUnit.SECONDS)).as("engine, journal and views agree when the gate is entered")
                .containsExactly(1, 1, 1);
        call.get(10, TimeUnit.SECONDS);
    }

    @Test
    void whoEntersTheGateAfterAFailedSyncWaitsForTheRebuild() throws Exception {
        world.commitGate = new CountDownLatch(1);
        world.commitFailure = new IllegalStateException("injected sync failure");
        Future<Long> call = submitting(1, new ArrayList<>(), new ArrayList<>());
        await().atMost(Duration.ofSeconds(10)).until(() -> world.transactions.get() == 1);

        Future<List<Integer>> entered = callers.submit(() -> {
            writer.enterGate();
            try {
                return List.of(world.applied.size(), world.journal().size(), world.recoveries.get());
            } finally {
                world.gate.unlock();
            }
        });
        Thread.sleep(200);
        world.commitGate.countDown();

        assertThat(entered.get(10, TimeUnit.SECONDS)).as("the speculative event is never seen: the engine is rebuilt first")
                .containsExactly(0, 0, 1);
        assertThatThrownBy(() -> call.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
    }

    @Test
    void whoHoldsTheGateStopsTheLogicThreadStartingBatches() throws Exception {
        writer.enterGate();
        Future<Long> call;
        try {
            call = callers.submit(() -> writer.submit(ctx -> {
                ctx.record(event(1), 1);
                return 1L;
            }, true, "t1", 1));
            Thread.sleep(300);
            assertThat(world.applied).as("not applied while the gate is held").isEmpty();
        } finally {
            world.gate.unlock();
        }
        assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
    }

    @Test
    void theLogicThreadWaitsForRoomOnlyWhenThePipelineIsFull() throws Exception {
        World small = new World();
        CoreWriter shallow = new CoreWriter(small, small.store, 256, 0, 1000, 2);
        try {
            small.commitGate = new CountDownLatch(1);
            List<Future<Long>> futures = new ArrayList<>();
            for (long n = 1; n <= 3; n++) {
                long k = n;
                futures.add(submitting(shallow, ctx -> {
                    ctx.record(event(k), k);
                    return k;
                }, "t" + k, k));
                await().atMost(Duration.ofSeconds(10)).until(() -> small.applied.size() == k);
            }
            // two batches fill the pipeline; the third is applied and waits for room
            assertThat(shallow.pipelineDepthNow()).isEqualTo(2);
            Future<Long> fourth = submitting(shallow, ctx -> {
                ctx.record(event(4), 4);
                return 4L;
            }, "t4", 4);
            Thread.sleep(300);
            assertThat(small.applied).as("the fourth is not started while the logic thread waits for room").hasSize(3);
            assertThat(fourth.isDone()).isFalse();

            small.commitGate.countDown();
            for (Future<Long> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
            assertThat(fourth.get(10, TimeUnit.SECONDS)).isEqualTo(4L);
            assertThat(small.journal()).containsExactlyElementsOf(small.applied);
            assertThat(shallow.stats().backPressureWaits()).isGreaterThanOrEqualTo(1);
            assertThat(shallow.stats().backPressureNanos()).isPositive();
        } finally {
            shallow.close();
        }
    }
}
