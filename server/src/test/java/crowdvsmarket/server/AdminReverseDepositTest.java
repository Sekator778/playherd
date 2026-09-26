package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.scheduler.EngineRebuildService;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Task item 1 (BLOCKER security fix, docs/reviews/2026-09-26-independent-critique.md): the
 * operator "reverse deposit" correction — reverses a deposit request's already-recorded
 * (fraudulent) balance/season effect (the exact exploit reproduced live: {@code requestDeposit}
 * credited any self-reported amount with zero on-chain verification, e.g. the fake confirmed
 * deposits {@code d-2}/{@code d-4}/{@code d-7}/{@code d-10} on the running stand) via
 * {@code POST /api/admin/deposits/{id}/reverse}, without altering or deleting the original event,
 * and lists it publicly (with reason) via the unauthenticated {@code ledgerCorrections} RPC method
 * — the exact mirror of {@link AdminReverseWithdrawalTest} for deposits (same {@code
 * LedgerCorrection}/{@code relatedRequestId} shape, see that class and {@code
 * crowdvsmarket.engine.ledger.LedgerBook#reverseDepositEffect}'s javadoc).
 *
 * <p>{@code gameService.requestDeposit} is disabled by default (task item 1's other half) —
 * needs {@link DepositEnabledProfile} so this class's fixture setup (creating a deposit request to
 * then reverse) keeps working; the reversal mechanism under test here is independent of whether
 * new deposits are currently allowed — which is exactly why the operator can still reverse the
 * historical d-2/d-4/d-7/d-10 rows even after this fix disables new ones.
 */
@QuarkusTest
@TestProfile(AdminReverseDepositTest.DepositEnabledProfile.class)
class AdminReverseDepositTest {

    public static class DepositEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("herd.deposit.enabled", "true");
        }
    }

    @ConfigProperty(name = "herd.operator.token")
    String operatorToken;

    @Inject
    GameService gameService;

    @Inject
    EngineRebuildService engineRebuildService;

    private String signUpPlayer(String label) {
        long now = System.currentTimeMillis();
        String wallet = "txch1revdep" + label + UUID.randomUUID().toString().replace("-", "");
        return gameService.registerOrTouchPlayer(wallet, now).player().playerId();
    }

    private Response callReverse(String requestId, String reason) {
        Map<String, Object> body = reason == null ? Map.of() : Map.of("reason", reason);
        return given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + operatorToken)
                .body(body)
                .post("/api/admin/deposits/" + requestId + "/reverse");
    }

    @Test
    void reverseWithoutOperatorTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(Map.of("reason", "x"))
                .post("/api/admin/deposits/d-does-not-matter/reverse")
                .then().statusCode(401);
    }

    @Test
    void reverseWithBlankReasonIsRejected() {
        String playerId = signUpPlayer("blank-reason");
        long now = System.currentTimeMillis();
        var request = gameService.requestDeposit(playerId, "500.000", now);

        Response resp = callReverse(request.requestId(), "  ");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
    }

    @Test
    void reverseOfUnknownRequestIdIs404() {
        Response resp = callReverse("d-does-not-exist-12345", "test reason");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(404);
    }

    @Test
    void reverseUndoesTheDepositsEffectAndIsPublicallyListedWithReason() {
        String playerId = signUpPlayer("undo");
        long now = System.currentTimeMillis();
        var request = gameService.requestDeposit(playerId, "5000000.000", now);
        assertThat(request.state().name()).isEqualTo("CONFIRMED");

        // the fraudulent deposit really did credit the balance, same as the live reproduction
        var creditedAccount = gameService.account(playerId);
        assertThat(new java.math.BigDecimal(creditedAccount.balance())).isEqualByComparingTo("5010000.000");

        String reason = "task item 1 test correction: undo fraudulent deposit " + request.requestId();
        Response resp = callReverse(request.requestId(), reason);
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
        assertThat(resp.jsonPath().getString("playerId")).isEqualTo(playerId);
        assertThat(resp.jsonPath().getString("relatedRequestId")).isEqualTo(request.requestId());
        assertThat(resp.jsonPath().getString("reason")).isEqualTo(reason);
        // delta is a debit of the exact original credited amount
        assertThat(new java.math.BigDecimal(resp.jsonPath().getString("delta")))
                .isEqualByComparingTo("-5000000.000");

        // balance is restored (the 5,000,000 fraudulent credit is undone, starter pack remains)
        var account = gameService.account(playerId);
        assertThat(new java.math.BigDecimal(account.balance())).isEqualByComparingTo("10000.000");

        // the original deposit request itself is untouched — still CONFIRMED, still on record
        var stillOnRecord = gameService.deposit(playerId, request.requestId());
        assertThat(stillOnRecord.state().name()).isEqualTo("CONFIRMED");
        assertThat(stillOnRecord.amount()).isEqualTo("5000000.000");

        // publicly listed with reason via the unauthenticated ledgerCorrections RPC — no session,
        // no operator token, same "public log" pattern as cancellations/reverse-withdrawal
        Response publicList = given().contentType(ContentType.JSON).body(Map.of("args", List.of()))
                .post("/api/rpc/ledgerCorrections");
        assertThat(publicList.statusCode()).as(publicList.asString()).isEqualTo(200);
        List<Map<String, Object>> items = publicList.jsonPath().getList("items");
        boolean found = items.stream().anyMatch(item ->
                request.requestId().equals(item.get("relatedRequestId"))
                        && reason.equals(item.get("reason")));
        assertThat(found).as("expected the correction to be publicly listed with its reason").isTrue();
    }

    @Test
    void reverseCannotBeAppliedTwiceForTheSameRequestId() {
        String playerId = signUpPlayer("twice");
        long now = System.currentTimeMillis();
        var request = gameService.requestDeposit(playerId, "200.000", now);

        Response first = callReverse(request.requestId(), "first correction");
        assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);

        Response second = callReverse(request.requestId(), "second correction");
        assertThat(second.statusCode()).as(second.asString()).isEqualTo(400);
    }

    /**
     * Regression test (task item 1's own explicit requirement: "make EventStore handle the new
     * event types on replay — a regression test that rebuilds after a reversal") — the exact
     * pattern {@link AdminReverseWithdrawalTest#reversalSurvivesEngineRebuildReplay} already
     * pins down for {@code WithdrawalReversed}, mirrored for the new {@code DepositReversed} event
     * type: drives the same rebuild path a real process restart uses
     * ({@code EngineRebuildService#rebuild}) right after a deposit reversal, so a missing
     * {@code EventStore#decode} case for it can never silently regress into a crash-looping
     * startup again.
     */
    @Test
    void reversalSurvivesEngineRebuildReplay() {
        String playerId = signUpPlayer("rebuild-replay");
        long now = System.currentTimeMillis();
        var request = gameService.requestDeposit(playerId, "750.000", now);

        String reason = "regression test: rebuild must replay DepositReversed";
        Response resp = callReverse(request.requestId(), reason);
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);

        // same code path EngineRebuildService.onStart() uses on every real process restart —
        // must not throw (this is exactly the failure mode WithdrawalReversed hit before
        // EventStore#decode learned that event type — see that class's regression test)
        engineRebuildService.rebuild();

        // state survives the rebuild intact
        var account = gameService.account(playerId);
        assertThat(new java.math.BigDecimal(account.balance())).isEqualByComparingTo("10000.000");
        var stillOnRecord = gameService.deposit(playerId, request.requestId());
        assertThat(stillOnRecord.state().name()).isEqualTo("CONFIRMED");

        Response publicList = given().contentType(ContentType.JSON).body(Map.of("args", List.of()))
                .post("/api/rpc/ledgerCorrections");
        List<Map<String, Object>> items = publicList.jsonPath().getList("items");
        boolean found = items.stream().anyMatch(item -> request.requestId().equals(item.get("relatedRequestId")));
        assertThat(found).as("correction must still be listed after a rebuild replay").isTrue();
    }
}
