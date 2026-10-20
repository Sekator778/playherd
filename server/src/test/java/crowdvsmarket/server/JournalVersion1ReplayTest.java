package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.StateDigest;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Architecture plan F4: a journal written by the server <b>before</b> F4 (schema version 1) replays to the same state.
 *
 * <p>The fixture ({@code src/test/resources/events/journal-v1/}) was produced by the code of the commit before F4: a game played
 * through the real service (people, a house bot, an API bot, nicknames, a strategy, two markets one of them closed, rounds settled,
 * a bet left open) and then dumped - the whole {@code game_events} table (every row {@code schema_version = 1}), the tables the
 * server wrote beside it ({@code players}, {@code markets}, {@code round_results}, ...), the two counters of {@code server_meta},
 * and {@code engine.sha256}, the digest of the engine state the old server had in memory ({@link StateDigest#engine}). Here the
 * database is set to exactly that dump and the server rebuilds from it, as it does at a restart.
 *
 * <p>What it proves: the engine state of the rebuild is the old server's, bit for bit (same digest); the {@code players} and
 * {@code markets} tables are left as they were (no F4 projection touches a player or market the journal does not describe); the
 * oracle counter is the old one; and the journal still decodes as version 1 (no row is rewritten).
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class JournalVersion1ReplayTest extends LiveGameTestBase {

    private static final Path DIR = Path.of("src/test/resources/events/journal-v1");

    @Inject
    PlayerDirectory players;
    @Inject
    crowdvsmarket.server.events.EventStore eventStore;

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
    void aJournalOfSchemaVersionOneReplaysToTheStateTheOldServerHad() {
        db.tx(c -> {
            db.update(c, "TRUNCATE TABLE game_events, players, round_results, player_round_history, withdrawal_requests, "
                    + "deposit_requests, auth_challenges, sessions, oracle_records, player_deposit_addresses, deposit_confirmations, "
                    + "price_snapshots, round_venues, feedback, registration_ips, ledger_entries RESTART IDENTITY CASCADE");
            db.update(c, "DELETE FROM markets");
            return null;
        });
        load("markets", "markets.json");
        load("players", "players.json");
        load("game_events", "game_events.json");
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
        Long notVersionOne = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events WHERE schema_version <> 1",
                rs -> rs.getLong("n")));
        assertThat(notVersionOne).as("the fixture is a journal of schema version 1 only").isZero();
        long events = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events", rs -> rs.getLong("n")));
        assertThat(events).isGreaterThan(15);

        rebuild.rebuild();

        assertThat(StateDigest.engine(hub)).isEqualTo(read("engine.txt"));
        assertThat(StateDigest.sha256(StateDigest.engine(hub))).isEqualTo(read("engine.sha256").strip());
        // nothing F4 projects touched a player or a market the journal does not describe, and the old counters are the counters
        assertThat(StateDigest.projections(db, hub).replace(" hub.oracleSeq=" + hub.oracleSeq(), ""))
                .isEqualTo(read("projections.txt"));
        assertThat(hub.oracleSeq()).isEqualTo(Long.parseLong(read("meta.txt").lines().filter(l -> l.startsWith("oracle_seq="))
                .findFirst().orElseThrow().substring("oracle_seq=".length()).strip()));
        // the rows are still version 1, and the new events are written as version 2 from here on
        Long rewritten = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events WHERE id <= ? AND schema_version <> 1",
                rs -> rs.getLong("n"), events));
        assertThat(rewritten).isZero();
        // the roster of the replay was seeded from the table (a version-1 PlayerRegistered carries no player)
        assertThat(hub.roster().all()).hasSize(players.all().size());
        assertThat(hub.roster().all()).anySatisfy(e -> assertThat(e.displayName()).isEqualTo("Alice V1"));
        assertThat(hub.roster().all()).filteredOn(e -> e.displayName().startsWith("Bot V1")).singleElement()
                .satisfies(e -> assertThat(e.displaysAsBot()).as("a house bot of the old server, kind HUMAN in its table row").isTrue());
        // a game goes on from here: a new player, a bet, and the journal grows in version 2
        String newcomer = signUp("v1later");
        assertThat(newcomer).isNotBlank();
        Integer newest = db.read(c -> db.queryOne(c, "SELECT MAX(schema_version) AS v FROM game_events", rs -> rs.getInt("v")));
        assertThat(newest).isEqualTo(2);
        assertThat(eventStore.loadAll().subList((int) events, eventStore.loadAll().size())).anySatisfy(
                e -> assertThat(e).isInstanceOfSatisfying(GameEvent.PlayerRegistered.class, r -> assertThat(r.isRegistration()).isTrue()));
    }
}
