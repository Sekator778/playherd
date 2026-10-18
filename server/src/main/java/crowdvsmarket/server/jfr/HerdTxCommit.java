package crowdvsmarket.server.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Timespan;

/** I-3: one {@code Db.tx}; the event's own duration is the whole transaction (connection, work, commit). */
@Name("herd.TxCommit")
@Category("Herd")
@Label("Transaction")
@Description("One Db.tx: the whole transaction and its commit")
public final class HerdTxCommit extends Event {
    @Label("commit()")
    @Timespan(Timespan.NANOSECONDS)
    public long commitNs;
    @Label("Committed")
    public boolean committed;
}
