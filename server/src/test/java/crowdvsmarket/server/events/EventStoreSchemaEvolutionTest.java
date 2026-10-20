package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Task item 4 (event schema evolution, docs/reviews/2026-09-26-independent-critique.md): the
 * three failure/compatibility behaviors {@link EventStore#decode} adds on top of the plain
 * type-name switch — see {@link EventStoreGoldenFixturesTest} for per-type round-trip/replay
 * coverage of the switch itself.
 */
@QuarkusTest
class EventStoreSchemaEvolutionTest {

    @Inject
    EventStore eventStore;

    /**
     * {@link EventStore#TYPE_ALIASES} is empty today (no {@link GameEvent} type has ever actually
     * been renamed — see its javadoc), so the resolution mechanism itself is exercised here
     * directly against a synthetic alias map, proving it would correctly rescue an old/renamed
     * {@code event_type} string the day {@code TYPE_ALIASES} actually needs an entry — exactly the
     * independent critique's scenario ("a class-rename would provably kill server startup
     * entirely, not just one event type").
     */
    @Test
    void aliasedTypeNameResolvesToCurrentType() {
        Map<String, String> aliases = Map.of("PlayerJoined", "PlayerRegistered");
        assertThat(EventStore.resolveTypeAlias("PlayerJoined", aliases)).isEqualTo("PlayerRegistered");
        // a name with no alias entry resolves to itself, unchanged
        assertThat(EventStore.resolveTypeAlias("PlayerRegistered", aliases)).isEqualTo("PlayerRegistered");
    }

    @Test
    void anEventTypeRenamedWithoutAnAliasEntryStillFailsLoudlyNamingTheRow() {
        // Without registering "PlayerJoined" -> "PlayerRegistered" in the real (empty) TYPE_ALIASES,
        // decode must still fail loudly rather than silently skip a money-relevant row — and the
        // failure must name the row id so an operator can go look at it (task item 4's exact
        // wording), not just the type.
        assertThatThrownBy(() -> eventStore.decode(4242L, "PlayerJoined", "{}", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("4242")
                .hasMessageContaining("PlayerJoined");
    }

    @Test
    void unsupportedSchemaVersionFailsLoudlyNamingTheRow() {
        assertThatThrownBy(() -> eventStore.decode(7777L, "PlayerRegistered",
                "{\"playerId\":\"p\",\"atMs\":1}", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("7777")
                .hasMessageContaining("schema_version");
    }

    @Test
    void currentSchemaVersionDecodesNormally() {
        GameEvent decoded = eventStore.decode(1L, "PlayerRegistered", "{\"playerId\":\"p\",\"atMs\":1}", 1);
        assertThat(decoded).isEqualTo(new GameEvent.PlayerRegistered("p", 1L));
    }
}
