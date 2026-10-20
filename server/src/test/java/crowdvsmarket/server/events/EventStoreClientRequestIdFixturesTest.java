package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.server.persistence.Db;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Event compatibility for the idempotent bet: {@code BetPlaced} has one more optional trailing field, {@code clientRequestId}, written
 * only when the client sent one. An event of any other bet is the same bytes it always was, and every fixture committed before the
 * field decodes to a bet without an id.
 */
@QuarkusTest
class EventStoreClientRequestIdFixturesTest {

    private static final String ID = "3f1c2b9e-6d54-4c0a-9a43-0d2f7a7e8b11";

    @Inject
    Db db;
    @Inject
    EventStore eventStore;

    @Test
    void theFixturesFromBeforeTheFieldDecodeToABetWithoutAnId() throws Exception {
        for (String dir : new String[] {"golden", "golden-markets", "golden-economy", "golden-economy-b", "golden-fixed"}) {
            String json = Files.readString(Path.of("src/test/resources/events", dir, "BetPlaced.json"));
            assertThat(db.json().readTree(json).has("clientRequestId")).as(dir + " predates the field").isFalse();

            GameEvent.BetPlaced decoded = (GameEvent.BetPlaced) eventStore.decode(-1L, "BetPlaced", json, 1);

            assertThat(decoded.clientRequestId()).as(dir).isNull();
            JsonNode written = db.json().readTree(db.json().writeValueAsString(decoded));
            assertThat(written.has("clientRequestId")).as(dir + " is written back without the key").isFalse();
        }
    }

    @Test
    void aFixtureWithAnIdDecodesToItAndIsWrittenBackWithTheSameKeys() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/events/golden-idempotent/BetPlaced.json"));

        GameEvent decoded = eventStore.decode(-1L, "BetPlaced", json, 2);

        GameEvent.BetPlaced expected = new GameEvent.BetPlaced("p-golden-1", Interval.HOURLY, 1700000000000L, 1700000180000L,
                Direction.UP, 5, 100000L, 1700000000500L, "spot_BTC", 3000L, 100L, 1200, "0.1").withClientRequestId(ID);
        assertThat(decoded).isEqualTo(expected);
        assertThat(((GameEvent.BetPlaced) decoded).clientRequestId()).isEqualTo(ID);
        assertThat(db.json().readTree(db.json().writeValueAsString(decoded))).isEqualTo(db.json().readTree(json));
    }

    @Test
    void aFixedBetCarriesTheIdToo() throws Exception {
        GameEvent.BetPlaced fixed = GameEvent.BetPlaced.fixed("p", Interval.HOURLY, 1L, 2L, Direction.DOWN, 100000L, 1L, "ETH", 7L, 100L)
                .withClientRequestId(ID);

        String json = db.json().writeValueAsString(fixed);

        assertThat(json).contains("\"mode\":\"FIXED\"").contains("\"clientRequestId\":\"" + ID + "\"");
        assertThat(eventStore.decode(-1L, "BetPlaced", json, 2)).isEqualTo(fixed);
    }

    @Test
    void aBlankIdIsNotAnEvent() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GameEvent.BetPlaced("p", Interval.HOURLY, 1L, 2L, Direction.UP, 2, 1L,
                1L, "ETH", 7L, 100L, 1200, "0.1").withClientRequestId(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
