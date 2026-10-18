package crowdvsmarket.server.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

/** I-4: one {@code GameService.resolveRound}. The event's own duration is the whole call, price fetch included. */
@Name("herd.Settle")
@Category("Herd")
@Label("resolveRound")
@Description("One round settlement: lock wait and the phases under the lock")
public final class HerdSettle extends Event {
    @Label("Bets settled")
    public int bets;
    @Label("Lock wait")
    @Timespan(Timespan.NANOSECONDS)
    public long waitNs;
    @Label("Under the lock")
    @Timespan(Timespan.NANOSECONDS)
    public long lockedNs;
    @Label("round_results.exists")
    @Timespan(Timespan.NANOSECONDS)
    public long existsNs;
    @Label("eventStore.append")
    @Timespan(Timespan.NANOSECONDS)
    public long appendNs;
    @Label("eventApplier.apply")
    @Timespan(Timespan.NANOSECONDS)
    public long applyNs;
    @Label("storeSettledResult")
    @Timespan(Timespan.NANOSECONDS)
    public long storeNs;
    @Label("enqueueOracleRecord")
    @Timespan(Timespan.NANOSECONDS)
    public long oracleNs;
    @Label("Outcome")
    public String outcome;
}
