package crowdvsmarket.server.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResultsHasherTest {

    @Test
    void keysAreSortedAlphabeticallyAtEveryLevel_andNoWhitespace() {
        var positions = List.of(
                new ResultsHasher.PositionEntry("p2", "DOWN", 5, 1000, "SETTLED", 1200),
                new ResultsHasher.PositionEntry("p1", "UP", 10, 500, "LIQUIDATED", 0));
        var crowd = new ResultsHasher.CrowdEntry("UP", 7, 1000000, 5000);

        var result = ResultsHasher.build("1h-123", "HOURLY", "SETTLED", 8345100L, 8500000L, positions, crowd);

        assertThat(result.json()).doesNotContain(" ").doesNotContain("\n").doesNotContain("\t");
        // top-level keys sorted: closePriceCents, crowd, openPriceCents, positions, roundId, series, status
        int closeIdx = result.json().indexOf("\"closePriceCents\"");
        int crowdIdx = result.json().indexOf("\"crowd\"");
        int openIdx = result.json().indexOf("\"openPriceCents\"");
        int positionsIdx = result.json().indexOf("\"positions\"");
        int roundIdIdx = result.json().indexOf("\"roundId\"");
        int seriesIdx = result.json().indexOf("\"series\"");
        int statusIdx = result.json().indexOf("\"status\"");
        assertThat(List.of(closeIdx, crowdIdx, openIdx, positionsIdx, roundIdIdx, seriesIdx, statusIdx))
                .isSorted();

        // positions sorted by playerId (p1 before p2), even though p2 was passed first
        assertThat(result.json().indexOf("\"p1\"")).isLessThan(result.json().indexOf("\"p2\""));

        // per-position keys sorted: direction, leverage, outcome, payoutMojos, playerId, stakeMojos
        int firstPositionStart = result.json().indexOf("{", result.json().indexOf("\"positions\""));
        String firstPosition = result.json().substring(firstPositionStart, result.json().indexOf("}", firstPositionStart) + 1);
        int dIdx = firstPosition.indexOf("\"direction\"");
        int lIdx = firstPosition.indexOf("\"leverage\"");
        int oIdx = firstPosition.indexOf("\"outcome\"");
        int payIdx = firstPosition.indexOf("\"payoutMojos\"");
        int pidIdx = firstPosition.indexOf("\"playerId\"");
        int stakeIdx = firstPosition.indexOf("\"stakeMojos\"");
        assertThat(List.of(dIdx, lIdx, oIdx, payIdx, pidIdx, stakeIdx)).isSorted();
    }

    @Test
    void hashIsSha256OfExactJsonBytesWithHexPrefix() throws Exception {
        var result = ResultsHasher.build("1h-1", "HOURLY", "SETTLED", 100L, 110L, List.of(), null);
        assertThat(result.hash()).startsWith("0x").hasSize(2 + 64);

        byte[] expected = MessageDigest.getInstance("SHA-256")
                .digest(result.json().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String expectedHex = "0x" + java.util.HexFormat.of().formatHex(expected);
        assertThat(result.hash()).isEqualTo(expectedHex);
    }

    @Test
    void nullCrowdSerializesAsJsonNull() {
        var result = ResultsHasher.build("1d-1", "DAILY", "CANCELLED", null, null, List.of(), null);
        assertThat(result.json()).contains("\"crowd\":null");
        assertThat(result.json()).contains("\"openPriceCents\":null");
        assertThat(result.json()).contains("\"positions\":[]");
    }

    // --- Markets (Р-69, docs/research/markets_architecture.md §6): BTC stays byte for byte what it was,
    // every other market adds the root key "market". The literals below were produced by the
    // PRE-markets ResultsHasher (git show 2d3e4e9:server/.../ResultsHasher.java, compiled on its own;
    // the file is unchanged since 0bc30ee), so they pin "published hashes stay verifiable", not just "the new code agrees with itself". ---

    private static final String BTC_SETTLED_JSON = "{\"closePriceCents\":5100000,\"crowd\":{\"direction\":\"UP\","
            + "\"leverage\":5,\"marginUsdCents\":1000000,\"pnlUsdCents\":100000},\"openPriceCents\":5000000,"
            + "\"positions\":[{\"direction\":\"UP\",\"leverage\":5,\"outcome\":\"SETTLED\",\"payoutMojos\":550000,"
            + "\"playerId\":\"p1\",\"stakeMojos\":500000},{\"direction\":\"DOWN\",\"leverage\":5,\"outcome\":"
            + "\"SETTLED\",\"payoutMojos\":90000,\"playerId\":\"p2\",\"stakeMojos\":100000},{\"direction\":\"UP\","
            + "\"leverage\":40,\"outcome\":\"LIQUIDATED\",\"payoutMojos\":0,\"playerId\":\"p3\",\"stakeMojos\":"
            + "250000}],\"roundId\":\"1h-1790889263076\",\"series\":\"HOURLY\",\"status\":\"SETTLED\"}";
    private static final String BTC_SETTLED_HASH =
            "0x5e5cf2e99b6fb260a4bac98179e01e73672e608ac0c2a1fedd956b5a2a5232da";
    private static final String BTC_CANCELLED_JSON = "{\"closePriceCents\":null,\"crowd\":null,\"openPriceCents\":null,"
            + "\"positions\":[],\"roundId\":\"1d-1790889263076\",\"series\":\"DAILY\",\"status\":\"CANCELLED\"}";
    private static final String BTC_CANCELLED_HASH =
            "0x0a86b1cffa2de71ccc3fdc2e99216ef70c3759736a40ee9b1ed5208ab62519ae";

    private static List<ResultsHasher.PositionEntry> samplePositions() {
        return List.of(
                new ResultsHasher.PositionEntry("p2", "DOWN", 5, 100000, "SETTLED", 90000),
                new ResultsHasher.PositionEntry("p1", "UP", 5, 500000, "SETTLED", 550000),
                new ResultsHasher.PositionEntry("p3", "UP", 40, 250000, "LIQUIDATED", 0));
    }

    @Test
    void btcSettledResultIsByteIdenticalToTheBeforeMarketsOutput() {
        var crowd = new ResultsHasher.CrowdEntry("UP", 5, 1000000, 100000);

        var legacyOverload = ResultsHasher.build("1h-1790889263076", "HOURLY", "SETTLED", 5000000L, 5100000L,
                samplePositions(), crowd);
        var marketOverload = ResultsHasher.build("BTC", "1h-1790889263076", "HOURLY", "SETTLED", 5000000L, 5100000L,
                samplePositions(), crowd);

        assertThat(legacyOverload.json()).isEqualTo(BTC_SETTLED_JSON);
        assertThat(legacyOverload.hash()).isEqualTo(BTC_SETTLED_HASH);
        assertThat(marketOverload.json()).isEqualTo(BTC_SETTLED_JSON);
        assertThat(marketOverload.hash()).isEqualTo(BTC_SETTLED_HASH);
    }

    @Test
    void btcCancelledResultIsByteIdenticalToTheBeforeMarketsOutput() {
        var legacyOverload = ResultsHasher.build("1d-1790889263076", "DAILY", "CANCELLED", null, null, List.of(), null);
        var marketOverload = ResultsHasher.build("BTC", "1d-1790889263076", "DAILY", "CANCELLED", null, null, List.of(),
                null);

        assertThat(legacyOverload.json()).isEqualTo(BTC_CANCELLED_JSON);
        assertThat(legacyOverload.hash()).isEqualTo(BTC_CANCELLED_HASH);
        assertThat(marketOverload.json()).isEqualTo(BTC_CANCELLED_JSON);
        assertThat(marketOverload.hash()).isEqualTo(BTC_CANCELLED_HASH);
    }

    @Test
    void anyOtherMarketAddsTheRootKeyMarketAndKeepsEveryOtherByte() {
        var crowd = new ResultsHasher.CrowdEntry("UP", 5, 1000000, 100000);

        var eth = ResultsHasher.build("ETH", "ETH-1h-1790889263076", "HOURLY", "SETTLED", 5000000L, 5100000L,
                samplePositions(), crowd);

        // sorted keys: "market" lands between "crowd" and "openPriceCents"; nothing else moves
        assertThat(eth.json()).contains("\"crowd\":{\"direction\":\"UP\",\"leverage\":5,\"marginUsdCents\":1000000,"
                + "\"pnlUsdCents\":100000},\"market\":\"ETH\",\"openPriceCents\":5000000,\"positions\":[");
        assertThat(eth.json()).contains("\"roundId\":\"ETH-1h-1790889263076\"");
        assertThat(eth.json()).doesNotContain(" ").doesNotContain("\n");
        assertThat(eth.json()).isNotEqualTo(BTC_SETTLED_JSON);
        assertThat(eth.hash()).startsWith("0x").hasSize(2 + 64).isNotEqualTo(BTC_SETTLED_HASH);
        // hash = sha256 of exactly those bytes
        assertThat(eth.hash()).isEqualTo(ResultsHasher.sha256Hex(eth.json()));
        // and the key is the ONLY difference: removing it gives back the BTC-shaped JSON of the same fields
        assertThat(eth.json().replace("\"market\":\"ETH\",", "").replace("ETH-1h-", "1h-")).isEqualTo(BTC_SETTLED_JSON);
    }

    @Test
    void cancelledResultOfAnotherMarketAlsoCarriesTheMarketKey() {
        var result = ResultsHasher.build("xyz_NVDA", "xyz_NVDA-1d-5", "DAILY", "CANCELLED", null, null, List.of(), null);

        assertThat(result.json()).isEqualTo("{\"closePriceCents\":null,\"crowd\":null,\"market\":\"xyz_NVDA\","
                + "\"openPriceCents\":null,\"positions\":[],\"roundId\":\"xyz_NVDA-1d-5\",\"series\":\"DAILY\","
                + "\"status\":\"CANCELLED\"}");
    }

    @Test
    void sameInputsProduceByteIdenticalOutput() {
        var a = ResultsHasher.build("1h-1", "HOURLY", "SETTLED", 100L, 105L,
                List.of(new ResultsHasher.PositionEntry("p1", "UP", 2, 100, "SETTLED", 105)), null);
        var b = ResultsHasher.build("1h-1", "HOURLY", "SETTLED", 100L, 105L,
                List.of(new ResultsHasher.PositionEntry("p1", "UP", 2, 100, "SETTLED", 105)), null);
        assertThat(a.json()).isEqualTo(b.json());
        assertThat(a.hash()).isEqualTo(b.hash());
    }
}
