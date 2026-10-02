package crowdvsmarket.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * E2 (docs/research/markets_architecture.md §4 п.17): the first player index of a NEW database is random in
 * {@code [2^20, 2^30)}. The chain service keeps its deposit registry for good while a database can be new, and a
 * deposit address derived from the same index twice is one address for two players: databases that all counted
 * 0, 1, 2 … would hand out the same addresses. A database that has handed out an index — a counter that has moved,
 * or players — is never touched. {@code herd.players.first-index} pins the first index (the tests of everything
 * else do, to keep their indexes small and predictable).
 *
 * <p>This class runs the application with the key unset, as production has it.
 */
@QuarkusTest
@TestProfile(PlayerIndexStartTest.NothingPinnedProfile.class)
class PlayerIndexStartTest {

    private static final String KEY = "next_player_index";

    /** The test configuration pins the index; an empty value is "not set" for an {@code OptionalInt}. No bots and
     *  no scheduler: nothing else in this application registers players while the tests reset the table. */
    public static class NothingPinnedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("herd.players.first-index", "", "herd.bots.count", "0", "quarkus.scheduler.enabled", "false");
        }
    }

    @Inject
    ServerMetaRepository serverMeta;
    @Inject
    Db db;

    @BeforeEach
    void aDatabaseWithoutPlayersAndWithoutAnIndexCounter() {
        resetPlayersAndCounter();
    }

    @AfterEach
    void leaveTheSharedDatabaseAsAFreshOne() {
        resetPlayersAndCounter();
    }

    private void resetPlayersAndCounter() {
        db.tx(c -> {
            db.update(c, "TRUNCATE TABLE players CASCADE");
            db.update(c, "DELETE FROM server_meta WHERE key = ?", KEY);
            return null;
        });
    }

    private void setCounter(long value) {
        db.tx(c -> db.update(c, "INSERT INTO server_meta (key, value) VALUES (?, ?) "
                + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value", KEY, Long.toString(value)));
    }

    private long counter() {
        return Long.parseLong(db.read(c -> db.queryOne(c, "SELECT value FROM server_meta WHERE key = ?",
                rs -> rs.getString("value"), KEY)));
    }

    private void aPlayerWithIndex(int index) {
        db.tx(c -> db.update(c, "INSERT INTO players (player_id, wallet_address, is_bot, player_index, created_at_ms) "
                + "VALUES (?, ?, FALSE, ?, 1)", "p-" + index, "txch1w" + index, index));
    }

    @Test
    void aNewDatabaseStartsAtARandomIndexInTheRangeAndCountsOnFromThere() {
        int first = serverMeta.reserveNextPlayerIndex();
        int second = serverMeta.reserveNextPlayerIndex();
        int third = serverMeta.reserveNextPlayerIndex();

        assertThat(first).as("random in [2^20, 2^30)").isBetween(ServerMetaRepository.FIRST_INDEX_MIN,
                ServerMetaRepository.FIRST_INDEX_MAX - 1);
        assertThat(second).isEqualTo(first + 1);
        assertThat(third).isEqualTo(first + 2);
        assertThat(counter()).isEqualTo(first + 3L);
    }

    @Test
    void twoNewDatabasesDoNotStartAtTheSameIndex() {
        Set<Integer> starts = new HashSet<>();
        for (int database = 0; database < 5; database++) {
            resetPlayersAndCounter();
            starts.add(serverMeta.reserveNextPlayerIndex());
        }

        assertThat(starts).as("five fresh databases, five starts (the chance of two alike is about 10^-8)").hasSize(5);
    }

    @Test
    void anExistingCounterIsNeverChanged() {
        setCounter(57);

        assertThat(serverMeta.reserveNextPlayerIndex()).isEqualTo(57);
        assertThat(serverMeta.reserveNextPlayerIndex()).isEqualTo(58);
        assertThat(counter()).isEqualTo(59L);
    }

    @Test
    void aDatabaseThatHasPlayersIsNeverTouchedEvenWhereTheCounterReadsZero() {
        aPlayerWithIndex(0);
        setCounter(0);

        assertThat(serverMeta.reserveNextPlayerIndex()).as("players exist: an index was handed out, keep counting")
                .isEqualTo(0 + 0);
        assertThat(counter()).isEqualTo(1L);
    }

    @Test
    void aPinnedFirstIndexIsUsedForANewDatabase() {
        ServerMetaRepository pinned = new ServerMetaRepository();
        pinned.pinnedFirstPlayerIndex = OptionalInt.of(5);
        assertThat(pinned.firstPlayerIndexOfANewDatabase()).isEqualTo(5);

        ServerMetaRepository unpinned = new ServerMetaRepository();
        assertThat(unpinned.pinnedFirstPlayerIndex).isEmpty();
        assertThat(unpinned.firstPlayerIndexOfANewDatabase()).isBetween(ServerMetaRepository.FIRST_INDEX_MIN,
                ServerMetaRepository.FIRST_INDEX_MAX - 1);
    }

    @Test
    void theRandomIndexIsUniformlySpreadOverTheRangeAndNeverLeavesIt() {
        SecureRandom random = new SecureRandom();
        long sum = 0;
        int below = 0;
        int n = 20_000;
        int middle = (ServerMetaRepository.FIRST_INDEX_MIN + ServerMetaRepository.FIRST_INDEX_MAX) / 2;
        for (int i = 0; i < n; i++) {
            int index = ServerMetaRepository.randomFirstPlayerIndex(random);
            assertThat(index).isGreaterThanOrEqualTo(ServerMetaRepository.FIRST_INDEX_MIN)
                    .isLessThan(ServerMetaRepository.FIRST_INDEX_MAX);
            sum += index;
            if (index < middle) {
                below++;
            }
        }

        assertThat(below / (double) n).as("about half below the middle of the range").isBetween(0.47, 0.53);
        assertThat(sum / (double) n / middle).as("mean near the middle").isBetween(0.95, 1.05);
        assertThat(ServerMetaRepository.FIRST_INDEX_MAX).as("an int, with room for 2^30 players to count on")
                .isEqualTo(1 << 30);
    }
}
