package crowdvsmarket.server.config;

import crowdvsmarket.engine.round.RoundDurations;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Central read of the {@code herd.*} config keys (application.properties) used across the
 *  server; small enough to keep as one bean rather than a {@code @ConfigMapping} per feature. */
@ApplicationScoped
public class GameConfig {

    @ConfigProperty(name = "herd.mode")
    String modeRaw;

    @ConfigProperty(name = "herd.round-durations.hourly-sec")
    long hourlySec;

    @ConfigProperty(name = "herd.round-durations.daily-sec")
    long dailySec;

    @ConfigProperty(name = "herd.replay.speedup")
    double replaySpeedup;

    @ConfigProperty(name = "herd.bots.count")
    int botsCount;

    @ConfigProperty(name = "herd.scheduler.tick-ms")
    long tickMs;

    @ConfigProperty(name = "herd.operator.token")
    String operatorToken;

    @ConfigProperty(name = "herd.session.cookie-name")
    String sessionCookieName;

    @ConfigProperty(name = "herd.session.ttl-hours")
    long sessionTtlHours;

    @ConfigProperty(name = "herd.auth.challenge-ttl-sec")
    long challengeTtlSec;

    @ConfigProperty(name = "herd.oracle.launcher-id")
    String oracleLauncherId;

    @ConfigProperty(name = "herd.oracle.network")
    String oracleNetwork;

    @ConfigProperty(name = "herd.chainsvc.token")
    String chainsvcToken;

    @ConfigProperty(name = "herd.ratelimit.capacity")
    int rateLimitCapacity;

    @ConfigProperty(name = "herd.ratelimit.refill-per-sec")
    double rateLimitRefillPerSecond;

    public GameMode mode() {
        return GameMode.parse(modeRaw);
    }

    public RoundDurations roundDurations() {
        return new RoundDurations(hourlySec * 1000L, dailySec * 1000L);
    }

    public double replaySpeedup() {
        return replaySpeedup;
    }

    public int botsCount() {
        return botsCount;
    }

    public long tickMs() {
        return tickMs;
    }

    public String operatorToken() {
        return operatorToken;
    }

    public String sessionCookieName() {
        return sessionCookieName;
    }

    public long sessionTtlMs() {
        return sessionTtlHours * 3_600_000L;
    }

    public long challengeTtlMs() {
        return challengeTtlSec * 1000L;
    }

    public String oracleLauncherId() {
        return oracleLauncherId;
    }

    public String oracleNetwork() {
        return oracleNetwork;
    }

    /** FIXED INTERFACE (a): the bearer token this server sends to chainsvc on every {@code
     *  /v1/*} call (chainsvc's own {@code C2_SVC_TOKEN} must hold the same value — {@code
     *  tools/stand.sh} generates both into {@code .env} together). */
    public String chainsvcToken() {
        return chainsvcToken;
    }

    public int rateLimitCapacity() {
        return rateLimitCapacity;
    }

    public double rateLimitRefillPerSecond() {
        return rateLimitRefillPerSecond;
    }
}
