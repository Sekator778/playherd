package crowdvsmarket.server.jfr;

import jdk.jfr.Category;
import jdk.jfr.DataAmount;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

/** I-5: one {@code EngineStateBuilder.build} (the replay of the whole event log). */
@Name("herd.Rebuild")
@Category("Herd")
@Label("Engine rebuild")
@Description("Replay of the event log into a fresh engine state")
public final class HerdRebuild extends Event {
    @Label("eventStore.loadAll")
    @Timespan(Timespan.NANOSECONDS)
    public long loadNs;
    @Label("apply loop")
    @Timespan(Timespan.NANOSECONDS)
    public long applyNs;
    @Label("Events replayed")
    public int events;
    @Label("Heap used before")
    @DataAmount
    public long heapBefore;
    @Label("Heap used after")
    @DataAmount
    public long heapAfter;
}
