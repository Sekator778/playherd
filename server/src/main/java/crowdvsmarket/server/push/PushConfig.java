package crowdvsmarket.server.push;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** {@code herd.push.*} - see application.properties. The per-address budget of upgrade attempts is {@code herd.push.upgrade.*}. */
@ApplicationScoped
public class PushConfig {

    @ConfigProperty(name = "herd.push.enabled", defaultValue = "true")
    boolean enabled;
    @ConfigProperty(name = "herd.push.coalesce-ms", defaultValue = "200")
    long coalesceMs;
    @ConfigProperty(name = "herd.push.max-connections", defaultValue = "5000")
    int maxConnections;
    @ConfigProperty(name = "herd.push.max-per-ip", defaultValue = "20")
    int maxPerIp;
    @ConfigProperty(name = "herd.push.max-per-player", defaultValue = "6")
    int maxPerPlayer;
    @ConfigProperty(name = "herd.push.max-topics", defaultValue = "40")
    int maxTopics;
    @ConfigProperty(name = "herd.push.max-pending-sends", defaultValue = "64")
    int maxPendingSends;
    @ConfigProperty(name = "herd.push.max-dropped-notices", defaultValue = "200")
    int maxDroppedNotices;
    @ConfigProperty(name = "herd.push.max-age-sec", defaultValue = "3600")
    long maxAgeSec;
    @ConfigProperty(name = "herd.push.client-messages-per-sec", defaultValue = "5")
    double clientMessagesPerSec;
    @ConfigProperty(name = "herd.push.upgrade.capacity", defaultValue = "20")
    int upgradeCapacity;
    @ConfigProperty(name = "herd.push.upgrade.refill-per-sec", defaultValue = "0.5")
    double upgradeRefillPerSec;

    public PushSettings settings() {
        return new PushSettings(enabled, coalesceMs, maxConnections, maxPerIp, maxPerPlayer, maxTopics, maxPendingSends,
                maxDroppedNotices, maxAgeSec, clientMessagesPerSec);
    }

    public int upgradeCapacity() {
        return upgradeCapacity;
    }

    public double upgradeRefillPerSec() {
        return upgradeRefillPerSec;
    }
}
