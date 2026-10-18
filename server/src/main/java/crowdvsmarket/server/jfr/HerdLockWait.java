package crowdvsmarket.server.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** I-1: how long an outermost acquisition of the engine lock waited, and how many threads were queued when it arrived. */
@Name("herd.LockWait")
@Category("Herd")
@Label("Engine lock wait")
@Description("Time from asking for the engine lock to holding it (not reentrant acquisitions)")
public final class HerdLockWait extends Event {
    @Label("Mode")
    public String mode;
    @Label("Threads queued on arrival")
    public int queueLength;
}
