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

    /**
     * Parameter variants (sweeps, and the catalog's own spread inside the math review's ranges): {@code loss-chaser@step/cap}, {@code house-money@step/cap/stakeMultiple},
     * {@code maximalist@share} (e.g. {@code loss-chaser@1.0/5}); null for any other id.
     */
    private static Persona swept(String id) {
        int at = id.indexOf('@');
        if (at < 0) {
            return null;
        }
        String[] p = id.substring(at + 1).split("/");
        try {
            return switch (id.substring(0, at)) {
                case "loss-chaser" -> new LossChaser(Double.parseDouble(p[0]), Integer.parseInt(p[1]));
                case "house-money" -> new HouseMoney(Double.parseDouble(p[0]), Integer.parseInt(p[1]), Double.parseDouble(p[2]));
                case "maximalist" -> new Maximalist(Double.parseDouble(p[0]));
                default -> null;
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bad persona variant: " + id, e);
        }
    }

    private Personas() {
    }

    /** All persona ids, sorted. */
    public static List<String> ids() {
        return BY_ID.keySet().stream().sorted().toList();
    }

    /** Today's server bots, for backtests that compare populations ({@link LegacyPersona}); not in {@link #ids()}. */
    private static final Map<String, Supplier<Persona>> LEGACY = Map.of(
            "legacy-maximalist", () -> new LegacyPersona(LegacyPersona.Kind.MAXIMALIST),
            "legacy-cautious", () -> new LegacyPersona(LegacyPersona.Kind.CAUTIOUS),
            "legacy-mixed", () -> new LegacyPersona(LegacyPersona.Kind.MIXED));

    /** @throws IllegalArgumentException for an unknown id */
    public static Persona byId(String id) {
        Persona swept = swept(id);
        if (swept != null) {
            return swept;
        }
        Supplier<Persona> s = BY_ID.containsKey(id) ? BY_ID.get(id) : LEGACY.get(id);
        if (s == null) {
            throw new IllegalArgumentException("unknown persona: " + id + " (known: " + ids() + ")");
        }
        return s.get();
    }
}
