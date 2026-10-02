package crowdvsmarket.server.testsupport;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * For tests that need a call to be QUEUED behind the engine's lock — the one condition under which a call can meet
 * an engine that has just stopped being ready: it passed its first readiness check, then waited for the lock while
 * another call failed and its recovery failed too ({@code GameService#lockWrite}/{@code #lockRead} check again
 * once they hold the lock). A thread that waits for the lock is parked in {@code lockWrite}/{@code lockRead}; this
 * waits for that, so the test releases the lock holder only when the call under test really is behind it, not after
 * a sleep that may be too short on a busy machine.
 */
public final class EngineLockProbe {

    private EngineLockProbe() {
    }

    /** What a test thread records about itself, for {@link #awaitQueued}. */
    public static final class Probe {
        private final AtomicReference<Thread> thread = new AtomicReference<>();

        /** Call first thing in the task that makes the call under test. */
        public void arrive() {
            thread.set(Thread.currentThread());
        }

        public Thread thread() {
            return thread.get();
        }
    }

    /** Blocks until the thread of {@code probe} is waiting for the engine lock inside {@code GameService}. */
    public static void awaitQueued(Probe probe) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(5)).until(() -> {
            Thread thread = probe.thread();
            return thread != null && thread.getState() == Thread.State.WAITING && Arrays.stream(thread.getStackTrace())
                    .anyMatch(frame -> frame.getClassName().endsWith("GameService")
                            && (frame.getMethodName().equals("lockWrite") || frame.getMethodName().equals("lockRead")));
        });
    }
}
