package crowdvsmarket.server.auth;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded, short-lived, in-memory read-through of the {@code sessions} table (architecture plan, lmax_roadmap step 1: "кэш сессий").
 * Only sessions that exist are cached (a miss always goes to the database, so a session created by another path is seen at once).
 *
 * <p><b>Rules.</b>
 * <ul>
 *   <li>An entry is a copy of a row, never a source of truth: it lives at most {@code ttlMs} and never past the row's own
 *       {@code expiresAtMs}, so an expired session stops working at the very instant the row says, cache or not.
 *   <li>A sign-out ({@link #invalidate}) removes the entry in this process at once. A lookup that read the row just before the
 *       sign-out and puts it in just after is refused by the {@code generation} counter, which every invalidation moves.
 *   <li>The cache is per process. With several server instances a sign-out on one is seen by the others only when their entries
 *       expire, so the TTL is the longest a signed-out session can still work there; it stays short (default 30 seconds), and an
 *       instance count above one needs this rule to be revisited (shared invalidation) before it is turned on.
 *   <li>Bounded: past {@code maxEntries} the expired entries go first, then the cache is cleared (a cold cache costs one read per
 *       session, nothing more).
 * </ul>
 */
public final class SessionCache {

    private record Entry(String playerId, long validUntilMs) {
    }

    private final long ttlMs;
    private final int maxEntries;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    public SessionCache(long ttlMs, int maxEntries) {
        this.ttlMs = Math.max(0, ttlMs);
        this.maxEntries = Math.max(1, maxEntries);
    }

    /** The player of a cached, still valid session. */
    public Optional<String> get(String sessionId, long nowMs) {
        Entry e = entries.get(sessionId);
        if (e == null) {
            return Optional.empty();
        }
        if (nowMs >= e.validUntilMs()) {
            entries.remove(sessionId, e);
            return Optional.empty();
        }
        return Optional.of(e.playerId());
    }

    /** The value to hand to {@link #put} with what a database read of the row returns: taken BEFORE the read. */
    public long generation() {
        return generation.get();
    }

    /** Remembers a row read from the database, unless a sign-out happened since {@code generationBeforeRead}. */
    public synchronized void put(String sessionId, String playerId, long rowExpiresAtMs, long nowMs, long generationBeforeRead) {
        if (ttlMs == 0 || generation.get() != generationBeforeRead) {
            return;
        }
        if (entries.size() >= maxEntries) {
            for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext();) {
                if (nowMs >= it.next().getValue().validUntilMs()) {
                    it.remove();
                }
            }
            if (entries.size() >= maxEntries) {
                entries.clear();
            }
        }
        long validUntil = Math.min(nowMs + ttlMs, rowExpiresAtMs);
        if (validUntil > nowMs) {
            entries.put(sessionId, new Entry(playerId, validUntil));
        }
    }

    /** The session was deleted (sign-out): it is gone from the cache before the caller goes on. */
    public synchronized void invalidate(String sessionId) {
        generation.incrementAndGet();
        entries.remove(sessionId);
    }

    /** Empties the cache (the table was wiped by something other than a sign-out: a test, an operator). */
    public synchronized void clear() {
        generation.incrementAndGet();
        entries.clear();
    }

    public int size() {
        return entries.size();
    }
}
