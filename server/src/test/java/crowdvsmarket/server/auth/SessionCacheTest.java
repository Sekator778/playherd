package crowdvsmarket.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The rules of {@link SessionCache}: a read-through of existing rows only, short-lived, never past the row's own expiry, emptied by
 *  a sign-out at once (also against a lookup that was reading the row meanwhile), bounded. */
class SessionCacheTest {

    private static final long NOW = 1_000_000L;

    @Test
    void aMissIsEmptyAndAPutIsAHitUntilTheTtl() {
        SessionCache cache = new SessionCache(30_000, 100);
        assertThat(cache.get("s1", NOW)).isEmpty();

        cache.put("s1", "p1", NOW + 3_600_000, NOW, cache.generation());

        assertThat(cache.get("s1", NOW)).contains("p1");
        assertThat(cache.get("s1", NOW + 29_999)).contains("p1");
        assertThat(cache.get("s1", NOW + 30_000)).isEmpty();
        assertThat(cache.size()).isZero(); // the expired entry went when it was met
    }

    @Test
    void anEntryNeverOutlivesTheRowsOwnExpiry() {
        SessionCache cache = new SessionCache(30_000, 100);
        cache.put("s1", "p1", NOW + 500, NOW, cache.generation());

        assertThat(cache.get("s1", NOW + 499)).contains("p1");
        assertThat(cache.get("s1", NOW + 500)).isEmpty();
    }

    @Test
    void aRowThatIsAlreadyExpiredIsNotCached() {
        SessionCache cache = new SessionCache(30_000, 100);
        cache.put("s1", "p1", NOW, NOW, cache.generation());
        assertThat(cache.size()).isZero();
    }

    @Test
    void invalidateRemovesTheEntryAtOnce() {
        SessionCache cache = new SessionCache(30_000, 100);
        cache.put("s1", "p1", NOW + 3_600_000, NOW, cache.generation());
        cache.put("s2", "p2", NOW + 3_600_000, NOW, cache.generation());

        cache.invalidate("s1");

        assertThat(cache.get("s1", NOW)).isEmpty();
        assertThat(cache.get("s2", NOW)).contains("p2");
    }

    @Test
    void aLookupThatReadTheRowBeforeASignOutCannotPutItBackAfterIt() {
        SessionCache cache = new SessionCache(30_000, 100);
        long generationBeforeTheRead = cache.generation();
        // ... the lookup has read the row; the sign-out deletes it and invalidates ...
        cache.invalidate("s1");
        // ... and only now the lookup puts what it read
        cache.put("s1", "p1", NOW + 3_600_000, NOW, generationBeforeTheRead);

        assertThat(cache.get("s1", NOW)).isEmpty();
        // a lookup that started after the sign-out is not refused
        cache.put("s2", "p2", NOW + 3_600_000, NOW, cache.generation());
        assertThat(cache.get("s2", NOW)).contains("p2");
    }

    @Test
    void clearEmptiesEverythingAndRefusesWhatWasBeingRead() {
        SessionCache cache = new SessionCache(30_000, 100);
        cache.put("s1", "p1", NOW + 3_600_000, NOW, cache.generation());
        long reading = cache.generation();

        cache.clear();
        cache.put("s2", "p2", NOW + 3_600_000, NOW, reading);

        assertThat(cache.size()).isZero();
    }

    @Test
    void theCacheIsBounded() {
        SessionCache cache = new SessionCache(30_000, 10);
        for (int i = 0; i < 1_000; i++) {
            cache.put("s" + i, "p" + i, NOW + 3_600_000, NOW, cache.generation());
            assertThat(cache.size()).isLessThanOrEqualTo(10);
        }
        assertThat(cache.get("s999", NOW)).contains("p999");
    }

    @Test
    void expiredEntriesGoBeforeTheCacheIsCleared() {
        SessionCache cache = new SessionCache(1_000, 3);
        cache.put("old1", "p", NOW + 3_600_000, NOW, cache.generation());
        cache.put("old2", "p", NOW + 3_600_000, NOW, cache.generation());
        cache.put("old3", "p", NOW + 3_600_000, NOW, cache.generation());

        cache.put("new", "p", NOW + 3_600_000, NOW + 5_000, cache.generation()); // the three are past their TTL

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("new", NOW + 5_000)).contains("p");
    }

    @Test
    void aZeroTtlCachesNothing() {
        SessionCache cache = new SessionCache(0, 10);
        cache.put("s1", "p1", NOW + 3_600_000, NOW, cache.generation());
        assertThat(cache.get("s1", NOW)).isEmpty();
    }
}
