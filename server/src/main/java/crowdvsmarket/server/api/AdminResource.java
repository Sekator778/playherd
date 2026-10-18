package crowdvsmarket.server.api;

import crowdvsmarket.server.bots.BotMetrics;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Feedback;
import crowdvsmarket.server.dto.Ledger;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.dto.ServerInfo;
import crowdvsmarket.server.prices.PriceSource;
import crowdvsmarket.server.prices.PriceSources;
import crowdvsmarket.server.service.FeedbackService;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.service.ServerInfoService;
import crowdvsmarket.server.util.ConstantTimeTokens;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Р-43: operator admin endpoints, protected by a bearer token from {@code HERD_OPERATOR_TOKEN}
 * (predложение stand-in — "the token is a stand-in until operator-key signing", see
 * docs/research/server_design.md). No web admin UI (Р-43: "Веб-админки нет") — this plus
 * {@code tools/herd-operator.sh} is the whole operator surface.
 */
@Path("/api/admin")
public class AdminResource {

    private static final Logger LOG = Logger.getLogger(AdminResource.class);

    @Inject
    GameConfig config;
    @Inject
    GameService gameService;
    @Inject
    ServerInfoService serverInfoService;
    @Inject
    PriceSources priceSources;
    @Inject
    FeedbackService feedbackService;
    @Inject
    BotMetrics botMetrics;

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

    /**
     * The operator's status page. It is the page the operator needs MOST while something is wrong, so it answers
     * during an outage: {@code server.engineReady} says whether the engine is ready, the list of ledger
     * corrections — which is read from the engine and answers 503 {@code ENGINE_NOT_READY} while it is not — is
     * then absent ({@code null}) instead of failing the whole page, and so is the list of cancellations when the
     * database does not answer (review of the core, finding 2).
     */
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminStatusDto status(@HeaderParam("Authorization") String auth) {
        requireOperator(auth);
        ServerInfo.ServerInfoDto info = serverInfoService.serverInfo();
        java.util.List<Rounds.CancellationDto> cancellations = null;
        try {
            cancellations = gameService.cancellations(new Paging.PageParams(20, null)).items();
        } catch (RuntimeException e) {
            LOG.warnf(e, "the operator status could not list the cancellations");
        }
        // Wave-2 security fix: same "journal of exceptional operator actions" pattern as
        // recentCancellations, for ledger corrections (Р-32/Р-43).
        java.util.List<Ledger.LedgerCorrectionDto> corrections = null;
        try {
            corrections = gameService.ledgerCorrections(new Paging.PageParams(20, null)).items();
        } catch (ApiException e) {
            if (e.code() != Enums.ApiErrorCode.ENGINE_NOT_READY) {
                throw e;
            }
        }
        // Economy stage B (plan B6): the flag only, no amounts; null while the engine cannot say (and before the cutover it is false)
        Boolean breaker = null;
        try {
            breaker = gameService.circuitBreakerOpen(System.currentTimeMillis());
        } catch (ApiException e) {
            if (e.code() != Enums.ApiErrorCode.ENGINE_NOT_READY) {
                throw e;
            }
        }
        return new AdminStatusDto(info, cancellations, corrections, priceFeeds(), breaker, botDays(),
                gameService.missingRows());
    }

    /**
     * Economy stage B (plan B7): the books against the chain - what was issued (100 000 000 PLAY), the PLAY treasury coin on chain, TREASURY,
     * HERD, what the players hold, what was withdrawn - and whether they agree ({@code booksBalanced}, {@code chainConsistent}); a mismatch is
     * also a WARN in the log. Operator only: it shows HERD, the platform's revenue, which no public screen does (Р-90).
     */
    @GET
    @Path("/reconciliation")
    @Produces(MediaType.APPLICATION_JSON)
    public crowdvsmarket.server.dto.Economy.ReconciliationDto reconciliation(@HeaderParam("Authorization") String auth) {
        requireOperator(auth);
        return gameService.reconciliation(System.currentTimeMillis());
    }

    /**
     * Р-81: the feedback visitors left in the app, newest first — the operator's way to read it (no web admin UI, Р-43;
     * {@code tools/herd-operator.sh feedback}). {@code sinceMs}: only messages received at or after that epoch-ms
     * (default: all); {@code limit}: at most that many, default {@value FeedbackService#DEFAULT_LIST_LIMIT}, at most
     * {@value FeedbackService#MAX_LIST_LIMIT}. Each item: {@code id}, {@code receivedAtMs}, {@code message}, {@code
     * country} (from the IP address, which is not stored), {@code timeZone}, {@code language}, {@code page}, {@code
     * client}, {@code playerId}, {@code nickname}, {@code roundsPlayed} (see {@link Feedback.FeedbackItemDto}). The two
     * parameters are read as text so that a typo is a {@code 400} with a message, not a bare {@code 404}.
     */
    @GET
    @Path("/feedback")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Feedback.FeedbackItemDto> feedback(@HeaderParam("Authorization") String auth,
                                                   @QueryParam("sinceMs") String sinceMs,
                                                   @QueryParam("limit") String limit) {
        requireOperator(auth);
        return feedbackService.list(parseLong(sinceMs, "sinceMs"), parseInt(limit, "limit"));
    }

    private static Long parseLong(String text, String name) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(text.strip());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(name + " must be a whole number");
        }
    }

    private static Integer parseInt(String text, String name) {
        Long value = parseLong(text, name);
        if (value == null) {
            return null;
        }
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    /** What the bots did on each of the last {@value #BOT_DAYS} UTC days; {@code null} while the database does not answer. */
    private java.util.List<BotMetrics.Day> botDays() {
        try {
            return botMetrics.lastDays(System.currentTimeMillis(), BOT_DAYS);
        } catch (RuntimeException e) {
            LOG.warnf(e, "the operator status could not read the bots' days");
            return null;
        }
    }

    private static final int BOT_DAYS = 7;

    /** What the price feeds are doing — memory only, so the page answers whatever is wrong elsewhere. */
    private java.util.List<PriceSource.FeedStatus> priceFeeds() {
        try {
            return priceSources.statuses(System.currentTimeMillis());
        } catch (RuntimeException e) {
            LOG.warnf(e, "the operator status could not list the price feeds");
            return null;
        }
    }

    /** {@code recentCancellations} is {@code null} while the database does not answer, {@code
     *  recentLedgerCorrections} while the engine is not ready ({@code server.engineReady} is {@code false}).
     *  {@code priceFeeds}: one row per price feed (Р-76) — {@code feed}, {@code venue}, {@code mode} ({@code poll} or
     *  {@code stream}), {@code state} ({@code polling}; for a stream {@code connected}, {@code silent},
     *  {@code rest-fallback}, {@code idle}; {@code disabled} for a venue in {@code herd.prices.disabled-venues};
     *  {@code synthetic}), {@code lastMessageAtMs} and {@code restReads} (how many markets the last poll read over REST
     *  because the stream had no quote). {@code botDays}: one row per UTC day of the last seven, oldest first - {@code bets} by
     *  bots with their {@code staked} PLAY and {@code fees}, the {@code pnl} of the bets that settled that day (payout minus stake,
     *  before fees), {@code retirements}, {@code botsCreated} (first bots and replacements together) and the {@code starterPacks}
     *  they were paid; {@code null} while the database does not answer. {@code missingRoundRows}: rounds that are resolved in the
     *  journal and whose result rows could not be written so far ({@code roundId}, {@code failures}, {@code sinceMs}, {@code lastError}),
     *  each retried in the background; empty when all is written. Not part of {@code contract.ts}: the operator's own page. */
    public record AdminStatusDto(ServerInfo.ServerInfoDto server, java.util.List<Rounds.CancellationDto> recentCancellations,
                                   java.util.List<Ledger.LedgerCorrectionDto> recentLedgerCorrections,
                                   java.util.List<PriceSource.FeedStatus> priceFeeds, Boolean circuitBreakerOpen,
                                   java.util.List<BotMetrics.Day> botDays,
                                   java.util.List<GameService.MissingRows> missingRoundRows) {

        public AdminStatusDto(ServerInfo.ServerInfoDto server, java.util.List<Rounds.CancellationDto> recentCancellations,
                              java.util.List<Ledger.LedgerCorrectionDto> recentLedgerCorrections,
                              java.util.List<PriceSource.FeedStatus> priceFeeds, Boolean circuitBreakerOpen,
                              java.util.List<BotMetrics.Day> botDays) {
            this(server, recentCancellations, recentLedgerCorrections, priceFeeds, circuitBreakerOpen, botDays, null);
        }

        public AdminStatusDto(ServerInfo.ServerInfoDto server, java.util.List<Rounds.CancellationDto> recentCancellations,
                              java.util.List<Ledger.LedgerCorrectionDto> recentLedgerCorrections,
                              java.util.List<PriceSource.FeedStatus> priceFeeds, Boolean circuitBreakerOpen) {
            this(server, recentCancellations, recentLedgerCorrections, priceFeeds, circuitBreakerOpen, null);
        }

        public AdminStatusDto(ServerInfo.ServerInfoDto server, java.util.List<Rounds.CancellationDto> recentCancellations,
                              java.util.List<Ledger.LedgerCorrectionDto> recentLedgerCorrections,
                              java.util.List<PriceSource.FeedStatus> priceFeeds) {
            this(server, recentCancellations, recentLedgerCorrections, priceFeeds, null);
        }
    }
}
