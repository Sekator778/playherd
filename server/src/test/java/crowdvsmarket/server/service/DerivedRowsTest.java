package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The queue of the rows that are written after a batch, on a thread of its own: order, retries, the drop, the wait, the status. */
class DerivedRowsTest {

    @Test
    void jobsRunInTheOrderTheyWereQueuedOnAThreadOfTheirOwn() {
        DerivedRows rows = new DerivedRows();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        List<String> threads = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 20; i++) {
            int n = i;
            rows.submit("job " + n, () -> {
                order.add("job " + n);
                threads.add(Thread.currentThread().getName());
            });
        }

        assertThat(rows.awaitIdle(10_000)).isTrue();

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            expected.add("job " + i);
        }
        assertThat(order).containsExactlyElementsOf(expected);
        assertThat(threads).containsOnly("herd-derived-rows");
        DerivedRows.Status status = rows.status();
        assertThat(status.pending()).isZero();
        assertThat(status.written()).isEqualTo(20);
        assertThat(status.dropped()).isZero();
        assertThat(status.oldestPendingMs()).isZero();
        rows.onStop(null);
    }

    @Test
    void aJobThatFailsIsTriedAgainAndThenDroppedAndCounted() {
        DerivedRows rows = new DerivedRows();
        AtomicInteger tries = new AtomicInteger();
        AtomicInteger good = new AtomicInteger();
        rows.submit("flaky", () -> {
            if (tries.incrementAndGet() < 3) {
                throw new IllegalStateException("db down " + tries.get());
            }
        });
        rows.submit("hopeless", () -> {
            throw new IllegalStateException("db gone");
        });
        rows.submit("behind", good::incrementAndGet);

        assertThat(rows.awaitIdle(30_000)).isTrue();

        assertThat(tries.get()).as("the third try wrote it").isEqualTo(3);
        assertThat(good.get()).as("the job behind a dropped one still runs").isEqualTo(1);
        DerivedRows.Status status = rows.status();
        assertThat(status.written()).isEqualTo(2);
        assertThat(status.dropped()).isEqualTo(1);
        assertThat(status.failedAttempts()).isEqualTo(2 + DerivedRows.ATTEMPTS);
        assertThat(status.lastError()).contains("db gone");
        rows.onStop(null);
    }

    @Test
    void waitingForTheQueueReturnsFalseWhileAJobIsStuckAndShowsTheLag() throws Exception {
        DerivedRows rows = new DerivedRows();
        CountDownLatch release = new CountDownLatch(1);
        rows.submit("stuck", () -> release.await());
        rows.submit("waiting", () -> {
        });

        assertThat(rows.awaitIdle(100)).isFalse();
        Thread.sleep(50);
        DerivedRows.Status status = rows.status();
        assertThat(status.pending()).isEqualTo(2);
        assertThat(status.oldestPendingMs()).as("the job that waits behind the stuck one").isGreaterThan(0);

        release.countDown();
        assertThat(rows.awaitIdle(10_000)).isTrue();
        assertThat(rows.status().pending()).isZero();
        rows.onStop(null);
        assertThat(TimeUnit.MILLISECONDS.toSeconds(rows.status().lastWrittenAtMs())).isPositive();
    }
}
