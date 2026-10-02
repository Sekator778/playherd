package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.testsupport.CleanDatabasePerTestClass;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The per-class reset of the test database ({@link CleanDatabasePerTestClass}) is one step under the engine
 * lock: a bet that is placed while it runs either lands before the truncation and goes with it, or finds
 * the players reloaded and is refused — it can never be written into the freshly emptied event table and
 * replayed into the next class's feed (the "3 expected, 4 found" of {@code BetFeedCapsTest} in CI: a
 * leftover bot's bet). Here a bettor hammers {@code placeBet} for players that exist while the reset runs
 * over and over; after every reset the game must be empty, in the table and in memory.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class CleanDatabaseResetTest extends LiveGameTestBase {

    @Inject
    BetFeed betFeed;

    private long count(String sql) {
        return db.read(c -> db.queryOne(c, sql, rs -> rs.getLong("n")));
    }

    @Test
    void aBetPlacedWhileTheResetRunsNeverSurvivesIt() throws Exception {
        List<String> players = new CopyOnWriteArrayList<>();
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> bettor = pool.submit(() -> {
            while (!stop.get()) {
                for (String player : List.copyOf(players)) {
                    try {
                        RoundWindow window = roundProgress.bettingWindow("BTC", Interval.HOURLY);
                        gameService.placeBet(player, bet(window, Enums.Direction.UP, 2, "100.000"),
                                window.startMs() - 1);
                    } catch (ApiException | IllegalStateException expected) {
                        // unknown player (the reset got there first), no series yet, ...
                    }
                }
            }
        });
        try {
            for (int round = 0; round < 40; round++) {
                for (int i = 0; i < 5; i++) {
                    players.add(signUp("reset"));
                }
                Thread.sleep(3);   // let the bettor place a few
                CleanDatabasePerTestClass.resetDatabaseAndMemory();
                players.clear();

                // nothing of what happened before the reset is left: not in the table ...
                assertThat(count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'BetPlaced'"))
                        .as("bet events surviving reset #" + round).isZero();
                assertThat(count("SELECT COUNT(*) AS n FROM players WHERE NOT is_bot")).isZero();
                // ... nor in memory
                RoundWindow window = roundProgress.bettingWindow("BTC", Interval.HOURLY);
                assertThat(betFeed.since(window.roundId(), 0).entries()).as("feed after reset #" + round).isEmpty();
                assertThat(hub.ledger().playerIds()).isEmpty();
            }
        } finally {
            stop.set(true);
            bettor.get(20, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }
}
