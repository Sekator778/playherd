package crowdvsmarket.server.core;

import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.jfr.HerdWriterBatch;
import java.util.List;

/**
 * A batch of {@link CoreWriter} on its way from the logic thread through the journal stage to the output stage. The logic thread fills it
 * before the hand-over; after that each field has one writer (the stage that owns it) and is read by the next stage through the queue
 * that carried the batch.
 */
final class WriterBatch {

    /** The commands of the batch that ran and were accepted, in order. */
    final List<WriterSlot<?>> accepted;
    /** Their events, in the order of the journal. */
    final List<EventStore.Pending> events;
    /** What {@code Host#prepareViews} built after the last command of the batch; {@code null}: no views to publish. */
    final Object views;
    /** A command that applied something and then threw: the engine holds a part of it, so the logic thread rebuilds it once the batch (the
     *  commands before the failing one) is durable and answers the callers itself. {@code null} for a batch that ends cleanly. */
    final WriterSlot<?> tainted;
    final Throwable taint;
    final HerdWriterBatch jfr;

    /** The journal stage could not write the batch, or an earlier one: nothing of it is durable, and the logic thread answers 503. */
    volatile boolean poisoned;
    volatile Throwable failure;
    /** The output stage is through with the batch. */
    volatile boolean done;
    /** The last seq of the journal that includes the batch's events, negative when it wrote none. */
    long version = -1;
    /** The sync that made the batch durable: its length, and how many batches shared it. */
    long syncNanos;
    int syncGroup;
    /** When the logic thread handed the batch over, and when the journal stage was through with it. */
    long handedAtNanos;
    long journaledAtNanos;
    /** How long the logic thread waited for room in the pipeline to hand this batch over. */
    long backPressureNanos;

    WriterBatch(List<WriterSlot<?>> accepted, List<EventStore.Pending> events, Object views, WriterSlot<?> tainted, Throwable taint,
                HerdWriterBatch jfr) {
        this.accepted = accepted;
        this.events = events;
        this.views = views;
        this.tainted = tainted;
        this.taint = taint;
        this.jfr = jfr;
    }
}
