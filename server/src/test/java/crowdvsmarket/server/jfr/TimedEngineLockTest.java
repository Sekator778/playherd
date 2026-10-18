package crowdvsmarket.server.jfr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

/**
 * I-1: the timed engine lock behaves like the {@link ReentrantReadWriteLock} it wraps (reentrancy, mutual exclusion,
 * downgrade, the exception of an unlock without ownership, non-fair unless asked) and, when JFR records, says
 * {@code herd.LockWait} / {@code herd.LockHold} once per outermost acquisition.
 */
class TimedEngineLockTest {

    @Test
    void isNonFairLikeTheLockItReplaces() {
        assertThat(new TimedEngineLock().isFair()).isFalse();
        assertThat(new TimedEngineLock(true).isFair()).isTrue();
    }

    @Test
    void writeLockIsReentrantAndReadCanBeTakenUnderIt() {
        TimedEngineLock lock = new TimedEngineLock();
        lock.writeLock().lock();
        lock.writeLock().lock();
        lock.readLock().lock(); // the downgrade path of the delegate
        lock.writeLock().unlock();
        lock.writeLock().unlock();
        lock.readLock().unlock();
        assertThat(lock.writeLock().tryLock()).isTrue(); // everything was released
        lock.writeLock().unlock();
    }

    @Test
    void readLockIsReentrantAndCannotBeUpgraded() {
        TimedEngineLock lock = new TimedEngineLock();
        lock.readLock().lock();
        lock.readLock().lock();
        assertThat(lock.writeLock().tryLock()).as("a read lock cannot become a write lock (as for the delegate)").isFalse();
        lock.readLock().unlock();
        lock.readLock().unlock();
        assertThat(lock.writeLock().tryLock()).isTrue();
        lock.writeLock().unlock();
    }

    @Test
    void unlockWithoutOwnershipThrowsLikeTheDelegate() {
        TimedEngineLock lock = new TimedEngineLock();
        assertThatThrownBy(() -> lock.writeLock().unlock()).isInstanceOf(IllegalMonitorStateException.class);
        assertThatThrownBy(() -> lock.readLock().unlock()).isInstanceOf(IllegalMonitorStateException.class);
        lock.readLock().lock();
        assertThatThrownBy(() -> lock.writeLock().unlock()).isInstanceOf(IllegalMonitorStateException.class);
        lock.readLock().unlock();
        assertThatThrownBy(() -> lock.readLock().unlock()).isInstanceOf(IllegalMonitorStateException.class);
    }

    @Test
    void writeExcludesEveryoneElseAndReadersShare() throws Exception {
        TimedEngineLock lock = new TimedEngineLock();
        lock.writeLock().lock();
        assertThat(inOtherThread(() -> lock.readLock().tryLock())).isFalse();
        assertThat(inOtherThread(() -> lock.writeLock().tryLock())).isFalse();
        lock.writeLock().unlock();
        lock.readLock().lock();
        assertThat(inOtherThread(() -> {
            boolean got = lock.readLock().tryLock();
            if (got) {
                lock.readLock().unlock();
            }
            return got;
        })).isTrue();
        assertThat(inOtherThread(() -> lock.writeLock().tryLock())).isFalse();
        lock.readLock().unlock();
    }

    @Test
    void aQueuedWriterGatesNewReadersExactlyAsTheDelegateDoes() throws Exception {
        // the non-fair delegate makes a new reader (lock(), not the barging tryLock()) wait while a writer heads
        // the queue; the wrapper must say the same
        ReentrantReadWriteLock plain = new ReentrantReadWriteLock();
        boolean expected = newReaderIsGatedByAQueuedWriter(plain.readLock(), plain.writeLock());
        TimedEngineLock timed = new TimedEngineLock();
        assertThat(expected).isTrue();
        assertThat(newReaderIsGatedByAQueuedWriter(timed.readLock(), timed.writeLock())).isEqualTo(expected);
    }

    private static boolean newReaderIsGatedByAQueuedWriter(Lock readSide, Lock writeSide) throws Exception {
        readSide.lock(); // this thread holds a read lock
        Thread writer = new Thread(() -> {
            writeSide.lock();
            writeSide.unlock();
        });
        writer.setDaemon(true);
        writer.start();
        awaitWaiting(writer);
        Thread reader = new Thread(() -> {
            readSide.lock();
            readSide.unlock();
        });
        reader.setDaemon(true);
        reader.start();
        reader.join(300);
        boolean gated = reader.getState() == Thread.State.WAITING;
        readSide.unlock();
        writer.join(5_000);
        reader.join(5_000);
        return gated;
    }

    private static void awaitWaiting(Thread t) throws InterruptedException {
        while (t.getState() != Thread.State.WAITING) {
            Thread.sleep(5);
        }
    }

    @Test
    void recordsOneWaitAndOneHoldPerOutermostAcquisition() throws Exception {
        TimedEngineLock lock = new TimedEngineLock();
        List<RecordedEvent> events = record(() -> {
            lock.writeLock().lock();
            lock.writeLock().lock(); // reentrant: no event
            lock.readLock().lock();  // under the own write lock: no event
            lock.readLock().unlock();
            lock.writeLock().unlock();
            lock.writeLock().unlock();
            lock.readLock().lock();
            lock.readLock().lock(); // reentrant
            lock.readLock().unlock();
            lock.readLock().unlock();
        });
        assertThat(named(events, "herd.LockWait")).extracting(e -> e.getString("mode")).containsExactly("write", "read");
        assertThat(named(events, "herd.LockHold")).extracting(e -> e.getString("mode")).containsExactly("write", "read");
    }

    @Test
    void waitEventSaysHowManyThreadsWereQueuedOnArrival() throws Exception {
        TimedEngineLock lock = new TimedEngineLock();
        List<RecordedEvent> events = record(() -> {
            lock.writeLock().lock();
            Thread first = queued(lock);
            Thread second = queued(lock);
            lock.writeLock().unlock();
            try {
                first.join(10_000);
                second.join(10_000);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        List<RecordedEvent> waits = named(events, "herd.LockWait");
        assertThat(waits).hasSize(3);
        assertThat(waits).extracting(e -> e.getInt("queueLength")).containsExactlyInAnyOrder(0, 0, 1);
        assertThat(named(events, "herd.LockHold")).hasSize(3);
    }

    @Test
    void failedTryLockLeavesNoEventAndNoHold() throws Exception {
        TimedEngineLock lock = new TimedEngineLock();
        lock.writeLock().lock();
        List<RecordedEvent> events = record(() -> {
            try {
                assertThat(inOtherThread(() -> lock.writeLock().tryLock(10, TimeUnit.MILLISECONDS))).isFalse();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        lock.writeLock().unlock();
        assertThat(named(events, "herd.LockHold")).isEmpty();
    }

    @Test
    void eventsAreDisabledWithoutARecording() {
        assertThat(new HerdLockWait().isEnabled()).isFalse();
        assertThat(new HerdLockHold().isEnabled()).isFalse();
    }

    private static Thread queued(TimedEngineLock lock) {
        CountDownLatch started = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            started.countDown();
            lock.writeLock().lock();
            lock.writeLock().unlock();
        });
        t.setDaemon(true);
        t.start();
        try {
            started.await();
            while (t.getState() != Thread.State.WAITING) {
                Thread.sleep(5);
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return t;
    }

    private interface Task<T> {
        T call() throws Exception;
    }

    private static <T> T inOtherThread(Task<T> task) throws Exception {
        Object[] box = new Object[2];
        Thread t = new Thread(() -> {
            try {
                box[0] = task.call();
            } catch (Exception e) {
                box[1] = e;
            }
        });
        t.start();
        t.join(10_000);
        if (box[1] != null) {
            throw (Exception) box[1];
        }
        @SuppressWarnings("unchecked")
        T result = (T) box[0];
        return result;
    }

    private static List<RecordedEvent> named(List<RecordedEvent> events, String name) {
        return events.stream().filter(e -> e.getEventType().getName().equals(name)).toList();
    }

    static List<RecordedEvent> record(Runnable body) throws Exception {
        Path file = Files.createTempFile("herd-timed-lock", ".jfr");
        try (Recording recording = new Recording()) {
            recording.enable("herd.LockWait").withThreshold(java.time.Duration.ZERO);
            recording.enable("herd.LockHold").withThreshold(java.time.Duration.ZERO);
            recording.start();
            body.run();
            recording.stop();
            recording.dump(file);
        }
        try {
            return RecordingFile.readAllEvents(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
