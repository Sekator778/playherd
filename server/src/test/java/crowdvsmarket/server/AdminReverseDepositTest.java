package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Ledger;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.WithdrawalDepositRepository;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import crowdvsmarket.server.service.GameService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Task item 1 (BLOCKER security fix, an internal security review): the
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
 * <p>D-53 update: {@code gameService.requestDeposit} (the self-report RPC this reversal mechanism
 * was originally built to clean up after) is now PERMANENTLY retired — see its own javadoc — so
 * this class's fixtures can no longer be created through it. {@link #legacySelfReportedDeposit}
 * reconstructs exactly the same historical event shape ({@code DepositRequested} +
 * {@code DepositConfirmed}) directly through {@link EventStore}/{@link EventApplier}, the same
 * primitives {@code GameService.requestDeposit} used before retirement — this class's whole point
 * is the *reversal* mechanism for that now-frozen historical shape (the real d-2/d-4/d-7/d-10 fraud
 * rows on the live stand), independent of whether the retired self-report entry point is still
 * reachable from the API. {@link DepositEnabledProfile} is kept (harmless) though no longer load-
 * bearing for the fixture helper itself.
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

    @Inject
    EventStore eventStore;
    @Inject
    EventApplier eventApplier;
    @Inject
    Db db;
    @Inject
    WithdrawalDepositRepository withdrawalDeposits;

    private String signUpPlayer(String label) {
        long now = System.currentTimeMillis();
        String wallet = "txch1revdep" + label + UUID.randomUUID().toString().replace("-", "");
        return gameService.registerOrTouchPlayer(wallet, now).player().playerId();
    }

    /** See the class javadoc: replays exactly what the now-retired {@code GameService
     *  .requestDeposit} used to do (self-report request, immediately auto-confirmed), so this
     *  test class can still build the historical fixture shape its reversal tests exercise. */
    private Ledger.DepositRequestDto legacySelfReportedDeposit(String playerId, String amount, long nowMs) {
        PlayAmount amt = PlayAmount.fromMoney(new BigDecimal(amount));
        GameEvent.DepositRequested requested = new GameEvent.DepositRequested(playerId, amt.mojos(), nowMs);
        db.tx(c -> {
            eventStore.append(c, requested, nowMs);
            return null;
        });
        String requestId = (String) eventApplier.apply(requested);
        db.tx(c -> {
            withdrawalDeposits.insertDeposit(c, requestId, playerId, amt.mojos(), nowMs);
            return null;
        });
        GameEvent.DepositConfirmed confirmed = new GameEvent.DepositConfirmed(requestId, nowMs);
        db.tx(c -> {
            eventStore.append(c, confirmed, nowMs);
            return null;
        });
        eventApplier.apply(confirmed);
        db.tx(c -> {
            withdrawalDeposits.updateDepositState(c, requestId, Enums.DepositState.CONFIRMED);
            return null;
        });
        // the events were applied outside the core writer: the read views are published from the engine, as the writer does after a commit
        gameService.runExclusively(gameService::publishAllViews);
        return withdrawalDeposits.findDeposit(requestId).orElseThrow();
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
        var request = legacySelfReportedDeposit(playerId, "500.000", now);

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
        var request = legacySelfReportedDeposit(playerId, "5000000.000", now);
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
        var request = legacySelfReportedDeposit(playerId, "200.000", now);

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
        var request = legacySelfReportedDeposit(playerId, "750.000", now);

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
