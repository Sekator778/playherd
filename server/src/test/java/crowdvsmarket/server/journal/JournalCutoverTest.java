package crowdvsmarket.server.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.testsupport.CleanDatabasePerTestClass;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The move of the journal from the table to the queue (Р-107): on the first start with an empty journal directory the rows of
 * {@code game_events} are imported with their ids and payloads, verified by count and SHA-256, and only then is the queue the journal.
 * Rows of schema version 1 and 2, all the golden fixtures, gaps in the ids, more rows than one excerpt of the import holds; a crash in the
 * middle; a table that is ahead of the journal.
 */
@QuarkusTest
class JournalCutoverTest {

    private static final Path GOLDEN = Path.of("src/test/resources/events/golden");
    /** The types born in schema version 2 (F4); every other golden fixture is a row of version 1. */
    private static final List<String> VERSION_2 = List.of("NicknameChanged", "BotStrategyAssigned", "MarketListed", "MarketClosed");

    @Inject
    Db db;
    @Inject
    Journal journal;
    @Inject
    EventStore eventStore;
    @Inject
    JournalProjector projector;

    @BeforeEach
    void empty() {
        CleanDatabasePerTestClass.resetDatabaseAndMemory();
        journal.cutoverHook(null);
    }

    @AfterEach
    void clean() {
        journal.cutoverHook(null);
        CleanDatabasePerTestClass.resetDatabaseAndMemory();
    }

    /** The table of an old server: every golden fixture as a row (ids with gaps, versions 1 and 2) and 3 000 more rows, more than one excerpt of the import. */
    private int fillTable() throws IOException {
        // what the reset's own bootstrap wrote (the economy's first event, bots) is dropped: the table is the one of an old server
        journal.resetForTests();
        db.tx(c -> db.update(c, "TRUNCATE TABLE game_events"));
        int rows = 0;
        long id = 10;
        try (var files = Files.list(GOLDEN)) {
            for (Path file : files.sorted().toList()) {
                String type = file.getFileName().toString().replace(".json", "");
                int version = VERSION_2.contains(type) ? 2 : 1;
                String json = Files.readString(file);
                long rowId = id;
                db.tx(c -> db.update(c, "INSERT INTO game_events (id, event_type, payload, at_ms, schema_version, market, player_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", rowId, type, Db.jsonText(json), 1_700_000_000_000L + rowId, version,
                        EventStore.marketOf(eventStore.decodeForTests(rowId, type, json, version)),
                        EventStore.playerOf(eventStore.decodeForTests(rowId, type, json, version))));
                id += 3; // a gap after every row: ids of a table that has rolled back inserts
                rows++;
            }
        }
        long from = id + 100;
        db.tx(c -> db.update(c, "INSERT INTO game_events (id, event_type, payload, at_ms, schema_version, market, player_id) "
                + "SELECT ? + i * 2, 'BetPlaced', jsonb_build_object('n', i, 'text', repeat('é中', 40), 'x', 1.50 * i, 'big', 1e30), "
                + "i, CASE WHEN i % 2 = 0 THEN 1 ELSE 2 END, CASE WHEN i % 3 = 0 THEN NULL ELSE 'BTC' END, 'p' || i "
                + "FROM generate_series(1, 3000) AS i", from));
        return rows + 3000;
    }

    private List<Object[]> tableRows() {
        return db.read(c -> db.queryList(c, "SELECT id, event_type, payload::text AS p, at_ms, schema_version, market, player_id FROM game_events ORDER BY id",
                rs -> new Object[] {rs.getLong("id"), rs.getString("event_type"), rs.getString("p"), rs.getLong("at_ms"),
                        rs.getInt("schema_version"), rs.getString("market"), rs.getString("player_id")}));
    }

    @Test
    void everyRowOfTheTableIsInTheJournalWithItsIdAndItsBytes() throws Exception {
        int expected = fillTable();

        JournalCutover.Result result = journal.reimportFromTableForTests();

        List<Object[]> table = tableRows();
        assertThat(table).hasSize(expected);
        assertThat(result.rows()).isEqualTo(expected);
        assertThat(result.lastId()).isEqualTo((Long) table.get(table.size() - 1)[0]);
        List<JournalRecord> records = new ArrayList<>();
        journal.store().replay(0, Long.MAX_VALUE, records::add);
        assertThat(records).hasSize(expected);
        JournalCutover.Digest again = new JournalCutover.Digest();
        for (int i = 0; i < expected; i++) {
            Object[] row = table.get(i);
            JournalRecord r = records.get(i);
            assertThat(r.seq()).as("id of row %d", i).isEqualTo(row[0]);
            assertThat(r.type()).isEqualTo(row[1]);
            assertThat(r.payload()).as("payload bytes of %d", r.seq()).isEqualTo(row[2]);
            assertThat(r.atMs()).isEqualTo(row[3]);
            assertThat(r.schemaVersion()).isEqualTo(row[4]);
            assertThat(r.market()).isEqualTo(row[5]);
            assertThat(r.playerId()).isEqualTo(row[6]);
            again.add(r);
        }
        assertThat(again.hex()).as("the digest the import verified").isEqualTo(result.sha256());
        // the golden fixtures decode from the journal exactly as they decode from the table
        for (JournalRecord r : records) {
            if (r.seq() < 10_000) {
                GameEvent fromJournal = eventStore.decode(r);
                Object[] row = table.stream().filter(t -> t[0].equals(r.seq())).findFirst().orElseThrow();
                assertThat(fromJournal).isEqualTo(eventStore.decodeForTests((Long) row[0], (String) row[1], (String) row[2], (Integer) row[4]));
            }
        }
        assertThat(records.stream().map(JournalRecord::schemaVersion).distinct()).contains(1, 2);
    }

    @Test
    void aCrashInTheMiddleOfTheImportIsRedoneFromTheUntouchedTable() throws Exception {
        int expected = fillTable();
        List<Object[]> before = tableRows();
        journal.closeForTests();
        // the directory is empty (the reset above): the next use is the first start after the move
        journal.cutoverHook(rows -> {
            if (rows >= JournalCutover.CHUNK) {
                throw new IllegalStateException("injected: the process died in the middle of the import");
            }
        });

        assertThatThrownBy(() -> journal.store()).hasMessageContaining("died in the middle of the import");
        assertThat(Files.exists(journal.importMarkerForTests())).as("the marker says the import did not finish").isTrue();

        journal.cutoverHook(null);
        long lastSeq = journal.lastSeq(); // a start after the crash: the marker is found, the half-written queue wiped, the import runs again
        assertThat(Files.exists(journal.importMarkerForTests())).isFalse();
        assertThat(journal.cutover()).isNotNull();
        assertThat(journal.cutover().rows()).isEqualTo(expected);
        assertThat(lastSeq).isEqualTo(journal.cutover().lastId());
        List<JournalRecord> records = new ArrayList<>();
        journal.store().replay(0, Long.MAX_VALUE, records::add);
        assertThat(records).as("each row once: not the half import plus the whole one").hasSize(expected);
        assertThat(tableRows()).as("the table was not touched").hasSize(before.size());
        for (int i = 0; i < expected; i++) {
            assertThat(records.get(i).seq()).isEqualTo(before.get(i)[0]);
            assertThat(records.get(i).payload()).isEqualTo(before.get(i)[2]);
        }
    }

    @Test
    void theStartAfterTheMoveDoesNotImportAgain() throws Exception {
        fillTable();
        journal.reimportFromTableForTests();
        long last = journal.lastSeq();
        journal.closeForTests();

        assertThat(journal.lastSeq()).as("the journal is not empty: it is the truth, the table is not read again").isEqualTo(last);
        assertThat(journal.cutover()).as("no import on this start").isNull();
    }

    /** A measurement, not a gate: {@code -Dherd.test.cutover-rows=350000} imports a table of the size of the live stand's and prints the time. */
    @Test
    void theImportOfALargeTableIsTimed() {
        int rows = Integer.getInteger("herd.test.cutover-rows", 0);
        org.junit.jupiter.api.Assumptions.assumeTrue(rows > 0, "measurement: run with -Dherd.test.cutover-rows=350000");
        journal.resetForTests();
        db.tx(c -> db.update(c, "TRUNCATE TABLE game_events"));
        db.tx(c -> db.update(c, "INSERT INTO game_events (id, event_type, payload, at_ms, schema_version, market, player_id) "
                + "SELECT i, 'BetPlaced', jsonb_build_object('playerId', 'player-' || (i % 5000), 'interval', 'HOURLY', 'windowStart', 1700000000000, "
                + "'windowEnd', 1700003600000, 'direction', 'UP', 'leverage', 5, 'stakeMojos', 100000, 'atMs', 1700000000000 + i, 'market', 'BTC', "
                + "'pad', repeat('x', 150)), 1700000000000 + i, 2, 'BTC', 'player-' || (i % 5000) FROM generate_series(1, ?) AS i", rows));

        long t0 = System.nanoTime();
        JournalCutover.Result result = journal.reimportFromTableForTests();
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println("CUTOVER-TIME rows=" + result.rows() + " total_ms=" + ms + " import_ms=" + result.tookMs() + " sha256=" + result.sha256());
        assertThat(result.rows()).isEqualTo(rows);
    }

    @Test
    void aTableAheadOfTheJournalIsRefused() {
        journal.append(List.of(new JournalRecord(0, "BotRetired", 2, 1, null, "bot-x", "{\"playerId\":\"bot-x\",\"atMs\":1}")));
        db.tx(c -> db.update(c, "INSERT INTO game_events (id, event_type, payload, at_ms, schema_version) VALUES (5000, 'BotRetired', "
                + "'{\"playerId\":\"bot-y\",\"atMs\":2}'::jsonb, 2, 2)"));
        journal.closeForTests();

        assertThatThrownBy(() -> journal.store()).isInstanceOf(IllegalStateException.class).hasMessageContaining("behind its copy");
    }
}
