package crowdvsmarket.server.api;

import crowdvsmarket.server.config.GameConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task item 2 (wave-1 security finding): "add a simple per-IP rate limit on
 * authChallenge/authVerify/placeBet (in-memory token bucket, generous limits, 429
 * RATE_LIMITED) — note in docs it is a stand-in for the VPS (Р-38)". See
 * docs/research/server_design.md for that note and the chosen defaults.
 *
 * <p>One token bucket per caller-supplied {@code key} — {@code RpcResource} keys it
 * {@code "<ip>:<method>"}, so each of the three rate-limited methods gets its own budget per IP
 * (one noisy method never starves another). In-memory only — предложение, explicitly a stand-in:
 * it resets on every server restart and does not share state across multiple server instances,
 * which is fine for this project's single-process local stand/demo VPS (Р-38) and not meant to
 * survive a real multi-instance deployment.
 */
@ApplicationScoped
public class RateLimiter {

    @Inject
    GameConfig config;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** {@code true} if a token was available and has been consumed; {@code false} if the caller
     *  should be refused (429 RATE_LIMITED). */
    public boolean tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(config.rateLimitCapacity()));
        return bucket.tryConsume(config.rateLimitCapacity(), config.rateLimitRefillPerSecond());
    }

    /** One bucket's own state, refilled lazily (no background thread) at whatever rate the config
     *  says every time it is touched — plenty precise for "generous limits" on a token bucket, and
     *  avoids needing a sweep to expire old per-IP buckets on top of a scheduler. */
    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;

        Bucket(int capacity) {
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(int capacity, double refillPerSecond) {
            long now = System.nanoTime();
            double elapsedSeconds = Math.max(0, now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }
    }
}
