package crowdvsmarket.server.push;

import crowdvsmarket.server.api.RateLimiter;
import crowdvsmarket.server.auth.SessionService;
import crowdvsmarket.server.config.GameConfig;
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
 * No cookie, or one that no session answers to, is a signed-out socket, not an error. A browser's {@code Origin} must be the
 * site's own ({@code herd.public-domain}, its {@code www.} form, the {@code Host} the request came to, or localhost); a request
 * with no {@code Origin} (not a browser) is let through (security review 2026-10-22, L4).
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
    @Inject
    GameConfig gameConfig;

    @Override
    public Uni<CheckResult> perform(HttpUpgradeContext context) {
        if (!hub.enabled()) {
            return CheckResult.rejectUpgrade(404);
        }
        HttpServerRequest request = context.httpRequest();
        if (!originAllowed(request.getHeader("Origin"), request.getHeader("Host"), gameConfig.publicDomain())) {
            return CheckResult.rejectUpgrade(403);
        }
        String ip = request.remoteAddress() == null ? "unknown" : RateLimiter.addressKey(request.remoteAddress().host());
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

    /** {@code true} when there is no {@code Origin} or its host is this site's own. */
    static boolean originAllowed(String origin, String hostHeader, String publicDomain) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        String host;
        try {
            host = java.net.URI.create(origin.strip()).getHost();
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false; // "null" (a sandboxed page) and the like
        }
        host = host.toLowerCase(java.util.Locale.ROOT);
        String domain = publicDomain == null ? "" : publicDomain.strip().toLowerCase(java.util.Locale.ROOT);
        if (host.equals(domain) || host.equals("www." + domain)
                || host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]")) {
            return true;
        }
        if (hostHeader != null) {
            String requested = hostHeader.strip().toLowerCase(java.util.Locale.ROOT);
            int colon = requested.lastIndexOf(':');
            if (colon > 0 && requested.indexOf(']') < colon) {
                requested = requested.substring(0, colon);
            }
            return host.equals(requested);
        }
        return false;
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
