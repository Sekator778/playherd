package crowdvsmarket.server.core;

import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * What a {@link Command} sees while {@link CoreWriter} runs it: the engine is its to change (the writer holds the engine
 * write lock), the journal and the database are not - events are <i>staged</i> and go to the journal with the batch the command is
 * in; there is no way to stage a database write (the rows derived from events are written after the batch, outside the writer).
 *
 * <p>Nothing here may be used after the command has returned.
 */
public final class WriteContext {

    /** What one command has staged. Kept apart per command so that a refused command leaves nothing in the batch. */
    static final class Staged {
        final List<EventStore.Pending> events = new ArrayList<>();
        final List<Runnable> afterCommit = new ArrayList<>();
        final List<Runnable> onRollback = new ArrayList<>();
        boolean touched;
    }

    private final CoreWriter.Host host;
    private Staged staged = new Staged();
    private long queueWaitNanos;
    private volatile long commitNanos;

    WriteContext(CoreWriter.Host host) {
        this.host = host;
    }

    void begin(Staged staged, long queueWaitNanos) {
        this.staged = staged;
        this.queueWaitNanos = queueWaitNanos;
    }

    void commitTook(long nanos) {
        commitNanos = nanos;
    }

    /**
     * Applies {@code event} to the engine now (so the commands after this one see it) and stages it for the journal: it is
     * written, in this order among the batch's events, when the batch commits. From the first call the command counts as
     * having touched the engine: an exception that leaves the command afterwards makes the writer rebuild the engine from
     * the journal, which does not have this event.
     *
     * @return what the applier returns for the event ({@link crowdvsmarket.server.events.EventApplier#apply})
     */
    public Object record(GameEvent event, long atMs) {
        staged.touched = true;
        Object result = host.apply(event);
        staged.events.add(new EventStore.Pending(event, atMs));
        return result;
    }

    /**
     * Stages {@code event} for the journal without applying it to the engine: for an event the engine has nothing to do with (the market
     * registry's {@code MarketListed} / {@code MarketClosed}, which the registry applies to its own memory). Unlike {@link #record} it
     * does not count as having touched the engine, so it is safe to use while the engine cannot be trusted.
     */
    public void stage(GameEvent event, long atMs) {
        staged.events.add(new EventStore.Pending(event, atMs));
    }

    /** Runs {@code action} on the writer thread once the batch has committed, before any caller is answered. */
    public void afterCommit(Runnable action) {
        staged.afterCommit.add(action);
    }

    /** Runs {@code action} on the writer thread if the batch does not commit (before the engine is rebuilt). For memory that
     *  the command has changed and a rebuild does not reset. */
    public void onRollback(Runnable action) {
        staged.onRollback.add(action);
    }

    /** Says that the command has changed the engine's state (or what a rebuild resets) by other means than
     *  {@link #record}: if the command then fails, the engine is rebuilt. */
    public void touch() {
        staged.touched = true;
    }

    /** How long the command waited in the queue, from the call to the start of its run. */
    public long queueWaitNanos() {
        return queueWaitNanos;
    }

    /** How long the commit of the batch took; set when the batch has committed (read it in {@link #afterCommit}). */
    public long commitNanos() {
        return commitNanos;
    }
}
