package crowdvsmarket.bots.persona;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** The personas by id. */
public final class Personas {

    private static final Map<String, Supplier<Persona>> BY_ID = Map.of(
            "disciplined", Disciplined::new,
            "house-money", HouseMoney::new,
            "loss-chaser", LossChaser::new,
            "overtrader", Overtrader::new,
            "occasional", Occasional::new,
            "maximalist", Maximalist::new,
            "cautious", Cautious::new);

    private Personas() {
    }

    /** All persona ids, sorted. */
    public static List<String> ids() {
        return BY_ID.keySet().stream().sorted().toList();
    }

    /** @throws IllegalArgumentException for an unknown id */
    public static Persona byId(String id) {
        Supplier<Persona> s = BY_ID.get(id);
        if (s == null) {
            throw new IllegalArgumentException("unknown persona: " + id + " (known: " + ids() + ")");
        }
        return s.get();
    }
}
