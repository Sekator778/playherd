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
 * Task item 4 (event schema evolution, an internal security review): a golden
 * JSON fixture for EVERY current {@link GameEvent} type (server/src/test/resources/events/golden/
 * &lt;TypeName&gt;.json), each exercised with a round-trip (fixture JSON → {@link GameEvent}
 * record, compared field-for-field against an independently hand-built expected instance) AND a
 * replay (the fixture is actually persisted into a real {@code game_events} row — the same
 * Postgres/JSONB round trip {@link EventStore#loadAll} uses at every real startup — then read back
 * through {@link EventStore#decode} itself, not a reimplementation of it). A future change to any
 * {@link GameEvent} record's shape that breaks decoding an old fixture fails exactly one of these,
 * naming the type, instead of only surfacing as an opaque startup crash against real historical
 * data.
 */
@QuarkusTest
class EventStoreGoldenFixturesTest {

    private static final Path FIXTURES_DIR =
            Path.of("src/test/resources/events/golden");

    @Inject
    Db db;

    @Inject
    EventStore eventStore;

    /** {@code version}: the {@code schema_version} the fixture row is stored with (the four types born in F4 are version 2). */
    private record Fixture(String typeName, GameEvent expected, int version) {
        Fixture(String typeName, GameEvent expected) {
            this(typeName, expected, 1);
        }
    }

    static Stream<Fixture> fixtures() {
        return Stream.of(
                new Fixture("PlayerRegistered", new GameEvent.PlayerRegistered("p-golden-1", 1700000000000L)),
                new Fixture("BetPlaced", new GameEvent.BetPlaced("p-golden-1", Interval.HOURLY, 1700000000000L,
                        1700000180000L, Direction.UP, 10, 100000L, 1700000000500L)),
                new Fixture("BetCancelled", new GameEvent.BetCancelled("p-golden-1", Interval.HOURLY,
                        1700000000000L, 1700000180000L, 1700000001000L)),
                new Fixture("RoundResolved", new GameEvent.RoundResolved(Interval.HOURLY, 1700000000000L,
                        1700000180000L, "85000.00", "85100.00",
                        List.of(new GameEvent.CandleData(1700000000000L, 1700000059999L, "85000.00", "85050.00",
                                "84990.00", "85040.00", "1.23456", 42)),
                        1700000180000L)),
                new Fixture("RoundCancelled", new GameEvent.RoundCancelled(Interval.HOURLY, 1700000000000L,
                        1700000180000L, "golden fixture test cancel", 1700000180000L)),
                new Fixture("WithdrawalRequested",
                        new GameEvent.WithdrawalRequested("p-golden-1", 500000L, 1700000002000L)),
                new Fixture("WithdrawalConfirmed", new GameEvent.WithdrawalConfirmed("w-golden-1", 1700000002500L)),
                new Fixture("WithdrawalFailed", new GameEvent.WithdrawalFailed("w-golden-2", 1700000003000L)),
                new Fixture("DepositRequested", new GameEvent.DepositRequested("p-golden-1", 250000L, 1700000004000L)),
                new Fixture("DepositConfirmed", new GameEvent.DepositConfirmed("d-golden-1", 1700000004500L)),
                new Fixture("DepositFailed", new GameEvent.DepositFailed("d-golden-2", 1700000005000L)),
                new Fixture("WithdrawalReversed", new GameEvent.WithdrawalReversed("p-golden-1", 500000L, "2026-09",
                        "golden fixture reversal", "w-golden-1", 1700000006000L)),
                new Fixture("DepositReversed", new GameEvent.DepositReversed("p-golden-1", 250000L, "2026-09",
                        "golden fixture reversal", "d-golden-1", 1700000007000L)),
                new Fixture("DepositConfirmedOnChain", new GameEvent.DepositConfirmedOnChain("p-golden-1", 7,
                        250000L, "0xgoldencoin1", 4735705L, 1700000008000L)),
                new Fixture("WithdrawalConfirmedOnChain", new GameEvent.WithdrawalConfirmedOnChain("w-golden-3",
                        "0xgoldencoin2", 4735706L, 1700000009000L)),
                new Fixture("EconomyCutover", new GameEvent.EconomyCutover(99_998_100_000L, 1700000010000L)),
                new Fixture("BotRetired", new GameEvent.BotRetired("bot-golden-1", 1700000011000L)),
                new Fixture("HerdSweptToTreasury", new GameEvent.HerdSweptToTreasury(2100L, 1700000012000L)),
                new Fixture("NicknameChanged", new GameEvent.NicknameChanged("p-golden-1", "Golden Name", 1700000013000L), 2),
                new Fixture("BotStrategyAssigned", new GameEvent.BotStrategyAssigned("bot-golden-1", "trend", 1700000014000L), 2),
                new Fixture("MarketListed", new GameEvent.MarketListed("GOLD", "PERP", "crypto", "GOLD", "Golden market", null,
                        null, "synthetic", "GOLD", "{\"k\": 1}", 25, 1, 7, 1700000015000L, null, 600, 300, "0.1", "1.0",
                        "0.016"), 2),
                new Fixture("MarketClosed", new GameEvent.MarketClosed("GOLD", "golden fixture close", 1700000016000L), 2));
    }

    /** Every {@link GameEvent} record must have exactly one fixture above — catches "added a new
     *  event type, forgot to golden-fixture-test it" as a hard failure, not a silent gap. */
    @Test
    void everyGameEventPermittedSubtypeHasAFixture() {
        List<String> declaredTypes = Stream.of(GameEvent.class.getPermittedSubclasses())
                .map(Class::getSimpleName).sorted().toList();
        List<String> fixturedTypes = fixtures().map(Fixture::typeName).sorted().toList();
        assertThat(fixturedTypes).as("add a golden fixture + Fixture entry for every GameEvent subtype")
                .isEqualTo(declaredTypes);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void roundTripsAndReplaysThroughTheRealEventStore(Fixture fixture) throws Exception {
        String fixtureJson = readFixture(fixture.typeName());

        // Round trip: the committed fixture JSON decodes to exactly the expected GameEvent.
        GameEvent decodedDirectly = eventStore.decode(-1L, fixture.typeName(), fixtureJson, fixture.version());
        assertThat(decodedDirectly).isEqualTo(fixture.expected());

        // Replay: the exact same fixture, persisted as a real game_events row (JSONB round trip
        // included) and read back through EventStore.decode via that row's own id — the same path
        // EventStore#loadAll uses for every historical row at real startup.
        // at_ms is the row's own column (used for e.g. admin listings elsewhere) — irrelevant to
        // this test, which only exercises decode()'s reading of event_type/payload/schema_version.
        Object node = db.json().readTree(fixtureJson);
        long rowId = db.tx(c -> db.queryOne(c,
                "INSERT INTO game_events (id, event_type, payload, at_ms, schema_version) VALUES (4000000000000000000 + nextval('game_events_id_seq'), ?, ?, ?, ?) "
                        + "RETURNING id",
                rs -> rs.getLong("id"), fixture.typeName(), Db.jsonb(node), 0L, fixture.version()));
        try {
            GameEvent replayed = db.read(c -> db.queryOne(c,
                    "SELECT id, event_type, payload, schema_version FROM game_events WHERE id = ?",
                    rs -> eventStore.decode(rs.getLong("id"), rs.getString("event_type"), rs.getString("payload"),
                            rs.getInt("schema_version")),
                    rowId));
            assertThat(replayed).isEqualTo(fixture.expected());
        } finally {
            // CI regression (docs/research/server_design.md "EventStoreGoldenFixturesTest / shared
            // game_events"): this INSERT above is a real, manually-committed JDBC transaction (Db#tx
            // uses its own connection/commit, not JTA), so it is NOT rolled back when the test method
            // ends — it stays in game_events for the rest of the suite's shared Postgres/JVM. Several
            // other @QuarkusTest classes (AdminReverseWithdrawalTest, AdminReverseDepositTest,
            // BotRestartRecoveryTest, GameFlowIntegrationTest, UnseenResultsTest, PlayOnChainTest)
            // call EngineRebuildService#rebuild(), which replays every row currently in game_events
            // into a fresh engine. These fixture rows use synthetic ids ("w-golden-1"/"d-golden-1"/
            // …) chosen purely as decode-test labels — never actually produced by a matching
            // WithdrawalRequested/DepositRequested in this same replay sequence — so whichever of
            // those test classes happens to run afterward (purely a function of surefire's
            // classpath-scan order, which is why this passed locally but failed "consistently" on
            // CI's runner) replayed e.g. WithdrawalConfirmed("w-golden-1") against a ledger that
            // never created that id, and blew up with exactly
            // NoSuchElementException: unknown withdrawal request: w-golden-1. Deleting the row here
            // keeps this test's real Postgres/JSONB round-trip coverage while leaving game_events
            // exactly as this test found it, regardless of what else runs in the suite or in what
            // order.
            db.tx(c -> db.update(c, "DELETE FROM game_events WHERE id = ?", rowId));
        }
    }

    private static String readFixture(String typeName) {
        try {
            return Files.readString(FIXTURES_DIR.resolve(typeName + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException("missing golden fixture for " + typeName, e);
        }
    }
}
