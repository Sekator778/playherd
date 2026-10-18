package crowdvsmarket.server.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

/** I-2: one {@code GameService.placeBet} call, split by phase. The event's own duration is the whole call. */
@Name("herd.PlaceBet")
@Category("Herd")
@Label("placeBet")
@Description("One placeBet call: lock wait and the phases under the lock")
public final class HerdPlaceBet extends Event {
    @Label("Lock wait")
    @Timespan(Timespan.NANOSECONDS)
    public long waitNs;
    @Label("round_results.exists")
    @Timespan(Timespan.NANOSECONDS)
    public long existsNs;
    @Label("requireExposureRoom")
    @Timespan(Timespan.NANOSECONDS)
    public long exposureNs;
    @Label("Bets already in the round (this market)")
    public int roundBets;
    @Label("persist (transaction)")
    @Timespan(Timespan.NANOSECONDS)
    public long persistNs;
    @Label("eventApplier.apply")
    @Timespan(Timespan.NANOSECONDS)
    public long applyNs;
    @Label("Outcome")
    public String outcome;
}
