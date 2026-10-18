package crowdvsmarket.server.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** I-6: the session lookup ({@code sessions} SELECT) of one request. */
@Name("herd.Session")
@Category("Herd")
@Label("Session lookup")
@Description("SessionService.playerIdFor: the database read of the session row")
public final class HerdSession extends Event {
    @Label("Found")
    public boolean found;
}
