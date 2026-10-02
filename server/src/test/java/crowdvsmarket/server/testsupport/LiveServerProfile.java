package crowdvsmarket.server.testsupport;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * The whole server in {@code herd.mode=live} on the REAL price pipeline with the offline source: {@code
 * herd.prices.source=synthetic} (the deterministic walk, one tick a second, and its catalog listing PERP and
 * ODDS markets) — no network, no scripted prices, and the Quarkus scheduler ON: rounds are 6 and 12 seconds
 * long and really run, are settled by the scheduler from the pipeline's ticks, and the test waits for them
 * (no bots here; {@link LiveServerWithBotsProfile} has some).
 *
 * <p>Five markets: BTC (the migration's, lot exponent 0), ETH, DOGE (a PERP whose price has seven decimals:
 * lot exponent 5) and one synthetic ODDS market each for politics and sports (six decimals). {@code
 * herd.markets.stocks/indices/commodities} must name something (an empty value is the default) and a name
 * already listed under crypto is listed once, so {@code BTC} there means "nothing more". The catalog is asked
 * every 2 seconds instead of every minute: the per-class database reset ({@code CleanDatabasePerTestClass}) may
 * delete the listed markets right after the first run, and the next run lists them again.
 */
public class LiveServerProfile implements QuarkusTestProfile {

    /** The markets this profile lists, BTC first. */
    public static final String[] MARKET_IDS = {"BTC", "ETH", "DOGE", "PMSYN1", "PMSYN2"};

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
                Map.entry("herd.mode", "live"),
                Map.entry("herd.prices.source", "synthetic"),
                Map.entry("herd.round-durations.hourly-sec", "6"),
                Map.entry("herd.round-durations.daily-sec", "12"),
                Map.entry("herd.bots.count", "0"),
                Map.entry("herd.markets.crypto", "BTC,ETH,DOGE"),
                Map.entry("herd.markets.stocks", "BTC"),
                Map.entry("herd.markets.indices", "BTC"),
                Map.entry("herd.markets.commodities", "BTC"),
                Map.entry("herd.markets.politics.count", "1"),
                Map.entry("herd.markets.sports.count", "1"),
                Map.entry("herd.markets.synthetic.catalog-every", "2s"));
    }
}
