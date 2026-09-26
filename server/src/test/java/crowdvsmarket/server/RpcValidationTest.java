package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.chain.ChainSvcClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wave-1 security findings (task item 2): input validation that used to either 500 instead of 400,
 * silently accept bad input, or echo Jackson/Java internals back to the client. chainsvc is mocked
 * exactly like {@link GameFlowIntegrationTest} — this class never talks to a real chain service.
 */
@QuarkusTest
class RpcValidationTest {

    @InjectMock
    ChainSvcClient chainSvcClient;

    @BeforeEach
    void stubChainSvc() {
        org.mockito.Mockito.when(chainSvcClient.authVerify(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ChainSvcClient.AuthVerifyResult(true, null));
        org.mockito.Mockito.when(chainSvcClient.oracleRecords(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> {
                    List<ChainSvcClient.OracleRecord> records = inv.getArgument(0);
                    List<Long> seqs = records.stream().map(ChainSvcClient.OracleRecord::seq).toList();
                    return new ChainSvcClient.OracleRecordsResult(seqs.size(), seqs);
                });
        org.mockito.Mockito.when(chainSvcClient.oracleStatus()).thenReturn(null);
        org.mockito.Mockito.when(chainSvcClient.health()).thenReturn(true);
    }

    private String signIn(String address) {
        Response challengeResp = given().contentType(ContentType.JSON)
                .body(Map.of("args", List.of()))
                .post("/api/rpc/authChallenge");
        String nonce = challengeResp.jsonPath().getString("nonce");
        Map<String, Object> verifyReq = Map.of(
                "nonce", nonce,
                "address", address,
                "publicKey", "0x" + "ab".repeat(48),
                "signature", "0x" + "cd".repeat(96));
        Response verifyResp = given().contentType(ContentType.JSON)
                .body(Map.of("args", List.of(verifyReq)))
                .post("/api/rpc/authVerify");
        String sessionId = verifyResp.getCookie("herd_session");
        assertThat(sessionId).as(verifyResp.asString()).isNotBlank();
        return sessionId;
    }

    private Response rpc(String sessionId, String method, Object... args) {
        var req = given().contentType(ContentType.JSON).body(Map.of("args", List.of(args)));
        if (sessionId != null) {
            req = req.cookie("herd_session", sessionId);
        }
        return req.post("/api/rpc/" + method);
    }

    private Response rpcRaw(String sessionId, String method, String rawBody) {
        var req = given().contentType(ContentType.JSON).body(rawBody);
        if (sessionId != null) {
            req = req.cookie("herd_session", sessionId);
        }
        return req.post("/api/rpc/" + method);
    }

    // --- setNickname (trim, 3-24 chars, allow-list, reject control chars/NUL) ---

    @Test
    void setNicknameRejectsTooShort() {
        String session = signIn("txch1nick-too-short-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "setNickname", "ab");
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("INVALID_NICKNAME");
    }

    @Test
    void setNicknameRejectsTooLong() {
        String session = signIn("txch1nick-too-long-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "setNickname", "a".repeat(25));
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("INVALID_NICKNAME");
    }

    @Test
    void setNicknameRejectsDisallowedCharacters() {
        String session = signIn("txch1nick-bad-chars-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "setNickname", "bad<script>");
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("INVALID_NICKNAME");
    }

    @Test
    void setNicknameRejectsControlCharactersIncludingNul() {
        String session = signIn("txch1nick-control-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "setNickname", "bad\u0000nick");
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("INVALID_NICKNAME");
    }

    @Test
    void setNicknameAcceptsValidNicknameAfterTrimming() {
        String session = signIn("txch1nick-valid-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "setNickname", "  Nice_Name.1  ");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
        assertThat(resp.jsonPath().getString("nickname")).isEqualTo("Nice_Name.1");
    }

    // --- missing/null required object body -> 400, never 500 ---

    @Test
    void authVerifyWithMissingBodyReturns400NotServerError() {
        Response resp = rpc(null, "authVerify");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("BAD_REQUEST");
    }

    @Test
    void authVerifyWithNullBodyReturns400NotServerError() {
        Response resp = rpcRaw(null, "authVerify", "{\"args\":[null]}");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("BAD_REQUEST");
    }

    @Test
    void previewLiquidationWithMissingBodyReturns400NotServerError() {
        Response resp = rpc(null, "previewLiquidation");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("BAD_REQUEST");
    }

    @Test
    void placeBetWithMissingBodyReturns400NotServerError() {
        String session = signIn("txch1placebet-null-body-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "placeBet");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("BAD_REQUEST");
    }

    // --- Jackson errors: generic message, no Java class names ---

    @Test
    void malformedJsonBodyReturnsGenericBadRequestMessage() {
        Response resp = rpcRaw(null, "authVerify", "{not valid json");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("BAD_REQUEST");
        assertThat(resp.asString()).doesNotContain("crowdvsmarket").doesNotContain("com.fasterxml");
    }

    @Test
    void typeMismatchInRequestBodyReturnsGenericMessageWithoutJavaClassName() {
        // leverage is an int; a JSON string can never bind to it — Jackson's own exception
        // message for this names the target Java DTO class, which must never reach the client.
        String body = "{\"args\":[{\"direction\":\"UP\",\"leverage\":\"not-a-number\",\"stake\":\"100.000\"}]}";
        Response resp = rpcRaw(null, "previewLiquidation", body);
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("BAD_REQUEST");
        assertThat(resp.asString()).doesNotContain("crowdvsmarket").doesNotContain("com.fasterxml");
    }

    // --- leverage must be an integral JSON number ---

    @Test
    void nonIntegralLeverageIsRejectedNotSilentlyTruncated() {
        String body = "{\"args\":[{\"direction\":\"UP\",\"leverage\":10.5,\"stake\":\"100.000\"}]}";
        Response resp = rpcRaw(null, "previewLiquidation", body);
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("BAD_REQUEST");
    }

    // --- wave-2 CRITICAL security fix: requestWithdrawal/requestDeposit reject non-positive
    // amounts (the exploited requestWithdrawal("-5000.000") minted PLAY before this fix) ---

    @Test
    void requestWithdrawalRejectsNegativeAmountAndNeverMintsPlay() {
        String session = signIn("txch1wd-negative-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String balanceBefore = rpc(session, "myAccount").jsonPath().getString("balance");
        assertThat(balanceBefore).isEqualTo("10000.000");

        Response resp = rpc(session, "requestWithdrawal", "-5000.000");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("INVALID_AMOUNT");

        String balanceAfter = rpc(session, "myAccount").jsonPath().getString("balance");
        assertThat(balanceAfter).as("balance must be unchanged, never increased").isEqualTo("10000.000");
    }

    @Test
    void requestWithdrawalRejectsZeroAmount() {
        String session = signIn("txch1wd-zero-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "requestWithdrawal", "0.000");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("INVALID_AMOUNT");
    }

    @Test
    void requestWithdrawalStillAcceptsAPositiveAmount() {
        String session = signIn("txch1wd-positive-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "requestWithdrawal", "500.000");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
        assertThat(resp.jsonPath().getString("state")).isEqualTo("CONFIRMED");
        String balanceAfter = rpc(session, "myAccount").jsonPath().getString("balance");
        assertThat(balanceAfter).isEqualTo("9500.000");
    }

    @Test
    void requestDepositRejectsNegativeAmount() {
        String session = signIn("txch1dep-negative-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "requestDeposit", "-100.000");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("INVALID_AMOUNT");
    }

    @Test
    void requestDepositRejectsZeroAmount() {
        String session = signIn("txch1dep-zero-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "requestDeposit", "0.000");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("INVALID_AMOUNT");
    }

    @Test
    void requestDepositStillAcceptsAPositiveAmount() {
        String session = signIn("txch1dep-positive-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "requestDeposit", "250.000");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
        assertThat(resp.jsonPath().getString("state")).isEqualTo("CONFIRMED");
        String balanceAfter = rpc(session, "myAccount").jsonPath().getString("balance");
        assertThat(balanceAfter).isEqualTo("10250.000");
    }

    @Test
    void placeBetStillRejectsZeroOrNegativeStakeAsBelowMinimum() {
        // Defense-in-depth check (task item 1's "stake" entry point): a non-positive stake is
        // already caught by the pre-existing minimum-stake check, exercised explicitly here so a
        // future change to that check's ordering cannot silently reopen this entry point.
        String session = signIn("txch1bet-zero-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String bettingRoundId = rpc(session, "rounds", "HOURLY").jsonPath().getString("betting.roundId");
        Response resp = rpc(session, "placeBet", java.util.Map.of("roundId", bettingRoundId, "direction", "UP",
                "leverage", 2, "stake", "-100.000"));
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(422);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("STAKE_BELOW_MINIMUM");
    }
}
