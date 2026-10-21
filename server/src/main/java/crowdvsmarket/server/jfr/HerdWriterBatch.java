package crowdvsmarket.server.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

/** F6: one batch of the core writer ({@code CoreWriter}). The event's own duration is the whole cycle: the engine lock taken,
 *  the commands run, the lock released, the batch committed, the callers answered. */
@Name("herd.WriterBatch")
@Category("Herd")
@Label("Writer batch")
@Description("One batch of the core writer: the commands, the engine-lock hold and the commit")
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
    @Label("Append of the batch to the journal and sync to disk (the durability point)")
    @Timespan(Timespan.NANOSECONDS)
    public long commitNs;
    @Label("Database writes of the commands that have some (after the journal)")
    @Timespan(Timespan.NANOSECONDS)
    public long sidecarNs;
    @Label("Outcome")
    public String outcome;
}
