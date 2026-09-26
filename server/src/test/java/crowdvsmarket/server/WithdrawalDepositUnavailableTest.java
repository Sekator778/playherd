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
 * Task item 1 (BLOCKER security fix, docs/reviews/2026-09-26-independent-critique.md): {@code
 * requestDeposit} used to credit any self-reported amount with zero on-chain verification (live-
 * reproduced: 5,000,000 fake PLAY minted in ten seconds), and {@code requestWithdrawal}
 * auto-confirmed with no chain call, falsely claiming a CAT left the game. This class runs with NO
 * {@code @TestProfile} override, so it exercises the application's real default config
 * (application.properties: {@code herd.deposit.enabled}/{@code herd.withdrawal.enabled} both
 * default {@code false}) — both must be rejected outright, for every amount, with zero balance
 * effect. See {@code RpcValidationTest}/{@code AdminReverseWithdrawalTest}/{@code
 * AdminReverseDepositTest} for the companion "still testable with the feature explicitly turned
 * back on via a profile" coverage.
 */
@QuarkusTest
class WithdrawalDepositUnavailableTest {

    @InjectMock
    ChainSvcClient chainSvcClient;

    @BeforeEach
    void stubChainSvc() {
        org.mockito.Mockito.when(chainSvcClient.authVerify(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ChainSvcClient.AuthVerifyResult(true, null));
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
        return given().contentType(ContentType.JSON).body(Map.of("args", List.of(args)))
                .cookie("herd_session", sessionId)
                .post("/api/rpc/" + method);
    }

    @Test
    void requestDepositIsRejectedRegardlessOfAmountAndCreditsNothing() {
        String session = signIn("txch1dep-unavail-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String balanceBefore = rpc(session, "myAccount").jsonPath().getString("balance");

        Response resp = rpc(session, "requestDeposit", "5000000.000");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(503);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("DEPOSIT_VERIFICATION_UNAVAILABLE");

        String balanceAfter = rpc(session, "myAccount").jsonPath().getString("balance");
        assertThat(balanceAfter).as("nothing must be credited while deposits are unavailable")
                .isEqualTo(balanceBefore);
    }

    @Test
    void requestWithdrawalIsRejectedRegardlessOfAmountAndDebitsNothing() {
        String session = signIn("txch1wd-unavail-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        String balanceBefore = rpc(session, "myAccount").jsonPath().getString("balance");
        assertThat(balanceBefore).isEqualTo("10000.000");

        Response resp = rpc(session, "requestWithdrawal", "500.000");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(503);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("WITHDRAWAL_UNAVAILABLE");

        String balanceAfter = rpc(session, "myAccount").jsonPath().getString("balance");
        assertThat(balanceAfter).as("nothing must be debited while withdrawals are unavailable")
                .isEqualTo(balanceBefore);
    }

    @Test
    void requestWithdrawalIsUnavailableEvenForANegativeAmount() {
        // Defense-in-depth: the "unavailable" gate is checked BEFORE amount parsing/validation,
        // so it can never be bypassed by sending malformed input that might hit a different code
        // path — every requestWithdrawal call gets the same 503, full stop.
        String session = signIn("txch1wd-unavail-neg-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Response resp = rpc(session, "requestWithdrawal", "-5000.000");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(503);
        assertThat(resp.jsonPath().getString("error.code")).isEqualTo("WITHDRAWAL_UNAVAILABLE");
    }
}
