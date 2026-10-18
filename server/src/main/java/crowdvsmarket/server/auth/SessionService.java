package crowdvsmarket.server.auth;

import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.jfr.HerdSession;
import crowdvsmarket.server.persistence.SessionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/** {@code herd_session} httpOnly cookie backing — an opaque, DB-backed session id (Р-4 §4.g). */
@ApplicationScoped
public class SessionService {

    @Inject
    GameConfig config;
    @Inject
    SessionRepository repo;

    private final SecureRandom random = new SecureRandom();

    public String create(String playerId, long nowMs) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repo.insert(sessionId, playerId, nowMs, nowMs + config.sessionTtlMs());
        return sessionId;
    }

    /** {@code Optional.empty()} for a missing/expired session — callers treat that as signed-out,
     *  never as an error, matching contract.ts's {@code currentPlayer(): Promise<PlayerSummaryDto|null>}. */
    public Optional<String> playerIdFor(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        HerdSession jfr = new HerdSession(); // I-6: the SELECT of the session row
        boolean rec = jfr.isEnabled();
        if (rec) {
            jfr.begin();
        }
        Optional<SessionRepository.Row> row = repo.find(sessionId);
        if (rec) {
            jfr.end();
            jfr.found = row.isPresent();
            jfr.commit();
        }
        return row.filter(r -> r.expiresAtMs() > System.currentTimeMillis())
                .map(SessionRepository.Row::playerId);
    }

    public void destroy(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            repo.delete(sessionId);
        }
    }

    public String cookieName() {
        return config.sessionCookieName();
    }
}
