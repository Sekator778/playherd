package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.server.persistence.Db;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Event compatibility for venues (D-76, T-13): {@code RoundResolved} has one more optional trailing field, {@code venue},
 * the exchange a round of a market with a backup exchange ran on and was settled from.
 * <ul>
 *   <li>the fixtures committed before venues ({@code events/golden}, {@code events/golden-markets}) have no
 *       {@code venue} key and decode with {@code venue == null};
 *   <li>a fixture that carries one ({@code events/golden-venues}) decodes to it, through the same Postgres/JSONB round
 *       trip {@link EventStore#loadAll} uses at every startup;
 *   <li>the event of a round with no venue is written without the key — byte for byte what it always was.
 * </ul>
 * {@code EventStore.CURRENT_SCHEMA_VERSION} was 1 when this was written (F4 made it 2; these rows are version 1 and decode as ever).
 */
@QuarkusTest
class EventStoreVenueFixturesTest {

    private static final Path VENUE_FIXTURES = Path.of("src/test/resources/events/golden-venues");
    private static final Path MARKET_FIXTURES = Path.of("src/test/resources/events/golden-markets");
    private static final Path OLD_FIXTURES = Path.of("src/test/resources/events/golden");

    private static final GameEvent.RoundResolved ON_KRAKEN = new GameEvent.RoundResolved(Interval.HOURLY, 1700000000000L,
            1700000180000L, "64000.10", "64120.50",
            List.of(new GameEvent.CandleData(1700000000000L, 1700000179999L, "64000.10", "64150.00", "63990.00",
                    "64120.50", "0", 0)),
            1700000180000L, "spot_BTC", "kraken");

    @Inject
    Db db;
    @Inject
    EventStore eventStore;

    @Test
    void aFixtureWithAVenueDecodesToItThroughTheRealEventStore() throws Exception {
        String fixtureJson = read(VENUE_FIXTURES);

        GameEvent decodedDirectly = eventStore.decode(-1L, "RoundResolved", fixtureJson, 1);
        assertThat(decodedDirectly).isEqualTo(ON_KRAKEN);
        assertThat(((GameEvent.RoundResolved) decodedDirectly).venue()).isEqualTo("kraken");

        Object node = db.json().readTree(fixtureJson);
        long rowId = db.tx(c -> db.queryOne(c,
                "INSERT INTO game_events (id, event_type, payload, at_ms, schema_version) VALUES (4000000000000000000 + nextval('game_events_id_seq'), ?, ?, ?, 1) RETURNING id",
                rs -> rs.getLong("id"), "RoundResolved", Db.jsonb(node), 0L));
        try {
            GameEvent replayed = db.read(c -> db.queryOne(c,
                    "SELECT id, event_type, payload, schema_version FROM game_events WHERE id = ?",
                    rs -> eventStore.decode(rs.getLong("id"), rs.getString("event_type"), rs.getString("payload"),
                            rs.getInt("schema_version")),
                    rowId));
            assertThat(replayed).isEqualTo(ON_KRAKEN);
        } finally {
            // a committed row would be replayed by every later EngineRebuildService.rebuild() in this suite
            db.tx(c -> db.update(c, "DELETE FROM game_events WHERE id = ?", rowId));
        }
    }

    @Test
    void fixturesFromBeforeVenuesDecodeWithoutOne() throws Exception {
        for (Path dir : List.of(OLD_FIXTURES, MARKET_FIXTURES)) {
            String fixtureJson = Files.readString(dir.resolve("RoundResolved.json"));
            assertThat(db.json().readTree(fixtureJson).has("venue")).as(dir + " predates the field").isFalse();

            GameEvent decoded = eventStore.decode(-1L, "RoundResolved", fixtureJson, 1);

            assertThat(((GameEvent.RoundResolved) decoded).venue()).as(dir.toString()).isNull();
        }
    }

    @Test
    void theEventOfARoundWithNoVenueIsWrittenWithoutTheKey() throws Exception {
        GameEvent.RoundResolved none = new GameEvent.RoundResolved(Interval.HOURLY, 1L, 2L, "1.00", "1.00", List.of(), 1L,
                "ETH");

        String json = db.json().writeValueAsString(none);

        assertThat(json).doesNotContain("venue");
        assertThat(eventStore.decode(-1L, "RoundResolved", json, 1)).isEqualTo(none);
        // and the same event with the venue the other overload names is the one with the key
        GameEvent.RoundResolved withVenue = new GameEvent.RoundResolved(Interval.HOURLY, 1L, 2L, "1.00", "1.00",
                List.of(), 1L, "ETH", "coinbase");
        String jsonWithVenue = db.json().writeValueAsString(withVenue);
        assertThat(jsonWithVenue).contains("\"venue\":\"coinbase\"");
        assertThat(jsonWithVenue.replace(",\"venue\":\"coinbase\"", "")).isEqualTo(json);
        assertThat(eventStore.decode(-1L, "RoundResolved", jsonWithVenue, 1)).isEqualTo(withVenue);
    }

    @Test
    void theBtcOverloadsKeepTheirShapeAndHaveNoVenue() {
        GameEvent.RoundResolved preMarkets = new GameEvent.RoundResolved(Interval.HOURLY, 1L, 2L, "1.00", "1.00",
                List.of(), 1L);

        assertThat(preMarkets.market()).isEqualTo("BTC");
        assertThat(preMarkets.venue()).isNull();
    }

    @Test
    void theCurrentSchemaVersionIsTwoAndTheseRowsAreVersionOne() {
        assertThat(EventStore.CURRENT_SCHEMA_VERSION).isEqualTo(2); // F4; the shape above is a version-1 row and still decodes
    }

    private static String read(Path dir) {
        try {
            return Files.readString(dir.resolve("RoundResolved.json"));
        } catch (IOException e) {
            throw new UncheckedIOException("missing fixture " + dir.resolve("RoundResolved.json"), e);
        }
    }
}
