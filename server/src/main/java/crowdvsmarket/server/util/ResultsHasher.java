package crowdvsmarket.server.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import crowdvsmarket.engine.market.MarketSpec;
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
 * <p><b>Proposal</b> (not spelled out by the task): {@code outcome} is {@code "SETTLED"},
 * {@code "LIQUIDATED"} or {@code "CANCELLED"}; a cancelled round's canonical JSON has
 * {@code openPriceCents}/{@code closePriceCents}/{@code crowd} all {@code null} and each
 * position's {@code payoutMojos == stakeMojos} (a full refund, D-23/D-32) with
 * {@code outcome="CANCELLED"} — D-41 only fixes that settled rounds are hashed; extending the
 * same shape to a cancelled round is this server's own choice, so every round (never just settled
 * ones) has a {@code resultsHash} to publish and to serve at
 * {@code GET /api/rounds/{roundId}/results.json}.
 *
 * <p><b>Markets (D-69, docs/research/markets_architecture.md §6):</b> for BTC the JSON is exactly
 * what it was before markets existed — byte for byte, so every published hash stays verifiable
 * (the BTC overload of {@link #build} is unchanged and pinned by {@code ResultsHasherTest}). For any
 * other market a root key {@code "market"} (the market id) is added; the price fields are the
 * market's ENGINE (lot) prices times 100, the unit {@code round_results} stores. A stored
 * {@code canonical_json} is never recomputed. <b>Venues (D-76):</b> a round of a market that has a backup exchange
 * also carries the root key {@code "venue"}, the exchange it ran on; a round of any other market does not.
 */
public final class ResultsHasher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ResultsHasher() {
    }

    /**
     * {@code mode} (D-106): {@code "FIXED"} for a fixed-payout position, which adds the key {@code "mode"} to its entry; {@code null}
     * (the percentage mode) leaves it out, so the entry of any other position is the same bytes it always was.
     */
    public record PositionEntry(String playerId, String direction, int leverage, long stakeMojos, String outcome,
                                  long payoutMojos, String mode) {

        public PositionEntry(String playerId, String direction, int leverage, long stakeMojos, String outcome,
                             long payoutMojos) {
            this(playerId, direction, leverage, stakeMojos, outcome, payoutMojos, null);
        }
    }

    public record CrowdEntry(String direction, int leverage, long marginUsdCents, long pnlUsdCents) {
    }

    public record CanonicalResult(String json, String hash) {
    }

    /** The BTC shape — no {@code "market"} key. */
    public static CanonicalResult build(String roundId, String series, String status, Long openPriceCents,
                                          Long closePriceCents, List<PositionEntry> positions, CrowdEntry crowd) {
        return build(MarketSpec.BTC_ID, roundId, series, status, openPriceCents, closePriceCents, positions, crowd);
    }

    /** {@code market} {@code "BTC"} gives the BTC shape above; any other id adds the root key
     *  {@code "market"}. */
    public static CanonicalResult build(String market, String roundId, String series, String status,
                                          Long openPriceCents, Long closePriceCents, List<PositionEntry> positions,
                                          CrowdEntry crowd) {
        return build(market, roundId, series, status, openPriceCents, closePriceCents, positions, crowd, null);
    }

    /**
     * The same, for a round of a market that has venues (D-76): {@code venue} — the exchange the round ran on,
     * {@code "coinbase"}, {@code "kraken"} or {@code "hyperliquid"} — is a root key of its own, after
     * {@code "status"} in the sorted order. {@code null} leaves the key out, and the JSON is then exactly what the
     * overloads above give: no existing shape changes, so every published hash stays verifiable.
     */
    public static CanonicalResult build(String market, String roundId, String series, String status,
                                          Long openPriceCents, Long closePriceCents, List<PositionEntry> positions,
                                          CrowdEntry crowd, String venue) {
        Map<String, Object> root = new TreeMap<>();
        if (!MarketSpec.BTC_ID.equals(market)) {
            root.put("market", market);
        }
        if (venue != null) {
            root.put("venue", venue);
        }
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
        if (p.mode() != null) {
            m.put("mode", p.mode());
        }
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
