package crowdvsmarket.server.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.server.jfr.TimedEngineLock;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The runtime guard of the rule "no database and no network under the engine lock or in a writer turn" on its own. */
class IoGuardTest {

    private IoGuard.Mode before;

    @BeforeEach
    void on() {
        before = IoGuard.mode();
        IoGuard.setMode(IoGuard.Mode.FAIL);
        IoGuard.takeViolations();
    }

    @AfterEach
    void restore() {
        IoGuard.takeViolations(); // this test's violations are not the suite's
        IoGuard.setMode(before);
    }

    @Test
    void aThreadWithoutTheLockMayDoIo() {
        assertThatCode(() -> IoGuard.check("database", "test")).doesNotThrowAnyException();
    }

    @Test
    void theWriteLockForbidsIoUntilItIsReleasedReentrantHoldsIncluded() {
        TimedEngineLock lock = new TimedEngineLock();
        lock.writeLock().lock();
        try {
            lock.writeLock().lock();
            lock.writeLock().unlock(); // inner release: still held
            assertThat(IoGuard.holdsEngineWriteLock()).isTrue();
            assertThatThrownBy(() -> IoGuard.check("database", "Db.tx")).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("engine I/O rule").hasMessageContaining("holds the engine write lock");
            assertThatThrownBy(() -> IoGuard.check("network", "GET host")).isInstanceOf(IllegalStateException.class);
        } finally {
            lock.writeLock().unlock();
        }
        assertThat(IoGuard.holdsEngineWriteLock()).isFalse();
        assertThatCode(() -> IoGuard.check("database", "test")).doesNotThrowAnyException();
        assertThat(IoGuard.takeViolations()).hasSize(2);
    }

    @Test
    void theReadLockAndATryLockAreCountedAsTheyAre() {
        TimedEngineLock lock = new TimedEngineLock();
        lock.readLock().lock();
        try {
            assertThat(IoGuard.holdsEngineWriteLock()).isFalse();
            assertThatCode(() -> IoGuard.check("database", "test")).doesNotThrowAnyException();
        } finally {
            lock.readLock().unlock();
        }
        assertThat(lock.writeLock().tryLock()).isTrue();
        try {
            assertThat(IoGuard.holdsEngineWriteLock()).isTrue();
        } finally {
            lock.writeLock().unlock();
        }
        assertThat(IoGuard.holdsEngineWriteLock()).isFalse();
    }

    @Test
    void theWriterThreadMayNotDoIoOutsideTheBatchCommit() throws Exception {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                IoGuard.check("database", "Db.read");
            } catch (Throwable e) {
                thrown.set(e);
            }
        }, IoGuard.WRITER_THREAD);
        writer.start();
        writer.join();
        assertThat(thrown.get()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("core writer's thread outside the batch commit");
    }

    @Test
    void anotherThreadIsNotAffectedByTheLockOfThisOne() throws Exception {
        TimedEngineLock lock = new TimedEngineLock();
        lock.writeLock().lock();
        try {
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    IoGuard.check("database", "Db.read");
                } catch (Throwable e) {
                    thrown.set(e);
                }
            });
            other.start();
            other.join();
            assertThat(thrown.get()).isNull();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Test
    void recordModeCountsAndDoesNotThrow() {
        IoGuard.setMode(IoGuard.Mode.RECORD);
        TimedEngineLock lock = new TimedEngineLock();
        lock.writeLock().lock();
        try {
            assertThatCode(() -> IoGuard.check("database", "Db.tx")).doesNotThrowAnyException();
        } finally {
            lock.writeLock().unlock();
        }
        assertThat(IoGuard.takeViolations()).hasSize(1);
    }

    @Test
    void offModeIsSilentAndKeepsNoState() {
        IoGuard.setMode(IoGuard.Mode.OFF);
        TimedEngineLock lock = new TimedEngineLock();
        lock.writeLock().lock();
        try {
            assertThat(IoGuard.holdsEngineWriteLock()).isFalse();
            assertThatCode(() -> IoGuard.check("database", "Db.tx")).doesNotThrowAnyException();
        } finally {
            lock.writeLock().unlock();
        }
        assertThat(IoGuard.takeViolations()).isEmpty();
    }

    @Test
    void everyAllowedEntryNamesItsReasonAndThePlanStepThatRemovesIt() {
        assertThat(IoGuard.ALLOWED).isNotEmpty().allSatisfy((frame, reason) -> {
            assertThat(frame).matches("[A-Za-z]+\\.[A-Za-z]+");
            assertThat(reason).contains("TODO plan");
        });
    }
}
