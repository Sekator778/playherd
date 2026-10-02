package crowdvsmarket.server.testsupport;

import java.util.HashMap;
import java.util.Map;

/** {@link LiveServerProfile} with three bots, each betting on up to three random markets in every round. */
public class LiveServerWithBotsProfile extends LiveServerProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("herd.bots.count", "3");
        return overrides;
    }
}
