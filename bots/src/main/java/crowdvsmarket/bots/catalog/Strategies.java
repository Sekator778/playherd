package crowdvsmarket.bots.catalog;

import crowdvsmarket.bots.signal.BollingerBounce;
import crowdvsmarket.bots.signal.Coin;
import crowdvsmarket.bots.signal.CrowdContrarian;
import crowdvsmarket.bots.signal.CrowdFollow;
import crowdvsmarket.bots.signal.DonchianBreakout;
import crowdvsmarket.bots.signal.EmaCross;
import crowdvsmarket.bots.signal.Momentum;
import crowdvsmarket.bots.signal.RsiReversion;
import crowdvsmarket.bots.signal.SignalStrategy;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a {@link SignalStrategy} from the kind and parameters written in the catalog. */
public final class Strategies {

    /** Kind to its exact parameter names. */
    private static final Map<String, Set<String>> PARAMS = Map.of(
            "momentum", Set.of("n"),
            "rsi", Set.of("n", "low", "high"),
            "bollinger", Set.of("n", "k"),
            "donchian", Set.of("n"),
            "ema", Set.of("fast", "slow"),
            "crowd-follow", Set.of(),
            "crowd-contrarian", Set.of(),
            "coin", Set.of());

    private Strategies() {
    }

    /** All strategy kinds, sorted. */
    public static List<String> kinds() {
        return PARAMS.keySet().stream().sorted().toList();
    }

    /**
     * @throws IllegalArgumentException for an unknown kind, missing or extra parameters, or values the strategy rejects
     */
    public static SignalStrategy create(String kind, Map<String, Double> p) {
        Set<String> expected = PARAMS.get(kind);
        if (expected == null) {
            throw new IllegalArgumentException("unknown strategy kind: " + kind + " (known: " + kinds() + ")");
        }
        if (!expected.equals(p.keySet())) {
            throw new IllegalArgumentException("strategy " + kind + " takes parameters " + expected + ", got " + p.keySet());
        }
        return switch (kind) {
            case "momentum" -> new Momentum(integer(p, "n"));
            case "rsi" -> new RsiReversion(integer(p, "n"), p.get("low"), p.get("high"));
            case "bollinger" -> new BollingerBounce(integer(p, "n"), p.get("k"));
            case "donchian" -> new DonchianBreakout(integer(p, "n"));
            case "ema" -> new EmaCross(integer(p, "fast"), integer(p, "slow"));
            case "crowd-follow" -> new CrowdFollow();
            case "crowd-contrarian" -> new CrowdContrarian();
            default -> new Coin();
        };
    }

    private static int integer(Map<String, Double> p, String key) {
        double v = p.get(key);
        if (v != Math.rint(v) || Math.abs(v) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("parameter " + key + " must be a whole number, got " + v);
        }
        return (int) v;
    }
}
