package crowdvsmarket.server.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** I-6: one RPC call as the server sees it (rate limiter, session lookup, method, serialisation excluded). */
@Name("herd.Rpc")
@Category("Herd")
@Label("RPC call")
@Description("Server-side handling time of one RPC method")
public final class HerdRpc extends Event {
    @Label("Method")
    public String method;
    @Label("Result")
    public String result;
}
