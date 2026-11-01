package crowdvsmarket.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ServerMetaRepository#raiseOracleSeqTo}: the oracle seq floor's one write
 * (docs/research/markets_architecture.md §7 item 4) — monotonic, in a transaction of its own, under the same
 * row lock as the settlement transaction that reserves a {@code seq}. {@link LiveMarketsProfile}: nothing else
 * reserves a {@code seq} while a test looks at the counter. The counter survives test classes, so every test
 * leaves it at or above where it found it.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class ServerMetaOracleSeqTest {

    @Inject
    ServerMetaRepository serverMeta;
    @Inject
    Db db;

    private long counterBefore;

    @BeforeEach
    void rememberTheCounter() {
        counterBefore = counter();
    }

    @AfterEach
    void putTheCounterBackAtOrAboveWhereItWas() {
        setCounter(Math.max(counterBefore, counter()));
    }

    private long counter() {
        String value = db.read(c -> db.queryOne(c, "SELECT value FROM server_meta WHERE key = 'oracle_seq'",
                rs -> rs.getString("value")));
        return value == null ? 0 : Long.parseLong(value);
    }

    private void setCounter(long value) {
        db.tx(c -> db.update(c, """
                INSERT INTO server_meta (key, value) VALUES ('oracle_seq', ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, Long.toString(value)));
    }

    @Test
    void raisingToAFloorAboveTheCounterMakesTheNextSeqTheFloorPlusOne() {
        setCounter(10);

        assertThat(serverMeta.raiseOracleSeqTo(1145)).isEqualTo(1145);

        assertThat(counter()).isEqualTo(1145);
        assertThat(serverMeta.reserveOracleSeqRange(1)).isEqualTo(1146);
    }

    @Test
    void aFloorAtOrBelowTheCounterChangesNothing() {
        setCounter(1145);

        assertThat(serverMeta.raiseOracleSeqTo(1145)).isEqualTo(1145);
        assertThat(serverMeta.raiseOracleSeqTo(1000)).isEqualTo(1145);
        assertThat(serverMeta.raiseOracleSeqTo(0)).isEqualTo(1145);

        assertThat(counter()).isEqualTo(1145);
        assertThat(serverMeta.reserveOracleSeqRange(1)).isEqualTo(1146);
    }

    @Test
    void theCounterNeverGoesDownWhateverOrderTheFloorsComeIn() {
        setCounter(0);

        serverMeta.raiseOracleSeqTo(300);
        serverMeta.raiseOracleSeqTo(100);
        serverMeta.raiseOracleSeqTo(500);
        serverMeta.raiseOracleSeqTo(400);

        assertThat(counter()).isEqualTo(500);
    }

    @Test
    void aDatabaseThatNeverIssuedASeqHasNoCounterRowYetAndTheRaiseCreatesIt() {
        db.tx(c -> db.update(c, "DELETE FROM server_meta WHERE key = 'oracle_seq'"));

        assertThat(serverMeta.raiseOracleSeqTo(1145)).isEqualTo(1145);
        assertThat(serverMeta.reserveOracleSeqRange(1)).isEqualTo(1146);
    }

    @Test
    void aFloorOfZeroOnAFreshDatabaseLeavesTheFirstSeqAtOne() {
        db.tx(c -> db.update(c, "DELETE FROM server_meta WHERE key = 'oracle_seq'"));

        assertThat(serverMeta.raiseOracleSeqTo(0)).isZero();
        assertThat(serverMeta.reserveOracleSeqRange(1)).isEqualTo(1);
    }

    @Test
    void aRaiseAfterSeveralReservationsOfARangeKeepsTheRangesDisjoint() {
        setCounter(100);
        long first = serverMeta.reserveOracleSeqRange(5); // 101..105

        assertThat(serverMeta.raiseOracleSeqTo(103)).isEqualTo(105); // inside what is already reserved: nothing
        assertThat(serverMeta.raiseOracleSeqTo(200)).isEqualTo(200);
        long next = serverMeta.reserveOracleSeqRange(3);

        assertThat(first).isEqualTo(101);
        assertThat(next).isEqualTo(201);
    }

    @Test
    void aFloorOutsideTheSafeRangeIsRefusedAndChangesNothing() {
        setCounter(50);

        assertThatThrownBy(() -> serverMeta.raiseOracleSeqTo(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> serverMeta.raiseOracleSeqTo(ServerMetaRepository.MAX_ORACLE_SEQ + 1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(counter()).isEqualTo(50);
        assertThat(serverMeta.raiseOracleSeqTo(ServerMetaRepository.MAX_ORACLE_SEQ))
                .isEqualTo(ServerMetaRepository.MAX_ORACLE_SEQ);
        setCounter(50); // do not leave 2^53 behind for the next test
    }

    @Test
    void concurrentReservationsAndRaisesNeverIssueTheSameSeqTwiceOrGoBackwards() throws Exception {
        setCounter(0);
        int reservers = 4;
        int perReserver = 150;
        ExecutorService pool = Executors.newFixedThreadPool(reservers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean reserving = new AtomicBoolean(true);
        try {
            List<Future<List<Long>>> reserved = new ArrayList<>();
            for (int t = 0; t < reservers; t++) {
                reserved.add(pool.submit(() -> {
                    start.await();
                    List<Long> mine = new ArrayList<>();
                    for (int i = 0; i < perReserver; i++) {
                        mine.add(serverMeta.reserveOracleSeqRange(1));
                    }
                    return mine;
                }));
            }
            Future<?> raiser = pool.submit(() -> {
                start.await();
                long floor = 0;
                while (reserving.get()) {
                    floor += 25; // a chain service whose maxKnownSeq keeps growing
                    serverMeta.raiseOracleSeqTo(floor);
                    Thread.sleep(1);
                }
                return null;
            });
            start.countDown();

            List<Long> all = new ArrayList<>();
            for (Future<List<Long>> f : reserved) {
                List<Long> mine = f.get(60, TimeUnit.SECONDS);
                assertThat(mine).as("one reserver sees ever-larger seqs").isSorted();
                all.addAll(mine);
            }
            reserving.set(false);
            raiser.get(10, TimeUnit.SECONDS);

            Set<Long> distinct = new TreeSet<>(all);
            assertThat(distinct).as("no seq issued twice").hasSize(reservers * perReserver);
            assertThat(counter()).as("the counter ends at or above every seq issued")
                    .isGreaterThanOrEqualTo(Collections.max(all));
        } finally {
            reserving.set(false);
            pool.shutdownNow();
        }
    }
}
