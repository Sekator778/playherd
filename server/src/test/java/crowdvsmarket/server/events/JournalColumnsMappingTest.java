package crowdvsmarket.server.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link EventStore#marketOf} / {@link EventStore#playerOf} against the shape of every event record: an event with a
 * {@code market} or {@code marketId} component has a market, one with a {@code playerId} component has a player, none of the others
 * has either. A new event type with a {@code playerId} that the switch maps to {@code null} fails here.
 */
class JournalColumnsMappingTest {

    @Test
    void everyEventTypeMapsTheComponentItHas() throws Exception {
        List<String> checked = new ArrayList<>();
        for (Class<?> type : GameEvent.class.getPermittedSubclasses()) {
            GameEvent event = sample(type);
            String marketComponent = has(type, "market") ? "market" : has(type, "marketId") ? "marketId" : null;
            assertThat(EventStore.marketOf(event)).as(type.getSimpleName() + " market")
                    .isEqualTo(marketComponent == null ? null : "v-" + marketComponent);
            assertThat(EventStore.playerOf(event)).as(type.getSimpleName() + " player")
                    .isEqualTo(has(type, "playerId") ? "v-playerId" : null);
            checked.add(type.getSimpleName());
        }
        assertThat(checked).hasSize(22);
    }

    @Test
    void anEventWrittenBeforeMarketsBelongsToBtc() {
        GameEvent.BetCancelled old = new GameEvent.BetCancelled("p", crowdvsmarket.engine.market.Interval.HOURLY, 1L, 2L, 3L);
        assertThat(EventStore.marketOf(old)).isEqualTo("BTC");
    }

    private static boolean has(Class<?> type, String component) {
        for (RecordComponent rc : type.getRecordComponents()) {
            if (rc.getName().equals(component)) {
                return true;
            }
        }
        return false;
    }

    /** An instance whose String components are {@code v-<name>}, numbers 1, enums their first constant, lists empty. */
    private static GameEvent sample(Class<?> type) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            Class<?> t = components[i].getType();
            types[i] = t;
            String name = components[i].getName();
            if (name.equals("mode") || name.equals("liqKappa") || name.equals("liqCap")) {
                args[i] = null; // a mode is validated against the wire names
            } else if (t == String.class) {
                args[i] = "v-" + name;
            } else if (t == long.class || t == Long.class) {
                args[i] = 1L;
            } else if (t == int.class || t == Integer.class) {
                args[i] = 1;
            } else if (t.isEnum()) {
                args[i] = t.getEnumConstants()[0];
            } else if (t == List.class) {
                args[i] = List.of();
            } else {
                args[i] = null;
            }
        }
        Constructor<?> canonical = type.getDeclaredConstructor(types);
        return (GameEvent) canonical.newInstance(args);
    }
}
