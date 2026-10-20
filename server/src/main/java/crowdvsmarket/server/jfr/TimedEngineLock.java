package crowdvsmarket.server.jfr;

import crowdvsmarket.server.core.IoGuard;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The engine lock of {@code GameService} with two JFR events (I-1, docs/research/load_bench_design_2026-10-17.md §5):
 * {@link HerdLockWait} (asking for the lock until holding it, with the number of threads queued on arrival) and
 * {@link HerdLockHold} (holding until releasing; JFR attaches the stack of the unlocking caller, which names the
 * {@code GameService} method that held it).
 *
 * <p>Every call goes to the same {@link ReentrantReadWriteLock} as before — non-fair by default, reentrant, with the same
 * downgrade and the same {@code IllegalMonitorStateException} — nothing is added to the order in which threads get the lock.
 * Only the outermost acquisition of a thread is an event (a reentrant one neither waits nor starts a hold). While the events
 * are not being recorded the extra cost is a few reads of the lock's own hold counts and no clock read.
 */
public final class TimedEngineLock implements ReadWriteLock {

    private final ReentrantReadWriteLock delegate;
    private final Lock readLock;
    private final Lock writeLock;

    /** Non-fair, like {@code new ReentrantReadWriteLock()}. */
    public TimedEngineLock() {
        this(false);
    }

    /** {@code fair} as for {@link ReentrantReadWriteLock#ReentrantReadWriteLock(boolean)}; for tests. */
    public TimedEngineLock(boolean fair) {
        delegate = new ReentrantReadWriteLock(fair);
        readLock = new ReadSide();
        writeLock = new WriteSide();
    }

    @Override
    public Lock readLock() {
        return readLock;
    }

    @Override
    public Lock writeLock() {
        return writeLock;
    }

    public boolean isFair() {
        return delegate.isFair();
    }

    /** What a side of the lock does differently from its delegate: the events around acquiring and releasing. */
    private abstract class Side implements Lock {
        private final Lock inner;
        private final String mode;

        Side(Lock inner, String mode) {
            this.inner = inner;
            this.mode = mode;
        }

        /** The calling thread already holds the lock in a way that makes this acquisition reentrant. */
        abstract boolean reentrant();

        /** The call is the release of the outermost hold of the calling thread. */
        abstract boolean outermostRelease();

        abstract void storeHold(HerdLockHold hold);

        /** The calling thread has just taken the lock, not reentrantly. */
        void acquired() {
        }

        /** The calling thread is about to release the outermost hold of the lock. */
        void releasing() {
        }

        /** The hold event started by the outermost acquisition of the calling thread (cleared), or null. */
        abstract HerdLockHold takeHold();

        @Override
        public void lock() {
            if (reentrant()) {
                inner.lock();
                return;
            }
            HerdLockWait wait = new HerdLockWait();
            if (!wait.isEnabled()) {
                inner.lock();
                startHold();
                return;
            }
            int queued = delegate.getQueueLength();
            wait.begin();
            inner.lock();
            commitWait(wait, queued);
            startHold();
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            if (reentrant()) {
                inner.lockInterruptibly();
                return;
            }
            HerdLockWait wait = new HerdLockWait();
            boolean recording = wait.isEnabled();
            int queued = recording ? delegate.getQueueLength() : 0;
            if (recording) {
                wait.begin();
            }
            inner.lockInterruptibly();
            if (recording) {
                commitWait(wait, queued);
            }
            startHold();
        }

        @Override
        public boolean tryLock() {
            boolean reentrant = reentrant();
            if (!inner.tryLock()) {
                return false;
            }
            if (!reentrant) {
                startHold();
            }
            return true;
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            boolean reentrant = reentrant();
            HerdLockWait wait = new HerdLockWait();
            boolean recording = !reentrant && wait.isEnabled();
            int queued = recording ? delegate.getQueueLength() : 0;
            if (recording) {
                wait.begin();
            }
            if (!inner.tryLock(time, unit)) {
                return false;
            }
            if (!reentrant) {
                if (recording) {
                    commitWait(wait, queued);
                }
                startHold();
            }
            return true;
        }

        @Override
        public void unlock() {
            HerdLockHold hold = null;
            if (outermostRelease()) {
                releasing();
                hold = takeHold();
                if (hold != null) {
                    hold.end();
                }
            }
            inner.unlock();
            if (hold != null) {
                hold.commit(); // after the release: the event costs the next thread nothing
            }
        }

        @Override
        public Condition newCondition() {
            return inner.newCondition();
        }

        private void commitWait(HerdLockWait wait, int queued) {
            wait.end();
            if (wait.shouldCommit()) {
                wait.mode = mode;
                wait.queueLength = queued;
                wait.commit();
            }
        }

        private void startHold() {
            acquired();
            HerdLockHold hold = new HerdLockHold();
            if (hold.isEnabled()) {
                hold.mode = mode;
                hold.begin();
                storeHold(hold);
            }
        }
    }

    private final class WriteSide extends Side {
        /** Of the thread that holds the write lock; read and written only by it. */
        private HerdLockHold hold;

        WriteSide() {
            super(delegate.writeLock(), "write");
        }

        @Override
        void acquired() {
            IoGuard.writeLockAcquired();
        }

        @Override
        void releasing() {
            IoGuard.writeLockReleasing();
        }

        @Override
        boolean reentrant() {
            return delegate.isWriteLockedByCurrentThread();
        }

        @Override
        boolean outermostRelease() {
            return delegate.getWriteHoldCount() == 1;
        }

        @Override
        void storeHold(HerdLockHold hold) {
            this.hold = hold;
        }

        @Override
        HerdLockHold takeHold() {
            HerdLockHold taken = hold;
            hold = null;
            return taken;
        }
    }

    private final class ReadSide extends Side {
        private final ThreadLocal<HerdLockHold> hold = new ThreadLocal<>();

        ReadSide() {
            super(delegate.readLock(), "read");
        }

        @Override
        boolean reentrant() {
            return delegate.getReadHoldCount() > 0 || delegate.isWriteLockedByCurrentThread();
        }

        @Override
        boolean outermostRelease() {
            return delegate.getReadHoldCount() == 1 && !delegate.isWriteLockedByCurrentThread();
        }

        @Override
        void storeHold(HerdLockHold hold) {
            this.hold.set(hold);
        }

        @Override
        HerdLockHold takeHold() {
            HerdLockHold taken = hold.get();
            if (taken != null) {
                hold.remove();
            }
            return taken;
        }
    }
}
