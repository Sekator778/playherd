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
