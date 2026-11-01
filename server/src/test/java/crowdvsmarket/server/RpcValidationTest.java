package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.chain.ChainSvcClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
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
 *
 * <p>Task item 1 (BLOCKER security fix): {@code requestDeposit}/{@code requestWithdrawal} are
 * disabled by default (see {@link WithdrawalDepositUnavailableTest} for that default-config
 * behavior) — this class needs its own {@link WithdrawalDepositEnabledProfile} to re-enable
 * {@code requestWithdrawal}, so its amount-validation tests below (negative/zero/positive/below-
 * minimum) keep exercising exactly what they always tested: the input validation itself,
 * independent of whether the feature is currently turned on. D-53 update: {@code requestDeposit}
 * itself is now PERMANENTLY retired regardless of this profile's {@code herd.deposit.enabled=true}
 * — see {@code requestDepositIsPermanentlyRetiredEvenWhenDepositEnabledIsTrue} below and {@code
 * GameService#requestDeposit}'s javadoc.
 */
@QuarkusTest
@TestProfile(RpcValidationTest.WithdrawalDepositEnabledProfile.class)
class RpcValidationTest {

    public static class WithdrawalDepositEnabledProfile implements QuarkusTestProfile {
        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of("herd.deposit.enabled", "true", "herd.withdrawal.enabled", "true");
        }
    }

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

    // --- task item 3 (security minor): AuthService must never echo chainsvc's raw exception
    // text to a client ---

    @Test
    void authVerifyNeverEchoesChainsvcsRawExceptionMessage() {
        String internalLeak = "connection refused: chainsvc-internal-host.invalid:8090 (secret detail)";
        org.mockito.Mockito.reset(chainSvcClient);
        org.mockito.Mockito.when(chainSvcClient.authVerify(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException(internalLeak));

        Response challengeResp = given().contentType(ContentType.JSON)
                .body(Map.of("args", List.of()))
                .post("/api/rpc/authChallenge");
        String nonce = challengeResp.jsonPath().getString("nonce");
        Map<String, Object> verifyReq = Map.of(
                "nonce", nonce,
                "address", "txch1leak-test-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "publicKey", "0x" + "ab".repeat(48),
                "signature", "0x" + "cd".repeat(96));
        Response resp = given().contentType(ContentType.JSON)
                .body(Map.of("args", List.of(verifyReq)))
                .post("/api/rpc/authVerify");

        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(401);
        assertThat(resp.asString()).doesNotContain(internalLeak).doesNotContain("chainsvc-internal-host");
        assertThat(resp.jsonPath().getString("error.message")).isEqualTo(
                "sign-in temporarily unavailable — please try again shortly");
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
        // D-53 update: requestWithdrawal now escrows-and-submits-for-real rather than
        // auto-confirming — chainSvcClient.submitPlayWithdrawal(...) is unstubbed here (returns
        // null, Mockito's default for a record-returning method), which GameService treats exactly
        // like a chainsvc outage (leaves the request REQUESTED for WithdrawalPollerService to
        // retry) — the escrow debit below is unaffected either way.
        String session = signIn("txch1wd-positive-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "requestWithdrawal", "500.000");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
        assertThat(resp.jsonPath().getString("state")).isEqualTo("REQUESTED");
        String balanceAfter = rpc(session, "myAccount").jsonPath().getString("balance");
        assertThat(balanceAfter).isEqualTo("9500.000");
    }

    @Test
    void requestWithdrawalRejectsBelowMinimum() {
        // D-53: minimum withdrawal (proposal, GameService.MIN_WITHDRAWAL) — checked after the
        // positive-amount check, before the balance check.
        String session = signIn("txch1wd-below-min-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "requestWithdrawal", "50.000");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(422);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("WITHDRAWAL_BELOW_MINIMUM");
        String balanceAfter = rpc(session, "myAccount").jsonPath().getString("balance");
        assertThat(balanceAfter).as("nothing must be debited for a rejected withdrawal").isEqualTo("10000.000");
    }

    // --- D-53: requestDeposit (the pre-D-53 self-report RPC) is now PERMANENTLY retired —
    // unconditionally, even under this class's own herd.deposit.enabled=true profile (see
    // GameService#requestDeposit's javadoc for why turning the real on-chain deposit path on must
    // never also reopen the self-report fraud vector task item 1 closed). This single test replaces
    // the amount-validation tests this method used to have (negative/zero/positive) — there is no
    // more input validation to speak of once the RPC is unconditionally rejected before ever
    // parsing its argument. See WithdrawalDepositUnavailableTest for the equivalent default-profile
    // (both flags false) coverage. ---

    @Test
    void requestDepositIsPermanentlyRetiredEvenWhenDepositEnabledIsTrue() {
        String session = signIn("txch1dep-retired-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String balanceBefore = rpc(session, "myAccount").jsonPath().getString("balance");

        Response resp = rpc(session, "requestDeposit", "250.000");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(503);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("DEPOSIT_VERIFICATION_UNAVAILABLE");

        String balanceAfter = rpc(session, "myAccount").jsonPath().getString("balance");
        assertThat(balanceAfter).as("nothing must be credited — requestDeposit never credits again")
                .isEqualTo(balanceBefore);
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

    // --- security review 2026-10-22: a number in exponent form ("1e100000000") made the first setScale compute a power of ten with
    // that many digits - about a minute of CPU on the thread that asked (for a bet: the core writer's logic thread). Plain decimal
    // notation only, at most 40 characters, refused before anything is computed ---

    private static final String EXPONENT_BOMB = "1e100000000";

    private static void assertRefusedFast(Response resp, long startedNanos) {
        long tookMs = (System.nanoTime() - startedNanos) / 1_000_000;
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("BAD_REQUEST");
        assertThat(tookMs).as("refused before any arithmetic").isLessThan(5_000);
    }

    @Test
    void previewLiquidationRefusesAStakeInExponentFormAtOnce() {
        long t0 = System.nanoTime();
        Response resp = rpc(null, "previewLiquidation", Map.of("direction", "UP", "leverage", 2, "stake", EXPONENT_BOMB));
        assertRefusedFast(resp, t0);
    }

    @Test
    void previewLiquidationRefusesAReferencePriceInExponentFormAtOnce() {
        long t0 = System.nanoTime();
        Response resp = rpc(null, "previewLiquidation", Map.of("direction", "UP", "leverage", 2, "stake", "100.000",
                "referencePrice", EXPONENT_BOMB));
        assertRefusedFast(resp, t0);
    }

    @Test
    void placeBetRefusesAStakeInExponentFormOrTooLongAtOnce() {
        String session = signIn("txch1bet-exponent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String bettingRoundId = rpc(session, "rounds", "HOURLY").jsonPath().getString("betting.roundId");
        for (String stake : List.of(EXPONENT_BOMB, "1E+100000000", "100" + "0".repeat(60))) {
            long t0 = System.nanoTime();
            Response resp = rpc(session, "placeBet", Map.of("roundId", bettingRoundId, "direction", "UP", "leverage", 2,
                    "stake", stake));
            assertRefusedFast(resp, t0);
            assertThat(resp.asString()).as("the client's text is not echoed in full").hasSizeLessThan(400);
        }
    }

    @Test
    void requestWithdrawalRefusesAnAmountInExponentFormAtOnce() {
        String session = signIn("txch1wd-exponent-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        long t0 = System.nanoTime();
        Response resp = rpc(session, "requestWithdrawal", EXPONENT_BOMB);
        assertRefusedFast(resp, t0);
    }

    @Test
    void aMissingOrUnknownIntervalIsABadRequestNotAnInternalError() {
        for (String raw : List.of("{\"args\":[null]}", "{\"args\":[]}", "{\"args\":[\"NOPE\"]}", "{\"args\":[{\"a\":1}]}")) {
            Response resp = rpcRaw(null, "rounds", raw);
            assertThat(resp.statusCode()).as(raw + " -> " + resp.asString()).isEqualTo(400);
            assertThat(resp.jsonPath().getString("error.code")).isEqualTo("BAD_REQUEST");
            assertThat(resp.asString()).doesNotContain("crowdvsmarket").doesNotContain("Interval");
        }
    }

    @Test
    void anUnknownEnumOrANullIntervalInARequestObjectIsABadRequest() {
        Response unknown = rpc(null, "previewLiquidation", Map.of("interval", "NOPE", "direction", "UP", "leverage", 2));
        assertThat(unknown.statusCode()).as(unknown.asString()).isEqualTo(400);
        assertThat(unknown.asString()).doesNotContain("crowdvsmarket");

        java.util.Map<String, Object> nulls = new java.util.HashMap<>();
        nulls.put("interval", null);
        nulls.put("direction", null);
        Response nullInterval = rpc(null, "previewLiquidation", nulls);
        assertThat(nullInterval.statusCode()).as(nullInterval.asString()).isEqualTo(400);
    }

    @Test
    void aBodyOverTheLimitIsRefusedBeforeItIsParsed() {
        Response resp = given().contentType(ContentType.JSON)
                .body("{\"args\":[\"" + "x".repeat(70 * 1024) + "\"]}").post("/api/rpc/rounds");
        assertThat(resp.statusCode()).isEqualTo(413);
    }

    @Test
    void aBodyAsLargeAsTheLargestLegitimateCallIsAccepted() {
        // submitFeedback's text is at most 2000 characters; acknowledgeResults lists round ids of 36 characters
        Response ack = rpc(null, "acknowledgeResults", java.util.Collections.nCopies(500, "r".repeat(36)));
        assertThat(ack.statusCode()).as("not signed in, but not too large: " + ack.asString()).isEqualTo(401);
        Response feedback = rpc(null, "submitFeedback", Map.of("message", "m".repeat(2000), "page", "/"));
        assertThat(feedback.statusCode()).as(feedback.asString()).isNotEqualTo(413);
    }
}
