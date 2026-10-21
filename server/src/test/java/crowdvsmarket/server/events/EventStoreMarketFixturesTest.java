package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.server.persistence.Db;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Event compatibility for markets (docs/research/markets_architecture.md §6, Т-13), the two halves:
 * <ul>
 *   <li>the golden fixtures that were committed before markets existed ({@code events/golden},
 *       untouched — {@link EventStoreGoldenFixturesTest} keeps asserting them field for field) have no
 *       {@code market} key, and must decode to BTC events;
 *   <li>fixtures that carry a {@code market} ({@code events/golden-markets}, a directory of their own
 *       so that "one fixture per event type" in the first directory stays true) decode to that market,
 *       through the same Postgres/JSONB round trip {@link EventStore#loadAll} uses at every startup.
 * </ul>
 * {@code EventStore.CURRENT_SCHEMA_VERSION} was 1 when this was written (F4 made it 2; these rows are version 1 and decode as ever) for both.
 */
@QuarkusTest
class EventStoreMarketFixturesTest {

    private static final Path OLD_FIXTURES = Path.of("src/test/resources/events/golden");
    private static final Path MARKET_FIXTURES = Path.of("src/test/resources/events/golden-markets");

    @Inject
    Db db;
    @Inject
    EventStore eventStore;

    private record Fixture(String typeName, GameEvent expected) {
    }

    static Stream<Fixture> marketFixtures() {
        return Stream.of(
                new Fixture("BetPlaced", new GameEvent.BetPlaced("p-golden-1", Interval.HOURLY, 1700000000000L,
                        1700000180000L, Direction.DOWN, 20, 300000L, 1700000000500L, "xyz_NVDA")),
                new Fixture("BetCancelled", new GameEvent.BetCancelled("p-golden-1", Interval.DAILY, 1700000000000L,
                        1700000900000L, 1700000001000L, "PM540816")),
                new Fixture("RoundResolved", new GameEvent.RoundResolved(Interval.HOURLY, 1700000000000L,
                        1700000180000L, "4000.00", "4400.00",
                        List.of(new GameEvent.CandleData(1700000000000L, 1700000179999L, "4000.00", "4400.00",
                                "3950.00", "4400.00", "0", 0)),
                        1700000180000L, "PM540816")),
                new Fixture("RoundCancelled", new GameEvent.RoundCancelled(Interval.HOURLY, 1700000000000L,
                        1700000180000L, "market closed: source closed it", 1700000180000L, "ETH")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("marketFixtures")
    void aMarketBearingFixtureDecodesToThatMarketThroughTheRealEventStore(Fixture fixture) throws Exception {
        String fixtureJson = read(MARKET_FIXTURES, fixture.typeName());

        GameEvent decodedDirectly = eventStore.decode(-1L, fixture.typeName(), fixtureJson, 1);
        assertThat(decodedDirectly).isEqualTo(fixture.expected());

        Object node = db.json().readTree(fixtureJson);
        long rowId = db.tx(c -> db.queryOne(c,
                "INSERT INTO game_events (id, event_type, payload, at_ms, schema_version) VALUES (4000000000000000000 + nextval('game_events_id_seq'), ?, ?, ?, 1) "
                        + "RETURNING id",
                rs -> rs.getLong("id"), fixture.typeName(), Db.jsonb(node), 0L));
        try {
            GameEvent replayed = db.read(c -> db.queryOne(c,
                    "SELECT id, event_type, payload, schema_version FROM game_events WHERE id = ?",
                    rs -> eventStore.decode(rs.getLong("id"), rs.getString("event_type"), rs.getString("payload"),
                            rs.getInt("schema_version")),
                    rowId));
            assertThat(replayed).isEqualTo(fixture.expected());
        } finally {
            // same reason as EventStoreGoldenFixturesTest: a committed row would be replayed by every
            // later EngineRebuildService.rebuild() in this suite — and it names a market no engine has
            db.tx(c -> db.update(c, "DELETE FROM game_events WHERE id = ?", rowId));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("oldFixtureTypes")
    void aFixtureFromBeforeMarketsDecodesToBtc(String typeName) throws Exception {
        String fixtureJson = read(OLD_FIXTURES, typeName);
        assertThat(db.json().readTree(fixtureJson).has("market")).as("fixture predates the field").isFalse();

        GameEvent decoded = eventStore.decode(-1L, typeName, fixtureJson, 1);

        assertThat(marketOf(decoded)).isEqualTo("BTC");
    }

    static Stream<String> oldFixtureTypes() {
        return Stream.of("BetPlaced", "BetCancelled", "RoundResolved", "RoundCancelled");
    }

    @Test
    void everyBtcEventWritesItsMarketAndReadsBackIdentically() throws Exception {
        List<GameEvent> events = List.of(
                new GameEvent.BetPlaced("p", Interval.HOURLY, 1L, 2L, Direction.UP, 2, 100000L, 1L),
                new GameEvent.BetCancelled("p", Interval.HOURLY, 1L, 2L, 1L),
                new GameEvent.RoundResolved(Interval.HOURLY, 1L, 2L, "1.00", "1.00", List.of(), 1L),
                new GameEvent.RoundCancelled(Interval.HOURLY, 1L, 2L, "r", 1L));

        for (GameEvent event : events) {
            String json = db.json().writeValueAsString(event);
            assertThat(json).contains("\"market\":\"BTC\"");
            assertThat(eventStore.decode(-1L, event.getClass().getSimpleName(), json, 1)).isEqualTo(event);
        }
    }

    @Test
    void theCurrentSchemaVersionIsTwoAndTheseRowsAreVersionOne() {
        // markets are an additive, defaulted field: no schema bump (Т-13)
        assertThat(EventStore.CURRENT_SCHEMA_VERSION).isEqualTo(2); // F4; the shape above is a version-1 row and still decodes
    }

    private static String marketOf(GameEvent event) {
        return switch (event) {
            case GameEvent.BetPlaced e -> e.market();
            case GameEvent.BetCancelled e -> e.market();
            case GameEvent.RoundResolved e -> e.market();
            case GameEvent.RoundCancelled e -> e.market();
            default -> throw new AssertionError("not a round event: " + event);
        };
    }

    private static String read(Path dir, String typeName) {
        try {
            return Files.readString(dir.resolve(typeName + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException("missing fixture " + dir.resolve(typeName + ".json"), e);
        }
    }
}
