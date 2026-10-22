package crowdvsmarket.server.auth;

import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.jfr.HerdSession;
import crowdvsmarket.server.persistence.AuthChallengeRepository;
import crowdvsmarket.server.persistence.SessionRepository;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/** {@code herd_session} httpOnly cookie backing — an opaque, DB-backed session id (Р-4 §4.g). A lookup is answered from
 *  {@link SessionCache} (a short-lived read-through of the table) and goes to the database only on a miss. */
@ApplicationScoped
public class SessionService {

    @Inject
    GameConfig config;
    @Inject
    SessionRepository repo;
    @Inject
    AuthChallengeRepository challenges;

    private static final Logger LOG = Logger.getLogger(SessionService.class);

    @ConfigProperty(name = "herd.session.cache-ttl-seconds", defaultValue = "30")
    long cacheTtlSeconds;
    @ConfigProperty(name = "herd.session.cache-max-entries", defaultValue = "20000")
    int cacheMaxEntries;

    /** Security review M2: sessions a player may hold at once; signing in past it ends the oldest. */
    @ConfigProperty(name = "herd.session.max-per-player", defaultValue = "20")
    int maxPerPlayer;

    private final SecureRandom random = new SecureRandom();
    private volatile SessionCache cache;

    private SessionCache cache() {
        SessionCache c = cache;
        if (c == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = new SessionCache(cacheTtlSeconds * 1000L, cacheMaxEntries);
                }
                c = cache;
            }
        }
        return c;
    }

    /** Forgets every cached session (the table was emptied by other means than a sign-out). */
    public void clearCache() {
        cache().clear();
    }

    public String create(String playerId, long nowMs) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repo.insert(sessionId, playerId, nowMs, nowMs + config.sessionTtlMs());
        for (String old : repo.idsBeyondNewest(playerId, maxPerPlayer)) {
            repo.delete(old);
            cache().invalidate(old);
        }
        return sessionId;
    }

    public int maxPerPlayer() {
        return maxPerPlayer;
    }

    /** Hourly: removes the sign-in challenges and the sessions that have expired (nothing else ever did; security review M2). */
    @Scheduled(every = "1h", delayed = "5m", concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = "herd-auth-purge")
    void purgeExpired() {
        try {
            purgeExpired(System.currentTimeMillis());
        } catch (RuntimeException e) {
            LOG.warn("purge of expired sessions and challenges failed", e);
        }
    }

    /** Returns the number of rows deleted (challenges + sessions). */
    int purgeExpired(long nowMs) {
        int n = challenges.deleteExpired(nowMs) + repo.deleteExpired(nowMs);
        if (n > 0) {
            LOG.infof("purged %d expired sign-in challenges and sessions", n);
        }
        return n;
    }

    /** {@code Optional.empty()} for a missing/expired session — callers treat that as signed-out,
     *  never as an error, matching contract.ts's {@code currentPlayer(): Promise<PlayerSummaryDto|null>}. */
    public Optional<String> playerIdFor(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        SessionCache cache = cache();
        Optional<String> cached = cache.get(sessionId, now);
        if (cached.isPresent()) {
            return cached;
        }
        long generation = cache.generation();
        HerdSession jfr = new HerdSession(); // I-6: the SELECT of the session row (a cache miss only)
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
        Optional<SessionRepository.Row> valid = row.filter(r -> r.expiresAtMs() > now);
        valid.ifPresent(r -> cache.put(sessionId, r.playerId(), r.expiresAtMs(), now, generation));
        return valid.map(SessionRepository.Row::playerId);
    }

    public void destroy(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            repo.delete(sessionId);
            cache().invalidate(sessionId); // after the row is gone: a lookup that misses from now on finds nothing in the table either
        }
    }

    public String cookieName() {
        return config.sessionCookieName();
    }
}
