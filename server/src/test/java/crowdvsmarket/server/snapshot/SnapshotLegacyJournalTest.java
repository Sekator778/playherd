package crowdvsmarket.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Architecture plan F5 on a journal of schema version 1 (the fixture {@code JournalVersion1ReplayTest} uses, written by the server
 * before F4): the state a snapshot gives is the old server's own state (its digest, recorded by the old code), the game goes on
 * in version 2 rows on top of it, and snapshot + tail equals the full replay there too. The nightly check on such a journal compares
 * what a replay determines (the roster of a version-1 journal comes from the {@code players} table, not from the journal).
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class SnapshotLegacyJournalTest extends LiveGameTestBase {

    private static final Path DIR = Path.of("src/test/resources/events/journal-v1");

    @Inject
    crowdvsmarket.server.journal.Journal journal;
    @Inject
    SnapshotService snapshots;
    @Inject
    BetFeed betFeed;

    private static String read(String name) {
        try {
            return Files.readString(DIR.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException("missing fixture " + name, e);
        }
    }

    private void load(String table, String file) {
        db.tx(c -> db.update(c, "INSERT INTO " + table + " SELECT * FROM json_populate_recordset(null::" + table + ", ?::json)",
                read(file)));
    }

    @Test
    void aSnapshotOfAVersionOneJournalGivesTheOldServersStateAndTheGameGoesOnOnTopOfIt() {
        db.tx(c -> {
            db.update(c, "TRUNCATE TABLE game_events, players, round_results, player_round_history, withdrawal_requests, "
                    + "deposit_requests, auth_challenges, sessions, oracle_records, player_deposit_addresses, deposit_confirmations, "
                    + "price_snapshots, round_venues, feedback, registration_ips, ledger_entries, engine_snapshots, player_sidecar RESTART IDENTITY CASCADE");
            db.update(c, "DELETE FROM markets");
            return null;
        });
        load("markets", "markets.json");
        load("players", "players.json");
        load("game_events", "game_events.json");
        journal.reimportFromTableForTests(); // Р-107: the fixture is the table of an old server; the first start imports it into the journal
        load("round_results", "round_results.json");
        load("player_round_history", "player_round_history.json");
        load("oracle_records", "oracle_records.json");
        db.tx(c -> db.queryOne(c, "SELECT setval(pg_get_serial_sequence('game_events', 'id'), (SELECT MAX(id) FROM game_events)) AS v",
                rs -> rs.getLong("v")));
        for (String line : read("meta.txt").split("\n")) {
            String[] kv = line.split("=");
            db.tx(c -> db.update(c, "INSERT INTO server_meta (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value",
                    kv[0], kv[1]));
        }
        long fixtureRows = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events", rs -> rs.getLong("n")));

        // the full replay of the old journal, then a snapshot of it, then a restart from that snapshot alone
        rebuild.rebuild();
        assertThat(rebuild.lastRebuild().snapshotSeq()).isZero();
        String fullDigest = StateDigest.of(hub, betFeed);
        EngineSnapshotRepository.Meta taken = snapshots.take(System.currentTimeMillis()).join();
        rebuild.rebuild();

        assertThat(rebuild.lastRebuild().snapshotSeq()).isEqualTo(taken.seq());
        assertThat(rebuild.lastRebuild().eventsReplayed()).isZero();
        assertThat(StateDigest.of(hub, betFeed)).isEqualTo(fullDigest);
        assertThat(crowdvsmarket.server.testsupport.StateDigest.engine(hub)).as("the old server's own digest of its state")
                .isEqualTo(read("engine.txt"));
        assertThat(snapshots.check(System.currentTimeMillis()).ok()).as("the check on a journal of version 1").isTrue();
        assertThat(snapshots.status().lastCheck().note()).contains("schema version 1");

        // the game goes on in version 2 rows
        String newcomer = signUp("v1snap");
        gameService.setNickname(newcomer, "After snapshot");
        String live = StateDigest.of(hub, betFeed);
        assertThat(live).isNotEqualTo(fullDigest);

        rebuild.rebuild();

        assertThat(rebuild.lastRebuild().snapshotSeq()).isEqualTo(taken.seq());
        assertThat((long) rebuild.lastRebuild().eventsReplayed()).isEqualTo(
                db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events WHERE id > ?", rs -> rs.getLong("n"), fixtureRows)));
        assertThat(StateDigest.of(hub, betFeed)).as("snapshot + tail == live").isEqualTo(live);
        assertThat(hub.roster().find(newcomer).orElseThrow().nickname()).isEqualTo("After snapshot");
        assertThat(hub.roster().all()).as("the players of the old journal come from the table, the new one from the journal")
                .hasSize(players().size());

        db.tx(c -> db.update(c, "DELETE FROM engine_snapshots"));
        rebuild.rebuild();

        assertThat(rebuild.lastRebuild().snapshotSeq()).isZero();
        assertThat(StateDigest.of(hub, betFeed)).as("full replay == live").isEqualTo(live);
    }

    private java.util.List<?> players() {
        return java.util.List.copyOf(db.read(c -> db.queryList(c, "SELECT player_id FROM players", rs -> rs.getString("player_id"))));
    }
}
