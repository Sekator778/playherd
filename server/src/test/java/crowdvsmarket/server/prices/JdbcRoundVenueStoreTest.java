package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.persistence.Db;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@code round_venues} (V9) over the real database: a round's row is written once and whoever wrote first decided, the
 * other callers read that row back, and the rows of the last retention are what the server loads at start (Р-76).
 */
@QuarkusTest
class JdbcRoundVenueStoreTest {

    private static final long T0 = 1_791_040_000_000L;

    @Inject
    JdbcRoundVenueStore store;
    @Inject
    Db db;

    private static RoundVenueStore.Decision decision(String suffix, String venue, long atMs) {
        return new RoundVenueStore.Decision("spot_JDBC-1h-" + suffix, "spot_JDBC", venue, atMs);
    }

    @Test
    void aRowIsWrittenOnceAndTheSecondWriterGetsTheFirstRowBack() {
        RoundVenueStore.Decision first = decision("1", "coinbase", T0);

        RoundVenueStore.Decision stored = store.insertOnce(first);
        RoundVenueStore.Decision second = store.insertOnce(decision("1", "kraken", T0 + 5_000));

        assertThat(stored).isEqualTo(first);
        assertThat(second).as("the table's row, not what the second call wanted").isEqualTo(first);
        assertThat(store.find("spot_JDBC-1h-1")).contains(first);
        long rows = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM round_venues WHERE round_id = ?",
                rs -> rs.getLong("n"), "spot_JDBC-1h-1"));
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void aRoundThatIsNotDecidedHasNoRow() {
        assertThat(store.find("spot_JDBC-1h-never")).isEmpty();
    }

    @Test
    void sinceReturnsTheRecentRowsOldestFirst() {
        store.insertOnce(decision("2c", "kraken", T0 + 3_000));
        store.insertOnce(decision("2a", "coinbase", T0 + 1_000));
        store.insertOnce(decision("2b", "coinbase", T0 + 2_000));
        store.insertOnce(decision("2old", "kraken", T0 - 10_000_000));

        List<RoundVenueStore.Decision> rows = store.since(T0 + 500);

        assertThat(rows).extracting(RoundVenueStore.Decision::roundId)
                .filteredOn(id -> id.startsWith("spot_JDBC-1h-2"))
                .containsExactly("spot_JDBC-1h-2a", "spot_JDBC-1h-2b", "spot_JDBC-1h-2c");
        assertThat(rows).extracting(RoundVenueStore.Decision::roundId).doesNotContain("spot_JDBC-1h-2old");
    }

    @Test
    void aHolderThatLoadsTheTableFindsWhatAnotherWrote() {
        RoundVenues writer = new RoundVenues(store, 60_000L, () -> T0 + 10_000);
        writer.decide("spot_JDBC-1h-" + (T0 - 1_000), "spot_JDBC", T0 - 1_000, "kraken", T0);

        RoundVenues afterARestart = new RoundVenues(store, 60_000L, () -> T0 + 10_000);
        assertThat(afterARestart.decided("spot_JDBC-1h-" + (T0 - 1_000))).isEmpty();
        assertThat(afterARestart.load()).isPositive();

        assertThat(afterARestart.decided("spot_JDBC-1h-" + (T0 - 1_000))).contains("kraken");
        assertThat(afterARestart.decidedForStart("spot_JDBC", T0 - 1_000)).contains("kraken");
        assertThat(afterARestart.decide("spot_JDBC-1h-" + (T0 - 1_000), "spot_JDBC", T0 - 1_000, "coinbase", T0 + 9_000))
                .as("a decision after a restart changes nothing").isEqualTo("kraken");
    }

    @Test
    void twoWritersAtOnceLeaveOneRowAndBothSeeIt() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> answers = new ConcurrentLinkedQueue<>();
        try {
            for (int i = 0; i < 8; i++) {
                String venue = i % 2 == 0 ? "coinbase" : "kraken";
                long at = T0 + i;
                pool.submit(() -> {
                    go.await();
                    answers.add(store.insertOnce(decision("race", venue, at)).venue());
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(answers).hasSize(8).containsOnly(store.find("spot_JDBC-1h-race").orElseThrow().venue());
        long rows = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM round_venues WHERE round_id = ?",
                rs -> rs.getLong("n"), "spot_JDBC-1h-race"));
        assertThat(rows).isEqualTo(1);
    }
}
