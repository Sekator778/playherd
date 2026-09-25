package crowdvsmarket.server.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FIXED INTERFACE (2), "Canonical results JSON" — UTF-8, keys sorted, no whitespace:
 * {@code {"roundId","series","status","openPriceCents","closePriceCents","positions":[{"playerId",
 * "direction","leverage","stakeMojos","outcome","payoutMojos"}] sorted by playerId,
 * "crowd":{"direction","leverage","marginUsdCents","pnlUsdCents"} or null}}. {@code resultsHash =
 * "0x"+sha256 hex} of those exact bytes.
 *
 * <p>The task's own key list above is written in a non-alphabetical (semantic) order for
 * readability; this class follows the governing rule stated right before it ("keys sorted")
 * literally, at every nesting level — {@code TreeMap}'s natural {@link String} ordering, which for
 * these all-ASCII field names is plain byte-lexicographic order, matching what any other
 * implementation reading "keys sorted" the same way would produce independently.
 *
 * <p><b>Предложение</b> (not spelled out by the task): {@code outcome} is {@code "SETTLED"},
 * {@code "LIQUIDATED"} or {@code "CANCELLED"}; a cancelled round's canonical JSON has
 * {@code openPriceCents}/{@code closePriceCents}/{@code crowd} all {@code null} and each
 * position's {@code payoutMojos == stakeMojos} (a full refund, Р-23/Р-32) with
 * {@code outcome="CANCELLED"} — Р-41 only fixes that settled rounds are hashed; extending the
 * same shape to a cancelled round is this server's own choice, so every round (never just settled
 * ones) has a {@code resultsHash} to publish and to serve at
 * {@code GET /api/rounds/{roundId}/results.json}.
 */
public final class ResultsHasher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResultsHasher() {
    }

    public record PositionEntry(String playerId, String direction, int leverage, long stakeMojos, String outcome,
                                  long payoutMojos) {
    }

    public record CrowdEntry(String direction, int leverage, long marginUsdCents, long pnlUsdCents) {
    }

    public record CanonicalResult(String json, String hash) {
    }

    public static CanonicalResult build(String roundId, String series, String status, Long openPriceCents,
                                          Long closePriceCents, List<PositionEntry> positions, CrowdEntry crowd) {
        Map<String, Object> root = new TreeMap<>();
        root.put("roundId", roundId);
        root.put("series", series);
        root.put("status", status);
        root.put("openPriceCents", openPriceCents);
        root.put("closePriceCents", closePriceCents);

        List<Map<String, Object>> positionMaps = positions.stream()
                .sorted((a, b) -> a.playerId().compareTo(b.playerId()))
                .map(ResultsHasher::positionMap)
                .toList();
        root.put("positions", positionMaps);

        root.put("crowd", crowd == null ? null : crowdMap(crowd));

        try {
            String json = MAPPER.writeValueAsString(root);
            return new CanonicalResult(json, sha256Hex(json));
        } catch (Exception e) {
            throw new RuntimeException("failed to build canonical results JSON for " + roundId, e);
        }
    }

    private static Map<String, Object> positionMap(PositionEntry p) {
        Map<String, Object> m = new TreeMap<>();
        m.put("playerId", p.playerId());
        m.put("direction", p.direction());
        m.put("leverage", p.leverage());
        m.put("stakeMojos", p.stakeMojos());
        m.put("outcome", p.outcome());
        m.put("payoutMojos", p.payoutMojos());
        return m;
    }

    private static Map<String, Object> crowdMap(CrowdEntry c) {
        Map<String, Object> m = new TreeMap<>();
        m.put("direction", c.direction());
        m.put("leverage", c.leverage());
        m.put("marginUsdCents", c.marginUsdCents());
        m.put("pnlUsdCents", c.pnlUsdCents());
        return m;
    }

    public static String sha256Hex(String utf8Text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(utf8Text.getBytes(StandardCharsets.UTF_8));
            return "0x" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
