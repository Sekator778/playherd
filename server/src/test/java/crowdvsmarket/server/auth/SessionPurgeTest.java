package crowdvsmarket.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.persistence.AuthChallengeRepository;
import crowdvsmarket.server.persistence.SessionRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Security review M2: expired sessions and sign-in challenges are purged, and a player holds a bounded number of sessions. */
@QuarkusTest
class SessionPurgeTest {

    @Inject
    SessionService sessions;
    @Inject
    SessionRepository sessionRepo;
    @Inject
    AuthChallengeRepository challengeRepo;

    @Test
    void expiredRowsGoAndLiveOnesStay() {
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString();
        sessionRepo.insert("old-" + id, "p-purge", now - 10_000, now - 1);
        sessionRepo.insert("live-" + id, "p-purge", now, now + 600_000);
        challengeRepo.insert("old-" + id, "m", "a", now - 1, now - 10_000);
        challengeRepo.insert("live-" + id, "m", "a", now + 600_000, now);

        assertThat(sessions.purgeExpired(now)).isGreaterThanOrEqualTo(2);

        assertThat(sessionRepo.find("old-" + id)).isEmpty();
        assertThat(sessionRepo.find("live-" + id)).isPresent();
        assertThat(challengeRepo.find("old-" + id)).isEmpty();
        assertThat(challengeRepo.find("live-" + id)).isPresent();
    }

    @Test
    void thePlayersOldestSessionEndsWhenTheCapIsPassed() {
        String player = "p-cap-" + UUID.randomUUID();
        long base = System.currentTimeMillis();
        String first = sessions.create(player, base);
        for (int i = 1; i < sessions.maxPerPlayer(); i++) {
            sessions.create(player, base + i);
        }
        assertThat(sessions.playerIdFor(first)).as("exactly at the cap: nobody ends yet").contains(player);

        String newest = sessions.create(player, base + 10_000);

        assertThat(sessions.playerIdFor(first)).as("the oldest went").isEmpty();
        assertThat(sessions.playerIdFor(newest)).contains(player);
        assertThat(sessionRepo.idsBeyondNewest(player, sessions.maxPerPlayer())).isEmpty();
    }
}
