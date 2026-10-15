package crowdvsmarket.server.testsupport;

import java.util.HashMap;
import java.util.Map;

/** {@link EconomyProfile} with three bots (the scheduler stays off: the test calls the bot service itself). */
public class EconomyWithBotsProfile extends EconomyProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("herd.bots.count", "3");
        return overrides;
    }
}
