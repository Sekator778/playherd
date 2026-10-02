package crowdvsmarket.server.testsupport;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;
import java.util.Set;

/**
 * For tests of the multi-market game (docs/research/markets_architecture.md): {@code herd.mode=live}
 * — every active market is played, prices come from {@link ScriptedMarketPrices} (enabled as an
 * alternative below, for these applications only) — and no bots.
 * The Quarkus scheduler is OFF: the test drives {@code RoundSchedulerService.tick(now)} itself with
 * timestamps it chooses, so a window that "ended" is settled at once, deterministically, instead of
 * after a real wait and next to a background tick that would race the test's own setup. Durations only
 * set the size of the windows (the clock is the test's); several test classes share this profile so
 * the application is started once for all of them.
 */
public class LiveMarketsProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "herd.mode", "live",
                "herd.round-durations.hourly-sec", "60",
                "herd.round-durations.daily-sec", "120",
                "herd.bots.count", "0",
                // a failed recovery is retried by every tick(now): no wait between two attempts
                "herd.rebuild.retry-ms", "0",
                "quarkus.scheduler.enabled", "false");
    }

    /** The scripted prices are opt-in: only the applications this profile starts get them. */
    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(ScriptedMarketPrices.class);
    }
}
