package crowdvsmarket.server.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** One command on its way through {@link CoreWriter}: what the caller asked, what the command staged, and the future the caller waits on. */
final class WriterSlot<T> {

    /** Put in the command queue by a stage that needs the logic thread to look at the pipeline (a failed sync); never run as a command. */
    static final WriterSlot<Object> WAKE = new WriterSlot<>(ctx -> null, false, "wake", 0, new AtomicInteger());

    final Command<T> command;
    final boolean needsReady;
    final String key;
    final long nowMs;
    final long submittedNanos = System.nanoTime();
    final CompletableFuture<T> future = new CompletableFuture<>();
    final WriteContext.Staged staged = new WriteContext.Staged();
    T result;
    private final AtomicInteger inFlight;

    WriterSlot(Command<T> command, boolean needsReady, String key, long nowMs, AtomicInteger inFlight) {
        this.command = command;
        this.needsReady = needsReady;
        this.key = key == null ? "command" : key;
        this.nowMs = nowMs;
        this.inFlight = inFlight;
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
