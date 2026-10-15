package crowdvsmarket.server.testsupport;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link LiveMarketsProfile} with the economy of stage B switched ON (Р-92): the game starts with the cutover, TREASURY opens with 1 000 000
 * PLAY (a small treasury, so that the exposure limits of 0.25% / 1% are reachable by a handful of bets), at most 3 new players per IP address a
 * day, withdrawals on. No bots, no scheduler (the test drives the clock). Every other test keeps the old rules ({@code application.properties}:
 * {@code %test.herd.economy.treasury-opening-play=0}).
 */
public class EconomyProfile extends LiveMarketsProfile {

    /** What TREASURY opens with in this profile, whole PLAY. */
    public static final long OPENING_PLAY = 1_000_000L;

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("herd.economy.treasury-opening-play", Long.toString(OPENING_PLAY));
        overrides.put("herd.registration.max-per-ip-per-day", "3");
        overrides.put("herd.withdrawal.enabled", "true");
        return overrides;
    }
}
