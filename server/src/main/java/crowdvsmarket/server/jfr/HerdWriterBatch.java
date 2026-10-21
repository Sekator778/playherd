package crowdvsmarket.server.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

/** F6: one batch of the core writer ({@code CoreWriter}). The event's own duration is the batch's whole life, from the moment the logic
 *  thread takes the lock to the moment the output stage has answered its callers; the fields say where the time went: the logic thread's
 *  turn ({@code lockedNs}, {@code logicNs}), its wait for room in the pipeline ({@code backPressureNs}), the wait for the journal stage and
 *  the sync ({@code commitNs}), the output stage ({@code outputNs}). */
@Name("herd.WriterBatch")
@Category("Herd")
@Label("Writer batch")
@Description("One batch of the core writer: the commands, the logic thread's turn, the wait for the pipeline, the sync and the output")
public final class HerdWriterBatch extends Event {
    @Label("Commands in the batch")
    public int commands;
    @Label("Events written")
    public int events;
    @Label("Commands in the queue when the batch was taken")
    public int queued;
    @Label("Longest wait of a command in the queue")
    @Timespan(Timespan.NANOSECONDS)
    public long maxQueueWaitNs;
    @Label("Under the engine lock (hold)")
    @Timespan(Timespan.NANOSECONDS)
    public long lockedNs;
    @Label("The logic thread's turn: the gate taken to the hand-over (commands, views prepared, back-pressure wait included)")
    @Timespan(Timespan.NANOSECONDS)
    public long logicNs;
    @Label("Logic thread waiting for room in the pipeline (back-pressure)")
    @Timespan(Timespan.NANOSECONDS)
    public long backPressureNs;
    @Label("Batches applied and not durable yet when this one was handed over (not counting it)")
    public int pipelineDepth;
    @Label("From the hand-over to the start of the journal stage's sync (waiting behind the sync in progress)")
    @Timespan(Timespan.NANOSECONDS)
    public long journalWaitNs;
    @Label("Append of the batch to the journal and sync to disk, shared with the batches of its group (the durability point)")
    @Timespan(Timespan.NANOSECONDS)
    public long commitNs;
    @Label("Batches that shared the sync")
    public int syncGroup;
    @Label("Output stage: views published, after-commit actions run, callers answered")
    @Timespan(Timespan.NANOSECONDS)
    public long outputNs;
    @Label("Outcome")
    public String outcome;
}
