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
 * (one noisy method never starves another). The budget is the shared one of {@code herd.ratelimit.*} unless the
 * caller names its own capacity and refill rate ({@link #tryAcquire(String, int, double)}; Р-81:
 * {@code submitFeedback}).
 *
 * <p>In-memory only — предложение, explicitly a stand-in:
 * it resets on every server restart and does not share state across multiple server instances,
 * which is fine for this project's single-process local stand/demo VPS (Р-38) and not meant to
 * survive a real multi-instance deployment.
 */
@ApplicationScoped
public class RateLimiter {

    @Inject
    GameConfig config;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Forgets every bucket. For tests that share one application with other test classes and would otherwise inherit their
     *  spent budget for 127.0.0.1. */
    public void clear() {
        buckets.clear();
    }

    /** {@code true} if a token was available and has been consumed; {@code false} if the caller
     *  should be refused (429 RATE_LIMITED). The shared budget of {@code herd.ratelimit.*}. */
    public boolean tryAcquire(String key) {
        return tryAcquire(key, config.rateLimitCapacity(), config.rateLimitRefillPerSecond());
    }

    /**
     * {@link #tryAcquire(String)} with a budget of its own: the bucket of {@code key} holds {@code capacity} tokens at most,
     * starts full, and gets {@code refillPerSecond} back every second (0 = never). Р-81 uses it for {@code submitFeedback},
     * which is limited far more strictly than the shared budget. A key must always be asked with the same budget — the
     * bucket is made by the first call with it; the keys are {@code "<ip>:<method>"}, so each method has its own.
     */
    public boolean tryAcquire(String key, int capacity, double refillPerSecond) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        return bucket.tryConsume(capacity, refillPerSecond);
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
