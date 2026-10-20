package crowdvsmarket.server.push;

import crowdvsmarket.server.api.RateLimiter;
import crowdvsmarket.server.auth.SessionService;
import io.quarkus.websockets.next.HttpUpgradeCheck;
import io.quarkus.websockets.next.UserData;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Before the upgrade of {@code /api/ws}: the switch ({@code herd.push.enabled}), the budget of upgrade attempts per client address
 * (the {@link RateLimiter} the RPC layer uses, with the budget of {@code herd.push.upgrade.*}; the address is the proxy-aware
 * one, as in {@code RpcResource}), and the session: the {@code herd_session} cookie of the upgrade request is looked up (a blocking
 * database read, so on a worker thread) and the player it belongs to is kept on the connection for the private {@code me} topic.
 * No cookie, or one that no session answers to, is a signed-out socket, not an error.
 */
@ApplicationScoped
public class PushUpgradeCheck implements HttpUpgradeCheck {

    static final UserData.TypedKey<String> IP = UserData.TypedKey.forString("herd.ip");
    static final UserData.TypedKey<String> PLAYER = UserData.TypedKey.forString("herd.player");

    @Inject
    PushHub hub;
    @Inject
    PushConfig config;
    @Inject
    RateLimiter rateLimiter;
    @Inject
    SessionService sessions;

    @Override
    public Uni<CheckResult> perform(HttpUpgradeContext context) {
        if (!hub.enabled()) {
            return CheckResult.rejectUpgrade(404);
        }
        HttpServerRequest request = context.httpRequest();
        String ip = request.remoteAddress() == null ? "unknown" : request.remoteAddress().host();
        if (!rateLimiter.tryAcquire(ip + ":ws", config.upgradeCapacity(), config.upgradeRefillPerSec())) {
            return CheckResult.rejectUpgrade(429);
        }
        context.userData().put(IP, ip);
        String sessionId = cookie(request.getHeader("Cookie"), sessions.cookieName());
        if (sessionId == null) {
            return CheckResult.permitUpgrade();
        }
        return Uni.createFrom().item(() -> sessions.playerIdFor(sessionId).orElse(null))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onItem().transformToUni(playerId -> {
                    if (playerId != null) {
                        context.userData().put(PLAYER, playerId);
                    }
                    return CheckResult.permitUpgrade();
                });
    }

    /** The value of cookie {@code name} in a {@code Cookie} header, or {@code null}. */
    static String cookie(String header, String name) {
        if (header == null) {
            return null;
        }
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                String value = trimmed.substring(name.length() + 1);
                return value.isBlank() ? null : value;
            }
        }
        return null;
    }
}
