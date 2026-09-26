package crowdvsmarket.server.api;

import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Ledger;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.dto.ServerInfo;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.util.ConstantTimeTokens;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Р-43: operator admin endpoints, protected by a bearer token from {@code HERD_OPERATOR_TOKEN}
 * (predложение stand-in — "the token is a stand-in until operator-key signing", see
 * docs/research/server_design.md). No web admin UI (Р-43: "Веб-админки нет") — this plus
 * {@code tools/herd-operator.sh} is the whole operator surface.
 */
@Path("/api/admin")
public class AdminResource {

    @Inject
    GameConfig config;
    @Inject
    GameService gameService;

    private void requireOperator(String authorizationHeader) {
        String expected = "Bearer " + config.operatorToken();
        // Bug fix (wave-1 finding): compares fixed-length digests (ConstantTimeTokens), not the
        // raw header/token strings — a raw-length short-circuit before the constant-time loop
        // would leak the token's length to a timing attacker.
        if (authorizationHeader == null || !ConstantTimeTokens.equals(authorizationHeader, expected)) {
            throw ApiException.unauthorized("missing or invalid operator bearer token");
        }
    }

    public record CancelRoundRequest(String reason) {
    }

    @POST
    @Path("/rounds/{roundId}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Rounds.CancellationDto cancelRound(@HeaderParam("Authorization") String auth,
                                                @PathParam("roundId") String roundId, CancelRoundRequest body) {
        requireOperator(auth);
        String reason = body == null || body.reason() == null || body.reason().isBlank()
                ? "cancelled by operator" : body.reason();
        return gameService.cancelRoundById(roundId, reason, System.currentTimeMillis());
    }

    public record ReverseWithdrawalRequest(String reason) {
    }

    /** Wave-2 security fix (Р-32/Р-43 pattern): reverses a withdrawal request's already-recorded
     *  balance/season effect (e.g. the {@code requestWithdrawal("-5000.000")} exploit) without
     *  altering or deleting the original event — {@code reason} is mandatory and becomes part of
     *  the public, replayable {@code LedgerCorrection} record ({@code GameService#reverseWithdrawal}
     *  / RpcResource's public {@code ledgerCorrections} method, unauthenticated like {@code
     *  cancellations}). */
    @POST
    @Path("/withdrawals/{requestId}/reverse")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Ledger.LedgerCorrectionDto reverseWithdrawal(@HeaderParam("Authorization") String auth,
                                                          @PathParam("requestId") String requestId,
                                                          ReverseWithdrawalRequest body) {
        requireOperator(auth);
        String reason = body == null ? null : body.reason();
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason is required");
        }
        return gameService.reverseWithdrawal(requestId, reason, System.currentTimeMillis());
    }

    public record ReverseDepositRequest(String reason) {
    }

    /** Task item 1 (BLOCKER security fix): reverses a deposit request's already-recorded
     *  (fraudulent) balance/season effect — e.g. the self-reported deposits {@code
     *  d-2}/{@code d-4}/{@code d-7}/{@code d-10}, auto-confirmed with zero on-chain verification
     *  before {@code GameService#requestDeposit} started rejecting new deposits outright — without
     *  altering or deleting the original event. Exact mirror of {@link #reverseWithdrawal}. */
    @POST
    @Path("/deposits/{requestId}/reverse")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Ledger.LedgerCorrectionDto reverseDeposit(@HeaderParam("Authorization") String auth,
                                                        @PathParam("requestId") String requestId,
                                                        ReverseDepositRequest body) {
        requireOperator(auth);
        String reason = body == null ? null : body.reason();
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("reason is required");
        }
        return gameService.reverseDeposit(requestId, reason, System.currentTimeMillis());
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminStatusDto status(@HeaderParam("Authorization") String auth) {
        requireOperator(auth);
        ServerInfo.ServerInfoDto info = gameService.serverInfo();
        Paging.Page<Rounds.CancellationDto> recentCancellations = gameService.cancellations(new Paging.PageParams(20, null));
        // Wave-2 security fix: same "journal of exceptional operator actions" pattern as
        // recentCancellations, for ledger corrections (Р-32/Р-43).
        Paging.Page<Ledger.LedgerCorrectionDto> recentCorrections = gameService.ledgerCorrections(new Paging.PageParams(20, null));
        return new AdminStatusDto(info, recentCancellations.items(), recentCorrections.items());
    }

    public record AdminStatusDto(ServerInfo.ServerInfoDto server, java.util.List<Rounds.CancellationDto> recentCancellations,
                                   java.util.List<Ledger.LedgerCorrectionDto> recentLedgerCorrections) {
    }
}
