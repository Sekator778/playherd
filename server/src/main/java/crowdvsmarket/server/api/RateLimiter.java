package crowdvsmarket.server.api;

import crowdvsmarket.server.config.GameConfig;
import jakarta.enterprise.context.ApplicationScoped;
import io.quarkus.scheduler.Scheduled;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task item 2 (wave-1 security finding): "add a simple per-IP rate limit on
 * authChallenge/authVerify/placeBet (in-memory token bucket, generous limits, 429
 * RATE_LIMITED) — note in docs it is a stand-in for the VPS (D-38)". See
 * docs/research/server_design.md for that note and the chosen defaults.
 *
 * <p>One token bucket per caller-supplied {@code key} — {@code RpcResource} keys it
 * {@code "<ip>:<method>"}, so each of the three rate-limited methods gets its own budget per IP
 * (one noisy method never starves another). The budget is the shared one of {@code herd.ratelimit.*} unless the
 * caller names its own capacity and refill rate ({@link #tryAcquire(String, int, double)}; D-81:
 * {@code submitFeedback}).
 *
 * <p>Memory is bounded (security review 2026-10-22, M2): a bucket idle for longer than it needs to refill to full is the same as
 * a fresh one, so {@link #sweep()} (every minute) forgets it; and the map holds at most {@link #MAX_BUCKETS} buckets - a new key
 * past that shares one overflow bucket. IPv6 callers are keyed by their /64 ({@link #addressKey(String)}), one subscriber's
 * whole range, so rotating the low 64 bits is no escape.
 *
 * <p>In-memory only — предложение, explicitly a stand-in:
 * it resets on every server restart and does not share state across multiple server instances,
 * which is fine for this project's single-process local stand/demo VPS (D-38) and not meant to
 * survive a real multi-instance deployment.
 */
@ApplicationScoped
public class RateLimiter {

    @Inject
    GameConfig config;

    /** The most buckets the map holds; a key that finds it full (after a sweep) takes the shared {@link #OVERFLOW_KEY} bucket. */
    static final int MAX_BUCKETS = 100_000;
    private static final String OVERFLOW_KEY = "\0overflow";
    /** A bucket that never refills (rate 0) is forgotten after this long idle. */
    private static final long NEVER_REFILLS_IDLE_NANOS = 24L * 3600 * 1_000_000_000L;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private volatile int maxBuckets = MAX_BUCKETS;

    /** For tests: lowers the cap of the map. */
    void setMaxBuckets(int max) {
        this.maxBuckets = max;
    }

    int size() {
        return buckets.size();
    }

    /**
     * The address a bucket is keyed by: an IPv4 address as it is, an IPv6 one reduced to its /64 prefix (a subscriber gets a whole
     * /64 and changes the low half at will), an IPv4-mapped IPv6 one as the IPv4 address. Anything that is not an address literal
     * (e.g. {@code "unknown"}) is returned unchanged.
     */
    public static String addressKey(String host) {
        if (host == null || host.indexOf(':') < 0) {
            return host; // IPv4, a name, or nothing: no lookup is ever made for these
        }
        try {
            int zone = host.indexOf('%');
            // a literal with a colon in it is parsed, never resolved
            InetAddress parsed = InetAddress.getByName(zone < 0 ? host : host.substring(0, zone));
            byte[] b = parsed.getAddress();
            if (b.length == 4) {
                return parsed.getHostAddress();
            }
            return String.format("%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64", b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]);
        } catch (UnknownHostException | RuntimeException e) {
            return host;
        }
    }

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
     * starts full, and gets {@code refillPerSecond} back every second (0 = never). D-81 uses it for {@code submitFeedback},
     * which is limited far more strictly than the shared budget. A key must always be asked with the same budget — the
     * bucket is made by the first call with it; the keys are {@code "<ip>:<method>"}, so each method has its own.
     */
    public boolean tryAcquire(String key, int capacity, double refillPerSecond) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxBuckets) {
                sweep();
            }
            bucket = buckets.size() >= maxBuckets
                    ? buckets.computeIfAbsent(OVERFLOW_KEY, k -> new Bucket(capacity))
                    : buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        }
        return bucket.tryConsume(capacity, refillPerSecond);
    }

    /** Forgets the buckets idle for longer than they need to refill to full: a fresh bucket is exactly what they would be. */
    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP, identity = "herd-ratelimit-sweep")
    void sweep() {
        long now = System.nanoTime();
        buckets.entrySet().removeIf(e -> e.getValue().idleLongerThanRefill(now));
    }

    /** One bucket's own state, refilled lazily (no background thread) at whatever rate the config
     *  says every time it is touched - plenty precise for "generous limits" on a token bucket; {@link #sweep()} drops
     *  the idle ones. */
    private static final class Bucket {
        private double tokens;
        private long lastRefillNanos;
        private int capacity;
        private double refillPerSecond;

        Bucket(int capacity) {
            this.tokens = capacity;
            this.capacity = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        /** Whether it has been untouched for as long as a refill from empty to full takes (or a day when it never refills). */
        synchronized boolean idleLongerThanRefill(long nowNanos) {
            long toFull = refillPerSecond > 0
                    ? (long) Math.min(capacity / refillPerSecond * 1e9, NEVER_REFILLS_IDLE_NANOS)
                    : NEVER_REFILLS_IDLE_NANOS;
            return nowNanos - lastRefillNanos > toFull;
        }

        synchronized boolean tryConsume(int capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
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
