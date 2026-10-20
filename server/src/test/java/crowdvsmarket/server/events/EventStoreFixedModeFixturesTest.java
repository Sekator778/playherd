package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.BetMode;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.server.persistence.Db;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Event compatibility for the fixed-payout mode (Р-106): {@code BetPlaced} has one more optional trailing field, {@code mode}, written
 * only as {@code "FIXED"}. An event of any other bet is the same bytes it always was, and every fixture committed before the field
 * (all the other {@code events/golden*} folders) decodes to a percentage-mode bet.
 */
@QuarkusTest
class EventStoreFixedModeFixturesTest {

    @Inject
    Db db;
    @Inject
    EventStore eventStore;

    @Test
    void theFixturesFromBeforeTheModeDecodeToThePercentageMode() throws Exception {
        for (String dir : new String[] {"golden", "golden-markets", "golden-economy", "golden-economy-b"}) {
            String json = Files.readString(Path.of("src/test/resources/events", dir, "BetPlaced.json"));
            assertThat(db.json().readTree(json).has("mode")).as(dir + " predates the field").isFalse();

            GameEvent.BetPlaced decoded = (GameEvent.BetPlaced) eventStore.decode(-1L, "BetPlaced", json, 1);

            assertThat(decoded.mode()).as(dir).isNull();
            assertThat(decoded.betMode()).as(dir).isEqualTo(BetMode.PERCENTAGE);
        }
    }

    @Test
    void aFixedFixtureDecodesToTheFixedModeAndIsWrittenBackWithTheSameKeys() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/events/golden-fixed/BetPlaced.json"));

        GameEvent decoded = eventStore.decode(-1L, "BetPlaced", json, 1);

        assertThat(decoded).isEqualTo(GameEvent.BetPlaced.fixed("p-golden-1", Interval.HOURLY, 1700000000000L, 1700000180000L,
                Direction.UP, 100000L, 1700000000500L, "spot_BTC", 3000L, 100L));
        assertThat(((GameEvent.BetPlaced) decoded).betMode()).isEqualTo(BetMode.FIXED);
        assertThat(db.json().readTree(db.json().writeValueAsString(decoded))).isEqualTo(db.json().readTree(json));
    }

    @Test
    void theEventOfAPercentageBetIsWrittenWithoutTheModeKey() throws Exception {
        GameEvent.BetPlaced percentage = new GameEvent.BetPlaced("p", Interval.HOURLY, 1L, 2L, Direction.UP, 10, 100000L, 1L,
                "ETH", 7L, 100L, 1200, "0.1");

        String json = db.json().writeValueAsString(percentage);

        assertThat(json).doesNotContain("mode");
        assertThat(eventStore.decode(-1L, "BetPlaced", json, 1)).isEqualTo(percentage);
    }

    @Test
    void aModeThatIsNotFixedOrAFixedBetWithoutFeesIsRefused() {
        assertThatThrownBy(() -> new GameEvent.BetPlaced("p", Interval.HOURLY, 1L, 2L, Direction.UP, 2, 1L, 1L, "ETH", 7L, 100L,
                null, null, "PERCENTAGE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameEvent.BetPlaced("p", Interval.HOURLY, 1L, 2L, Direction.UP, 2, 1L, 1L, "ETH", null, null,
                null, null, "FIXED")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GameEvent.BetPlaced("p", Interval.HOURLY, 1L, 2L, Direction.UP, 2, 1L, 1L, "ETH", 7L, 100L,
                null, null, "WHATEVER")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSchemaVersionIsTwoSinceF4() {
        assertThat(EventStore.CURRENT_SCHEMA_VERSION).isEqualTo(2);
        assertThat(EventStore.LEGACY_SCHEMA_VERSION).isEqualTo(1);
    }
}
