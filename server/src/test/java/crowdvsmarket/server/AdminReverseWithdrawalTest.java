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
 * Wave-2 security fix (item 2): the operator "reverse withdrawal" correction — reverses a
 * withdrawal request's already-recorded balance/season effect (e.g. the exploited
 * {@code requestWithdrawal("-5000.000")}) via {@code POST /api/admin/withdrawals/{id}/reverse},
 * without altering or deleting the original event, and lists it publicly (with reason) via the
 * unauthenticated {@code ledgerCorrections} RPC method — the same "public log" pattern
 * {@code cancellations} already uses for round cancellations (Р-32/Р-43).
 *
 * <p>Task item 1 (BLOCKER security fix): {@code gameService.requestWithdrawal} is disabled by
 * default (see {@code RpcValidationTest.WithdrawalDepositEnabledProfile}) — needs the same
 * {@link WithdrawalEnabledProfile} override so this class's fixture setup (creating a withdrawal
 * request to then reverse) keeps working; the reversal mechanism under test here is independent
 * of whether new withdrawals are currently allowed.
 */
@QuarkusTest
@TestProfile(AdminReverseWithdrawalTest.WithdrawalEnabledProfile.class)
class AdminReverseWithdrawalTest {

    public static class WithdrawalEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("herd.withdrawal.enabled", "true");
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
        String wallet = "txch1reverse" + label + UUID.randomUUID().toString().replace("-", "");
        return gameService.registerOrTouchPlayer(wallet, now).player().playerId();
    }

    private Response callReverse(String requestId, String reason) {
        Map<String, Object> body = reason == null ? Map.of() : Map.of("reason", reason);
        return given().contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + operatorToken)
                .body(body)
                .post("/api/admin/withdrawals/" + requestId + "/reverse");
    }

    @Test
    void reverseWithoutOperatorTokenIsUnauthorized() {
        given().contentType(ContentType.JSON).body(Map.of("reason", "x"))
                .post("/api/admin/withdrawals/w-does-not-matter/reverse")
                .then().statusCode(401);
    }

    @Test
    void reverseWithBlankReasonIsRejected() {
        String playerId = signUpPlayer("blank-reason");
        long now = System.currentTimeMillis();
        var request = gameService.requestWithdrawal(playerId, "500.000", now);

        Response resp = callReverse(request.requestId(), "  ");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(400);
    }

    @Test
    void reverseOfUnknownRequestIdIs404() {
        Response resp = callReverse("w-does-not-exist-12345", "test reason");
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(404);
    }

    @Test
    void reverseUndoesTheWithdrawalsEffectAndIsPublicallyListedWithReason() {
        String playerId = signUpPlayer("undo");
        long now = System.currentTimeMillis();
        var request = gameService.requestWithdrawal(playerId, "1500.000", now);
        assertThat(request.state().name()).isEqualTo("CONFIRMED");

        String reason = "wave-2 test correction: undo withdrawal " + request.requestId();
        Response resp = callReverse(request.requestId(), reason);
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
        assertThat(resp.jsonPath().getString("playerId")).isEqualTo(playerId);
        assertThat(resp.jsonPath().getString("relatedRequestId")).isEqualTo(request.requestId());
        assertThat(resp.jsonPath().getString("reason")).isEqualTo(reason);
        // delta is the exact inverse of the original withdrawal (a credit of the same magnitude
        // back to the balance it was debited from)
        assertThat(new java.math.BigDecimal(resp.jsonPath().getString("delta")))
                .isEqualByComparingTo("1500.000");

        // balance is restored (the 1500 debit is undone)
        var account = gameService.account(playerId);
        assertThat(new java.math.BigDecimal(account.balance())).isEqualByComparingTo("10000.000");

        // the original withdrawal request itself is untouched — still CONFIRMED, still on record
        var stillOnRecord = gameService.withdrawal(playerId, request.requestId());
        assertThat(stillOnRecord.state().name()).isEqualTo("CONFIRMED");
        assertThat(stillOnRecord.amount()).isEqualTo("1500.000");

        // publicly listed with reason via the unauthenticated ledgerCorrections RPC — no session,
        // no operator token, same "public log" pattern as cancellations
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
        var request = gameService.requestWithdrawal(playerId, "200.000", now);

        Response first = callReverse(request.requestId(), "first correction");
        assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);

        Response second = callReverse(request.requestId(), "second correction");
        assertThat(second.statusCode()).as(second.asString()).isEqualTo(400);
    }

    /**
     * Regression test for a real live-stand crash found in wave-3 retesting: the operator
     * "reverse withdrawal" correction persists a {@code GameEvent.WithdrawalReversed} row, but
     * {@code EventStore#decode} was never given a case for it — so every restart after the very
     * first live reversal replayed that row via {@link EngineRebuildService#rebuild()} (the exact
     * same code path a real process restart uses) and threw
     * {@code IllegalStateException: unknown game_events.event_type: WithdrawalReversed},
     * permanently crash-looping the server (fixed by adding the missing
     * {@code case "WithdrawalReversed"} to {@code EventStore#decode}'s switch). This test drives
     * that same rebuild path directly, right after a reversal, so this can never silently regress
     * again without failing here first.
     */
    @Test
    void reversalSurvivesEngineRebuildReplay() {
        String playerId = signUpPlayer("rebuild-replay");
        long now = System.currentTimeMillis();
        var request = gameService.requestWithdrawal(playerId, "750.000", now);

        String reason = "wave-3 regression test: rebuild must replay WithdrawalReversed";
        Response resp = callReverse(request.requestId(), reason);
        assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);

        // same code path EngineRebuildService.onStart() uses on every real process restart —
        // must not throw (it did, in production, before EventStore#decode learned this event type)
        engineRebuildService.rebuild();

        // state survives the rebuild intact
        var account = gameService.account(playerId);
        assertThat(new java.math.BigDecimal(account.balance())).isEqualByComparingTo("10000.000");
        var stillOnRecord = gameService.withdrawal(playerId, request.requestId());
        assertThat(stillOnRecord.state().name()).isEqualTo("CONFIRMED");

        Response publicList = given().contentType(ContentType.JSON).body(Map.of("args", List.of()))
                .post("/api/rpc/ledgerCorrections");
        List<Map<String, Object>> items = publicList.jsonPath().getList("items");
        boolean found = items.stream().anyMatch(item -> request.requestId().equals(item.get("relatedRequestId")));
        assertThat(found).as("correction must still be listed after a rebuild replay").isTrue();
    }
}
