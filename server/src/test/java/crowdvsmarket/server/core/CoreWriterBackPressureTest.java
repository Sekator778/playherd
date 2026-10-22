package crowdvsmarket.server.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.GameEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Security review H3: a full queue answers 503 within a short deadline instead of parking the caller for good. */
class CoreWriterBackPressureTest {

    private CoreWriterTest.World world;
    private CoreWriter writer;
    private final ExecutorService callers = Executors.newCachedThreadPool();

    @AfterEach
    void stop() {
        while (world.gate.isHeldByCurrentThread()) {
            world.gate.unlock();
        }
        callers.shutdownNow();
        writer.close();
    }

    @Test
    void aSubmitToAFullQueueIsAnswered503WithinTheDeadlineAndTheOnesQueuedStillRun() throws Exception {
        world = new CoreWriterTest.World();
        writer = new CoreWriter(world, world.store, 256, 0, 2, CoreWriter.DEFAULT_PIPELINE_DEPTH, 100);
        // the logic thread is held inside a command: nothing leaves the queue behind it
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        futures.add(callers.submit(() -> writer.submit(ctx -> {
            running.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 0L;
        }, true, "hold", 0)));
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
        for (long n = 1; n <= 5; n++) {
            long id = n;
            futures.add(callers.submit(() -> writer.submit(ctx -> {
                ctx.record(new GameEvent.EconomyCutover(id, id), id);
                return id;
            }, true, "t" + id, id)));
        }
        // the queue holds 2: 3 of the 5 behind the held command are refused, and quickly
        await().atMost(Duration.ofSeconds(5)).until(() -> futures.stream().filter(Future::isDone).count() >= 3);
        for (Future<Long> f : futures) {
            if (f.isDone()) {
                Throwable cause = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class, f::get).getCause();
                assertThat(cause).isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ENGINE_NOT_READY);
                    assertThat(e.httpStatus()).isEqualTo(503);
                    assertThat(e.getMessage()).contains("busy");
                });
            }
        }
        assertThat(writer.inFlight()).as("a refused submit is not counted as in flight").isEqualTo(3);

        release.countDown();
        long served = 0;
        for (Future<Long> f : futures) {
            try {
                f.get(10, java.util.concurrent.TimeUnit.SECONDS);
                served++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(ApiException.class);
            }
        }
        assertThat(served).isEqualTo(3L);
        assertThat(world.journal()).hasSize(2);
    }
}
