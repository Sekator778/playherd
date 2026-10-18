package crowdvsmarket.server.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** I-1: how long an outermost holder kept the engine lock; the stack is the unlocking caller, i.e. the GameService method. */
@Name("herd.LockHold")
@Category("Herd")
@Label("Engine lock hold")
@Description("Time from holding the engine lock to releasing it (not reentrant acquisitions)")
public final class HerdLockHold extends Event {
    @Label("Mode")
    public String mode;
}
