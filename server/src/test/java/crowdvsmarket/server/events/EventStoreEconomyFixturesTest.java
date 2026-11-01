package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.server.persistence.Db;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Event compatibility for the economy fees (D-92, plan A4): {@code BetPlaced} has two more optional trailing fields,
 * {@code feeHerdMojos} and {@code feeNetMojos}.
 * <ul>
 *   <li>the fixtures committed before the change ({@code events/golden}, {@code events/golden-markets}) have no fee keys and
 *       decode to a bet with {@code null} fees, which {@code EventApplier} places as a legacy bet (no fees, original rules);
 *   <li>a fixture that carries them ({@code events/golden-economy}) decodes to them;
 *   <li>the event of a legacy bet is written without the keys — byte for byte what it always was.
 * </ul>
 * {@code EventStore.CURRENT_SCHEMA_VERSION} was 1 when this was written (F4 made it 2; these rows are version 1 and decode as ever).
 */
@QuarkusTest
class EventStoreEconomyFixturesTest {

    @Inject
    Db db;
    @Inject
    EventStore eventStore;

    @Test
    void fixturesFromBeforeTheEconomyDecodeWithoutFees() throws Exception {
        for (String dir : new String[] {"golden", "golden-markets"}) {
            String json = Files.readString(Path.of("src/test/resources/events", dir, "BetPlaced.json"));
            assertThat(db.json().readTree(json).has("feeHerdMojos")).as(dir + " predates the fields").isFalse();

            GameEvent.BetPlaced decoded = (GameEvent.BetPlaced) eventStore.decode(-1L, "BetPlaced", json, 1);

            assertThat(decoded.feeHerdMojos()).as(dir).isNull();
            assertThat(decoded.feeNetMojos()).as(dir).isNull();
            assertThat(decoded.hasFees()).as(dir).isFalse();
        }
    }

    @Test
    void aFixtureWithFeesDecodesToThemThroughTheRealEventStore() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/events/golden-economy/BetPlaced.json"));

        GameEvent decoded = eventStore.decode(-1L, "BetPlaced", json, 1);

        assertThat(decoded).isEqualTo(new GameEvent.BetPlaced("p-golden-1", Interval.HOURLY, 1700000000000L,
                1700000180000L, Direction.UP, 500, 100000L, 1700000000500L, "spot_BTC", 5000L, 100L));
    }

    @Test
    void theEventOfALegacyBetIsWrittenWithoutTheFeeKeys() throws Exception {
        GameEvent.BetPlaced legacy = new GameEvent.BetPlaced("p", Interval.HOURLY, 1L, 2L, Direction.UP, 10, 100000L, 1L,
                "ETH");

        String json = db.json().writeValueAsString(legacy);

        assertThat(json).doesNotContain("fee");
        assertThat(eventStore.decode(-1L, "BetPlaced", json, 1)).isEqualTo(legacy);
        GameEvent.BetPlaced withFees = new GameEvent.BetPlaced("p", Interval.HOURLY, 1L, 2L, Direction.UP, 10, 100000L, 1L,
                "ETH", 7L, 100L);
        String jsonWithFees = db.json().writeValueAsString(withFees);
        assertThat(jsonWithFees).contains("\"feeHerdMojos\":7").contains("\"feeNetMojos\":100");
        assertThat(eventStore.decode(-1L, "BetPlaced", jsonWithFees, 1)).isEqualTo(withFees);
    }

    @Test
    void theCurrentSchemaVersionIsTwoAndTheseRowsAreVersionOne() {
        assertThat(EventStore.CURRENT_SCHEMA_VERSION).isEqualTo(2); // F4; the shape above is a version-1 row and still decodes
    }

    @Test
    void aFixtureWithTheStoredLiquidationParametersDecodesToThemAndAStageARowWithoutThemStillDecodes() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/events/golden-economy-b/BetPlaced.json"));

        GameEvent decoded = eventStore.decode(-1L, "BetPlaced", json, 1);

        assertThat(decoded).isEqualTo(new GameEvent.BetPlaced("p-golden-1", Interval.HOURLY, 1700000000000L, 1700000180000L,
                Direction.UP, 500, 100000L, 1700000000500L, "spot_BTC", 5000L, 100L, 1200, "0.1"));
        GameEvent.BetPlaced placed = (GameEvent.BetPlaced) decoded;
        assertThat(placed.liquidation().cap()).isEqualTo(1200);
        assertThat(placed.liquidation().kappa()).isEqualByComparingTo("0.1");
        // the stage-A fixture (fees, no liquidation parameters) has none and is settled with the market's row of the day
        String stageA = Files.readString(Path.of("src/test/resources/events/golden-economy/BetPlaced.json"));
        assertThat(((GameEvent.BetPlaced) eventStore.decode(-1L, "BetPlaced", stageA, 1)).liquidation()).isNull();
        // a bet written without parameters carries no keys for them
        assertThat(db.json().writeValueAsString(new GameEvent.BetPlaced("p", Interval.HOURLY, 1L, 2L, Direction.UP, 10, 100000L, 1L,
                "ETH", 7L, 100L))).doesNotContain("liq");
        assertThat(db.json().writeValueAsString(placed)).contains("\"liqCap\":1200").contains("\"liqKappa\":\"0.1\"");
    }
}
