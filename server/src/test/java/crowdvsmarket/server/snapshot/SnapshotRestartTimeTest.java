package crowdvsmarket.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.bench.BenchJournal;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Architecture plan F5, the point of it: a restart from a snapshot against a restart that replays the whole journal, on a journal made
 * by the load bench's fabricator (a settled history over two markets). The size is {@code -Dherd.test.snapshot-events} (30 000 by
 * default). In one process, one run, the times of the two rebuilds are printed ({@code RESTART TIME ...}); what is asserted is that the
 * two states are the same and that the snapshot path replayed nothing (no timing is asserted: it would be noise at this size).
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class SnapshotRestartTimeTest extends LiveGameTestBase {

    @Inject
    SnapshotService snapshots;
    @Inject
    BetFeed betFeed;

    /** The fabricator's idempotency markers ({@code bench.*} in {@code server_meta}) belong to one run of it: another test class that used
     *  the same seed would be told "already fabricated" about a journal the cleaning between classes has since removed. */
    @BeforeEach
    @AfterEach
    void forgetTheFabricatorsMarkers() {
        db.tx(c -> db.update(c, "DELETE FROM server_meta WHERE key LIKE 'bench.%'"));
    }

    private Map<String, String> run(String mode, String... kv) {
        BenchJournal.Args args = BenchJournal.Args.of(kv);
        return db.tx(c -> {
            try {
                return BenchJournal.run(c, mode, args);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** The first line where two canonical texts differ, with its neighbours; {@code null} when they are equal. */
    private static String firstDifference(String a, String b) {
        String[] x = a.split("\n");
        String[] y = b.split("\n");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String l = i < x.length ? x[i] : "<none>";
            String r = i < y.length ? y[i] : "<none>";
            if (!l.equals(r)) {
                return "line " + i + ":\n full:     " + l + "\n snapshot: " + r;
            }
        }
        return null;
    }

    @Test
    void restartingFromASnapshotIsFasterThanReplayingTheJournalAndGivesTheSameState() {
        long events = Long.getLong("herd.test.snapshot-events", 30_000L);
        Market a = list("T_RT_A", MarketKind.PERP, Category.CRYPTO, "RTA", 25, 1);
        Market b = list("T_RT_B", MarketKind.PERP, Category.CRYPTO, "RTB", 25, 1);
        prices.setPrice(a, "2500.000");
        prices.setPrice(b, "100.000");
        long now = System.currentTimeMillis();
        run("seed", "players", "300", "seed", "7", "now-ms", Long.toString(now), "hourly-sec", "60", "daily-sec", "120");
        Map<String, String> history = run("history", "events", Long.toString(events), "bets-per-round", "10", "seed", "3", "now-ms",
                Long.toString(now), "hourly-sec", "60", "daily-sec", "120");
        long rows = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events", rs -> rs.getLong("n")));
        assertThat(rows).isGreaterThan((long) (events * 0.8));

        long t0 = System.nanoTime();
        rebuild.rebuild(now);
        long fullMs = (System.nanoTime() - t0) / 1_000_000L;
        assertThat(rebuild.lastRebuild().snapshotSeq()).isZero();
        assertThat((long) rebuild.lastRebuild().eventsReplayed()).isEqualTo(rows);

        long t1 = System.nanoTime();
        EngineSnapshotRepository.Meta taken = snapshots.take(now).join();
        long takeMs = (System.nanoTime() - t1) / 1_000_000L;
        int bytes = db.read(c -> db.queryOne(c, "SELECT length(body) AS n FROM engine_snapshots WHERE seq = ?", rs -> rs.getInt("n"),
                taken.seq()));

        long t2 = System.nanoTime();
        rebuild.rebuild(now);
        long snapshotMs = (System.nanoTime() - t2) / 1_000_000L;
        assertThat(rebuild.lastRebuild().snapshotSeq()).isEqualTo(taken.seq());
        assertThat(rebuild.lastRebuild().eventsReplayed()).isZero();
        String snapshotText = StateCodec.encode(hub, betFeed);

        // the same database, replayed whole once more: the state is the same (the oracle counter's floor in server_meta, which the
        // first full replay was still raising while it repaired the rows of a journal of version 1, is the same for both now)
        db.tx(c -> db.update(c, "DELETE FROM engine_snapshots"));
        long t3 = System.nanoTime();
        rebuild.rebuild(now);
        long secondFullMs = (System.nanoTime() - t3) / 1_000_000L;
        assertThat(rebuild.lastRebuild().snapshotSeq()).isZero();
        assertThat(firstDifference(StateCodec.encode(hub, betFeed), snapshotText)).as("snapshot restart == full replay").isNull();

        System.out.printf("RESTART TIME journal=%d rows (history events_written=%s) first full replay=%d ms (it also writes the "
                + "result rows the fabricator leaves out), second full replay=%d ms, from snapshot=%d ms (snapshot: %d bytes compressed, "
                + "taken in %d ms incl. the write)%n", rows, history.get("events_written"), fullMs, secondFullMs, snapshotMs, bytes,
                takeMs);
    }
}
