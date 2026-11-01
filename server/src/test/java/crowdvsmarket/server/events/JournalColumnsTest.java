package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.server.persistence.Db;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The {@code market} / {@code player_id} columns of {@code game_events} (V20): filled by both append paths, the payload next to
 * them unchanged, the rows of an old journal filled by the migration's own UPDATE statements and readable before as after, and the
 * two indexes there.
 */
@QuarkusTest
class JournalColumnsTest {

    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V20__game_events_market_player.sql");

    @Inject
    Db db;
    @Inject
    EventStore eventStore;
    @Inject
    crowdvsmarket.server.journal.Journal journal;

    private record Row(long id, String type, String market, String player, JsonNode payload) {
    }

    private GameEvent.BetPlaced bet(String player, String market, long n) {
        return new GameEvent.BetPlaced(player, Interval.HOURLY, 1_000L * n, 1_000L * n + 500, Direction.UP, 2, 5L, n, market,
                null, null, null, null, null);
    }

    private List<Row> rows(long afterId) {
        return db.read(c -> db.queryList(c,
                "SELECT id, event_type, market, player_id, payload FROM game_events WHERE id > ? ORDER BY id",
                rs -> new Row(rs.getLong("id"), rs.getString("event_type"), rs.getString("market"), rs.getString("player_id"),
                        parse(rs.getString("payload"))), afterId));
    }

    private JsonNode parse(String json) {
        try {
            return db.json().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The tree of the event's JSON text, as the database hands it back (numbers of the same kind on both sides). */
    private JsonNode tree(GameEvent event) {
        try {
            return db.json().readTree(db.json().writeValueAsString(event));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void deleteAfter(long id) {
        db.tx(c -> db.update(c, "DELETE FROM game_events WHERE id > ?", id));
    }

    @Test
    void bothAppendPathsFillTheColumnsAndLeaveThePayloadAlone() {
        long before = eventStore.lastId();
        try {
            List<GameEvent> events = new ArrayList<>();
            events.add(bet("jc-p1", "spot_ETH", 1));
            events.add(new GameEvent.RoundCancelled(Interval.DAILY, 5L, 9L, "test", 7L, "spot_ETH"));
            events.add(new GameEvent.WithdrawalConfirmed("w-1", 8L));
            events.add(new GameEvent.BotRetired("jc-bot", 9L));
            // one event: append() ...
            db.tx(c -> {
                eventStore.append(c, events.get(0), 1L);
                return null;
            });
            // ... several, more than one INSERT statement of appendAll
            List<EventStore.Pending> pending = new ArrayList<>();
            for (int i = 0; i < 205; i++) {
                pending.add(new EventStore.Pending(events.get(1 + i % 3), 2L));
            }
            eventStore.appendAll(pending);

            List<Row> rows = rows(before);
            assertThat(rows).hasSize(1 + pending.size());
            Row first = rows.get(0);
            assertThat(first.market()).isEqualTo("spot_ETH");
            assertThat(first.player()).isEqualTo("jc-p1");
            assertThat(first.payload()).isEqualTo(tree(events.get(0)));
            for (int i = 0; i < pending.size(); i++) {
                GameEvent e = pending.get(i).event();
                Row r = rows.get(1 + i);
                assertThat(r.type()).isEqualTo(e.getClass().getSimpleName());
                assertThat(r.market()).as("market of row %d", r.id()).isEqualTo(EventStore.marketOf(e));
                assertThat(r.player()).as("player of row %d", r.id()).isEqualTo(EventStore.playerOf(e));
                assertThat(r.payload()).isEqualTo(tree(e));
            }
            assertThat(rows.get(1).market()).isEqualTo("spot_ETH");
            assertThat(rows.get(1).player()).isNull();
            assertThat(rows.get(2).market()).isNull();
            assertThat(rows.get(3).player()).isEqualTo("jc-bot");
        } finally {
            // D-107: the events are in the journal, which cannot be trimmed; this class starts with an empty journal and table
            // (CleanDatabasePerTestClass) and leaves both empty, so no later rebuild replays a bet of a player nobody registered
            journal.resetForTests();
            db.tx(c -> db.update(c, "DELETE FROM game_events"));
        }
    }

    @Test
    void theMigrationBackfillFillsOldRowsAndTheOldRowsStayReadable() throws Exception {
        long before = eventStore.lastId();
        try {
            // rows as the journal had them before V20: columns NULL, a bet before markets without a market key
            ObjectNode oldBet = (ObjectNode) db.json().valueToTree(bet("jc-old", "BTC", 3));
            oldBet.remove("market");
            insertOld("BetPlaced", oldBet);
            insertOld("RoundResolved", db.json().valueToTree(new GameEvent.RoundResolved(Interval.HOURLY, 1L, 2L, "1", "2",
                    List.of(), 3L, "spot_SOL")));
            insertOld("MarketListed", db.json().readTree("{\"marketId\":\"spot_XYZ\",\"kind\":\"spot\"}"));
            insertOld("WithdrawalConfirmed", db.json().valueToTree(new GameEvent.WithdrawalConfirmed("w-9", 4L)));
            insertOld("PlayerRegistered", db.json().valueToTree(new GameEvent.PlayerRegistered("jc-reg", 5L)));

            List<Row> raw = rows(before);
            assertThat(raw).extracting(Row::market).containsOnlyNulls();
            assertThat(raw).extracting(Row::player).containsOnlyNulls();

            backfill();

            List<Row> rows = rows(before);
            assertThat(rows).extracting(Row::market).containsExactly("BTC", "spot_SOL", "spot_XYZ", null, null);
            assertThat(rows).extracting(Row::player).containsExactly("jc-old", null, null, null, "jc-reg");
            assertThat(rows).extracting(Row::payload).isEqualTo(raw.stream().map(Row::payload).toList());

            backfill(); // idempotent: a second run changes nothing
            assertThat(rows(before)).isEqualTo(rows);
        } finally {
            deleteAfter(before);
        }
    }

    @Test
    void theIndexesExist() {
        List<String> names = db.read(c -> db.queryList(c,
                "SELECT indexname FROM pg_indexes WHERE tablename = 'game_events'", rs -> rs.getString("indexname")));
        assertThat(names).contains("game_events_market_idx", "game_events_player_idx");
    }

    private void insertOld(String type, JsonNode payload) {
        db.tx(c -> db.update(c, "INSERT INTO game_events (id, event_type, payload, at_ms, schema_version) VALUES (4000000000000000000 + nextval('game_events_id_seq'), ?, ?, 0, 1)", type,
                Db.jsonb(payload)));
    }

    /** The UPDATE statements of the migration, run as they are. */
    private void backfill() throws Exception {
        StringBuilder sql = new StringBuilder();
        for (String line : Files.readAllLines(MIGRATION)) {
            if (!line.startsWith("--")) {
                sql.append(line).append('\n');
            }
        }
        int run = 0;
        for (String statement : sql.toString().split(";")) {
            String s = statement.strip();
            if (s.startsWith("UPDATE")) {
                db.tx(c -> db.update(c, s));
                run++;
            }
        }
        assertThat(run).isEqualTo(3);
    }
}
