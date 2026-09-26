package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Task item 1 (live-verification finding, Р-53 follow-up, 2026-09-26): {@code game_events} showed
 * two {@code PlayerRegistered} rows for the same {@code playerId}/wallet address (ids 8985/9048 on
 * the live stand) after nothing more than an ordinary second sign-in — {@code
 * GameService#registerOrTouchPlayer} used to append a fresh event on EVERY sign-in, by design (see
 * the previous revision of its javadoc), not only the first. Fixed by checking {@code
 * LedgerBook#seasonRecord} (a pure read) before persisting anything for a returning player: a
 * second sign-in within the same season is now a genuine no-op in {@code game_events}, exactly
 * like {@code ensureSeasonPack} itself already was a no-op for it — see {@code
 * GameService#registerOrTouchPlayer}'s updated javadoc.
 */
@QuarkusTest
class PlayerRegistrationIdempotencyTest {

    @Inject
    GameService gameService;
    @Inject
    EventStore eventStore;

    private long playerRegisteredCount(String playerId) {
        return eventStore.loadAll().stream()
                .filter(e -> e instanceof GameEvent.PlayerRegistered pr && pr.playerId().equals(playerId))
                .count();
    }

    @Test
    void signingInTwiceInTheSameSeasonAppendsExactlyOnePlayerRegisteredEvent() {
        long now = System.currentTimeMillis();
        String wallet = "txch1regidem" + UUID.randomUUID().toString().replace("-", "");

        GameService.PlayerTouchResult first = gameService.registerOrTouchPlayer(wallet, now);
        assertThat(first.isNewPlayer()).isTrue();
        assertThat(playerRegisteredCount(first.player().playerId())).isEqualTo(1);

        // The exact live scenario: the same wallet signs in again, a few seconds later — same
        // season, no season rollover in between.
        GameService.PlayerTouchResult second = gameService.registerOrTouchPlayer(wallet, now + 5_000);

        assertThat(second.isNewPlayer()).isFalse();
        assertThat(second.player().playerId()).isEqualTo(first.player().playerId());
        assertThat(playerRegisteredCount(first.player().playerId()))
                .as("a second, ordinary sign-in must not append another PlayerRegistered row")
                .isEqualTo(1);

        // A third sign-in, still the same season, is equally a no-op.
        gameService.registerOrTouchPlayer(wallet, now + 10_000);
        assertThat(playerRegisteredCount(first.player().playerId())).isEqualTo(1);
    }

    @Test
    void signingInAsANewPlayerAlwaysAppendsExactlyOnePlayerRegisteredEvent() {
        long now = System.currentTimeMillis();
        String wallet = "txch1regnew" + UUID.randomUUID().toString().replace("-", "");

        GameService.PlayerTouchResult touch = gameService.registerOrTouchPlayer(wallet, now);

        assertThat(touch.isNewPlayer()).isTrue();
        assertThat(playerRegisteredCount(touch.player().playerId())).isEqualTo(1);
    }
}
