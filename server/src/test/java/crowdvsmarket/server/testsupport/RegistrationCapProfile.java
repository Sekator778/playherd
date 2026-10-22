package crowdvsmarket.server.testsupport;

import java.util.HashMap;
import java.util.Map;

/** {@link LiveMarketsProfile} with at most 4 new players per UTC day in all and at most 2 per address (security review H4). */
public class RegistrationCapProfile extends LiveMarketsProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("herd.registration.max-per-day", "4");
        overrides.put("herd.registration.max-per-ip-per-day", "2");
        return overrides;
    }
}
