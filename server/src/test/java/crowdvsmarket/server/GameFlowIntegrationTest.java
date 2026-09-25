package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

/**
 * The task's integration test: "runs 3 fast rounds with bots and 2 signed-in players (mock the
 * chainsvc client) and checks balances, reveal rules, results.json hash, and restart recovery."
 * chainsvc is mocked via {@link InjectMock} — this test never talks to a real chain service; both
 * "players" sign in with the local test-wallet flow (Р-45), i.e. any address/publicKey/signature,
 * since the mock accepts every {@code authVerify} call as valid — signature verification itself is
 * chainsvc's job (FIXED INTERFACE 3), not this server's.
 */
@QuarkusTest
@TestProfile(GameFlowIntegrationTest.FastRoundsProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GameFlowIntegrationTest {

    public static class FastRoundsProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // Short enough that "3 fast rounds" finishes in well under a minute, long enough that
            // 2 HTTP sign-ins + 2 place-bet calls comfortably land inside one betting window.
            return Map.of(
                    "herd.round-durations.hourly-sec", "4",
                    "herd.round-durations.daily-sec", "6",
                    "herd.bots.count", "8");
        }
    }

    @InjectMock
    ChainSvcClient chainSvcClient;

    @Inject
    ObjectMapper mapper;
    @Inject
    RoundResultRepository roundResults;
    @Inject
    EngineRebuildService engineRebuildService;

    private record Session(String playerId, String sessionId) {
    }

    private Session signIn(String address) {
        Response challengeResp = given().contentType(ContentType.JSON)
                .body(Map.of("args", List.of()))
                .post("/api/rpc/authChallenge");
        assertThat(challengeResp.statusCode()).isEqualTo(200);
        String nonce = challengeResp.jsonPath().getString("nonce");

        Map<String, Object> verifyReq = Map.of(
                "nonce", nonce,
                "address", address,
                "publicKey", "0x" + "ab".repeat(48),
                "signature", "0x" + "cd".repeat(96));
        Response verifyResp = given().contentType(ContentType.JSON)
                .body(Map.of("args", List.of(verifyReq)))
                .post("/api/rpc/authVerify");
        assertThat(verifyResp.statusCode()).as(verifyResp.asString()).isEqualTo(200);
        String playerId = verifyResp.jsonPath().getString("player.playerId");
        String sessionId = verifyResp.getCookie("herd_session");
        assertThat(sessionId).isNotBlank();
        return new Session(playerId, sessionId);
    }

    private JsonNode rpc(Session session, String method, Object... args) {
        var req = given().contentType(ContentType.JSON).body(Map.of("args", List.of(args)));
        if (session != null) {
            req = req.cookie("herd_session", session.sessionId());
        }
        Response resp = req.post("/api/rpc/" + method);
        assertThat(resp.statusCode()).as("%s -> %s", method, resp.asString()).isEqualTo(200);
        try {
            return mapper.readTree(resp.asString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Order(0)
    void currentPlayerWithoutSessionIsJsonNull() {
        // Regression: a null result used to be serialized as {}, which the web app read as
        // "signed in" and never showed CONNECT WALLET on a fresh device.
        String body = given().contentType(ContentType.JSON).body(Map.of("args", List.of()))
                .post("/api/rpc/currentPlayer")
                .then().statusCode(200).extract().asString();
        org.junit.jupiter.api.Assertions.assertEquals("null", body.trim());
    }

    @Test
    @Order(1)
    void threeFastRoundsWithBotsAndTwoPlayers_balancesRevealRulesHashAndRestartRecovery() throws Exception {
        // chainsvc mock: every auth attempt is valid (real crypto is chainsvc's job, FIXED
        // INTERFACE 3), oracle records are always fully queued, status reports a stub.
        org.mockito.Mockito.when(chainSvcClient.authVerify(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ChainSvcClient.AuthVerifyResult(true, null));
        org.mockito.Mockito.when(chainSvcClient.oracleRecords(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
        org.mockito.Mockito.when(chainSvcClient.oracleStatus())
                .thenReturn(new ChainSvcClient.OracleStatus(
                        "c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20", "testnet11", 1L, 4_735_705L,
                        0, null, "10000000000"));
        org.mockito.Mockito.when(chainSvcClient.health()).thenReturn(true);

        // --- sign in two players (Р-45 local test wallet) ---
        Session player1 = signIn("txch1player1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Session player2 = signIn("txch1player2bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        assertThat(player1.playerId()).isNotEqualTo(player2.playerId());

        // --- reveal rules (Р-22): before either player bets, the betting round shows 0 bettors
        // and no directions/amounts (RoundBetting has no such fields at all — structurally
        // impossible to leak, see dto/Rounds.java). ---
        JsonNode hourlyPair = rpc(player1, "rounds", "HOURLY");
        String bettingRoundId = hourlyPair.path("betting").path("roundId").asText();
        assertThat(hourlyPair.path("betting").path("phase").asText()).isEqualTo("BETTING");

        JsonNode myAccountBefore = rpc(player1, "myAccount");
        String balanceBefore = myAccountBefore.path("balance").asText();
        assertThat(new java.math.BigDecimal(balanceBefore)).isEqualByComparingTo("10000.000");

        // --- place opposite-direction bets from both players on the current betting round ---
        Map<String, Object> bet1 = Map.of("roundId", bettingRoundId, "direction", "UP", "leverage", 5,
                "stake", "500.000");
        Map<String, Object> bet2 = Map.of("roundId", bettingRoundId, "direction", "DOWN", "leverage", 5,
                "stake", "300.000");
        rpc(player1, "placeBet", bet1);
        rpc(player2, "placeBet", bet2);

        // --- still hidden while betting: only the headcount is visible, even to the bettors
        // themselves via the *other* player's read (no session at all here) ---
        JsonNode duringBetting = rpc(null, "round", bettingRoundId);
        assertThat(duringBetting.path("phase").asText()).isEqualTo("BETTING");
        assertThat(duringBetting.path("participantCount").asInt()).isEqualTo(2);
        assertThat(duringBetting.has("positions")).isFalse();

        // --- wait for that exact round to settle ---
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300))
                .until(() -> roundResults.exists(bettingRoundId));

        // --- revealed after settlement: both directions/leverages visible to anyone ---
        JsonNode settled = rpc(null, "round", bettingRoundId);
        assertThat(settled.path("phase").asText()).isEqualTo("SETTLED");
        JsonNode positions = settled.path("positions");
        assertThat(positions).hasSizeGreaterThanOrEqualTo(2);
        boolean sawPlayer1 = false;
        boolean sawPlayer2 = false;
        for (JsonNode p : positions) {
            if (p.path("playerId").asText().equals(player1.playerId())) {
                sawPlayer1 = true;
                assertThat(p.path("direction").asText()).isEqualTo("UP");
                assertThat(p.path("leverage").asInt()).isEqualTo(5);
            }
            if (p.path("playerId").asText().equals(player2.playerId())) {
                sawPlayer2 = true;
                assertThat(p.path("direction").asText()).isEqualTo("DOWN");
            }
        }
        assertThat(sawPlayer1).isTrue();
        assertThat(sawPlayer2).isTrue();

        // --- balances moved (stake at minimum was debited; payout — 0 or more — was credited) ---
        JsonNode myAccountAfter = rpc(player1, "myAccount");
        java.math.BigDecimal balanceAfter = new java.math.BigDecimal(myAccountAfter.path("balance").asText());
        assertThat(balanceAfter).isGreaterThanOrEqualTo(java.math.BigDecimal.ZERO);
        assertThat(balanceAfter).isNotEqualByComparingTo(new java.math.BigDecimal(balanceBefore)); // stake moved

        // --- results.json byte-for-byte hashes to resultsHash (Р-41) ---
        String resultsHash = settled.path("resultsHash").asText();
        assertThat(resultsHash).startsWith("0x");
        Response resultsJson = given().get("/api/rounds/" + bettingRoundId + "/results.json");
        assertThat(resultsJson.statusCode()).isEqualTo(200);
        byte[] raw = resultsJson.asByteArray();
        String recomputed = "0x" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        assertThat(recomputed).isEqualTo(resultsHash);
        // and the canonical JSON actually names this round and both players
        String resultsText = new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(resultsText).contains(bettingRoundId).contains(player1.playerId()).contains(player2.playerId());
        assertThat(resultsText).doesNotContain(" ").doesNotContain("\n"); // "no whitespace"

        // --- 3 fast rounds: wait for two MORE hourly rounds to settle (bots keep betting on their
        // own — BotService/RoundSchedulerService run continuously in the background) ---
        AtomicReference<String> secondRoundId = new AtomicReference<>();
        AtomicReference<String> thirdRoundId = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(() -> {
            JsonNode pair = rpc(null, "rounds", "HOURLY");
            String runningId = pair.path("running").path("roundId").asText();
            if (!runningId.equals(bettingRoundId) && secondRoundId.get() == null) {
                secondRoundId.set(runningId);
            }
            return roundResults.exists(bettingRoundId) && secondRoundId.get() != null
                    && roundResults.exists(secondRoundId.get());
        });
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).until(() -> {
            JsonNode pair = rpc(null, "rounds", "HOURLY");
            String runningId = pair.path("running").path("roundId").asText();
            if (!runningId.equals(bettingRoundId) && !runningId.equals(secondRoundId.get())
                    && thirdRoundId.get() == null) {
                thirdRoundId.set(runningId);
            }
            return thirdRoundId.get() != null && roundResults.exists(thirdRoundId.get());
        });
        assertThat(roundResults.exists(bettingRoundId)).isTrue();
        assertThat(roundResults.exists(secondRoundId.get())).isTrue();
        assertThat(roundResults.exists(thirdRoundId.get())).isTrue();

        // --- restart recovery: rebuild the whole engine from persisted events (same code path a
        // real process restart uses, EngineRebuildService) and check nothing changed ---
        JsonNode beforeRebuildAccount = rpc(player1, "myAccount");
        JsonNode beforeRebuildRound = rpc(null, "round", bettingRoundId);
        JsonNode beforeRebuildCrowd = rpc(null, "crowdAccount");

        engineRebuildService.rebuild();

        JsonNode afterRebuildAccount = rpc(player1, "myAccount");
        JsonNode afterRebuildRound = rpc(null, "round", bettingRoundId);
        JsonNode afterRebuildCrowd = rpc(null, "crowdAccount");

        assertThat(afterRebuildAccount.path("balance").asText()).isEqualTo(beforeRebuildAccount.path("balance").asText());
        assertThat(afterRebuildRound).isEqualTo(beforeRebuildRound); // round_results is a read model, untouched by rebuild
        assertThat(afterRebuildCrowd.path("capital").asText()).isEqualTo(beforeRebuildCrowd.path("capital").asText());
        assertThat(afterRebuildCrowd.path("history").size()).isEqualTo(beforeRebuildCrowd.path("history").size());

        // player1 can still place a bet after the rebuild (session, engine and RoundProgress all
        // came back correctly, not just the read models)
        JsonNode pairAfterRebuild = rpc(player1, "rounds", "HOURLY");
        String nextBettingRoundId = pairAfterRebuild.path("betting").path("roundId").asText();
        rpc(player1, "placeBet", Map.<String, Object>of("roundId", nextBettingRoundId, "direction", "UP",
                "leverage", 2, "stake", "100.000"));
    }
}
