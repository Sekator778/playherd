package crowdvsmarket.server.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** F6: one sync of the journal stage of the core writer: the batches that were waiting, written as one excerpt and synced together
 *  (group commit). The event's own duration is the append and the sync. */
@Name("herd.WriterSync")
@Category("Herd")
@Label("Writer journal sync")
@Description("One group commit of the core writer's journal stage: the batches that shared a sync")
public final class HerdWriterSync extends Event {
    @Label("Batches in the group")
    public int batches;
    @Label("Events written")
    public int events;
    @Label("Batches still waiting behind this group when it was taken")
    public int waitingAfter;
    @Label("Outcome")
    public String outcome;
}
