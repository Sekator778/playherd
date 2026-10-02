package crowdvsmarket.server.testsupport;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link LiveMarketsProfile} with bots: for the tests that need the bot service's monitor in play (it is
 * taken before the engine lock by a bot tick, and after it by a rebuild creating the bots) — still no
 * scheduler of its own, so a test calls {@code BotService#tick} and {@code RoundSchedulerService#tick}
 * itself, when it wants one.
 */
public class LiveMarketsWithBotsProfile extends LiveMarketsProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("herd.bots.count", "4");
        return overrides;
    }
}
