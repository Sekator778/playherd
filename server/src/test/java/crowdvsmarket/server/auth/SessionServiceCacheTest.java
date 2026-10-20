package crowdvsmarket.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.persistence.SessionRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * {@link SessionService} answers a session from {@link SessionCache} and reads the {@code sessions} table only on a miss; a sign-out
 * and the row's expiry end a session at once. (A revoked API key needs no cache test of its own: the key lookup is
 * {@code PlayerDirectory#byKeyHash}, memory that {@code updateKey} changes in the same step as the row -
 * {@code ApiBotsTest#aWrongMalformedMissingOrRevokedKeyIsRefused}.)
 */
@QuarkusTest
class SessionServiceCacheTest {

    @Inject
    SessionService sessions;
    @InjectSpy
    SessionRepository repo;

    private static String id() {
        return "cache-test-" + UUID.randomUUID();
    }

    @Test
    void theTableIsReadOnceForManyLookups() {
        String sessionId = sessions.create("p-cache-1", System.currentTimeMillis());
        Mockito.clearInvocations(repo);

        for (int i = 0; i < 50; i++) {
            assertThat(sessions.playerIdFor(sessionId)).contains("p-cache-1");
        }

        Mockito.verify(repo, Mockito.times(1)).find(sessionId);
    }

    @Test
    void signOutEndsTheSessionAtOnce() {
        String sessionId = sessions.create("p-cache-2", System.currentTimeMillis());
        assertThat(sessions.playerIdFor(sessionId)).contains("p-cache-2"); // cached now

        sessions.destroy(sessionId);

        assertThat(sessions.playerIdFor(sessionId)).isEmpty();
        assertThat(repo.find(sessionId)).isEmpty();
    }

    @Test
    void aSessionThatDoesNotExistIsAMissEveryTimeAndAnotherPathCanCreateIt() {
        String sessionId = id();
        assertThat(sessions.playerIdFor(sessionId)).isEmpty();

        repo.insert(sessionId, "p-cache-3", System.currentTimeMillis(), System.currentTimeMillis() + 60_000);

        assertThat(sessions.playerIdFor(sessionId)).contains("p-cache-3"); // nothing negative was remembered
    }

    @Test
    void theRowsExpiryEndsTheSessionEvenWhileItIsCached() throws InterruptedException {
        String sessionId = id();
        long now = System.currentTimeMillis();
        repo.insert(sessionId, "p-cache-4", now, now + 400);
        assertThat(sessions.playerIdFor(sessionId)).contains("p-cache-4");

        Thread.sleep(500);

        assertThat(sessions.playerIdFor(sessionId)).isEmpty();
    }

    @Test
    void anExpiredRowIsNeverAnswered() {
        String sessionId = id();
        long now = System.currentTimeMillis();
        repo.insert(sessionId, "p-cache-5", now - 10_000, now - 1);

        assertThat(sessions.playerIdFor(sessionId)).isEmpty();
        assertThat(sessions.playerIdFor(sessionId)).isEmpty();
    }

    @Test
    void clearCacheSendsTheNextLookupBackToTheTable() {
        String sessionId = sessions.create("p-cache-6", System.currentTimeMillis());
        sessions.playerIdFor(sessionId);
        Mockito.clearInvocations(repo);

        sessions.clearCache();
        sessions.playerIdFor(sessionId);

        Mockito.verify(repo, Mockito.times(1)).find(sessionId);
    }
}
