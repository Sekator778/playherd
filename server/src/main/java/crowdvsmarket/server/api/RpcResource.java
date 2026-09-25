package crowdvsmarket.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import crowdvsmarket.server.auth.AuthService;
import crowdvsmarket.server.auth.SessionService;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Auth;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.service.GameService;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

/**
 * FIXED INTERFACE (1): every method {@code m} of {@code Api} (web/src/api/contract.ts) is served
 * as {@code POST /api/rpc/m} with JSON body {@code {"args":[...]}}; success → 200 with the DTO
 * JSON exactly as typed in contract.ts (void → {@code {}}); failure → 4xx/5xx with
 * {@code {"error":{"code","message"}}} (see {@link ApiExceptionMapper}). Session = httpOnly cookie
 * {@code herd_session} set by {@code authVerify}.
 */
@Path("/api/rpc")
public class RpcResource {

    private static final String SESSION_COOKIE = "herd_session";

    @Inject
    ObjectMapper mapper;
    @Inject
    GameService gameService;
    @Inject
    AuthService authService;
    @Inject
    SessionService sessions;

    @POST
    @Path("/{method}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response call(@PathParam("method") String method, String body,
                          @CookieParam(SESSION_COOKIE) String sessionCookie) {
        JsonNode args = argsArray(body);
        String playerId = sessions.playerIdFor(sessionCookie).orElse(null);
        long now = System.currentTimeMillis();

        Object result;
        NewCookie setCookie = null;
        NewCookie clearCookie = null;

        switch (method) {
            case "authChallenge" -> {
                Auth.AuthChallengeRequest req = obj(args, 0, Auth.AuthChallengeRequest.class);
                result = authService.challenge(req == null ? new Auth.AuthChallengeRequest(null) : req);
            }
            case "authVerify" -> {
                Auth.AuthVerifyRequest req = obj(args, 0, Auth.AuthVerifyRequest.class);
                AuthService.VerifyResult vr = authService.verify(req);
                result = vr.session();
                setCookie = sessionCookie(vr.sessionId());
            }
            case "currentPlayer" -> result = playerId == null ? null : gameService.summary(playerId);
            case "setNickname" -> {
                requireSignedIn(playerId);
                result = gameService.setNickname(playerId, str(args, 0));
            }
            case "signOut" -> {
                sessions.destroy(sessionCookie);
                clearCookie = clearSessionCookie();
                result = java.util.Map.of();
            }
            case "rounds" -> result = gameService.roundsPair(Enums.Interval.valueOf(str(args, 0)), playerId);
            case "round" -> result = gameService.round(str(args, 0), playerId);
            case "btcReferencePrice" -> result = gameService.btcReferencePrice();
            case "previewLiquidation" -> result = gameService.previewLiquidation(obj(args, 0, Betting.LiquidationPreviewRequest.class));
            case "placeBet" -> {
                requireSignedIn(playerId);
                result = gameService.placeBet(playerId, obj(args, 0, Betting.PlaceBetRequest.class), now);
            }
            case "cancelBet" -> {
                requireSignedIn(playerId);
                gameService.cancelBet(playerId, str(args, 0), now);
                result = java.util.Map.of();
            }
            case "myAccount" -> {
                requireSignedIn(playerId);
                result = gameService.account(playerId);
            }
            case "mySeasonRecord" -> {
                requireSignedIn(playerId);
                result = gameService.seasonRecord(playerId, str(args, 0));
            }
            case "myLedger" -> {
                requireSignedIn(playerId);
                result = gameService.myLedger(playerId, pageParams(args, 0));
            }
            case "requestWithdrawal" -> {
                requireSignedIn(playerId);
                result = gameService.requestWithdrawal(playerId, str(args, 0), now);
            }
            case "withdrawal" -> {
                requireSignedIn(playerId);
                result = gameService.withdrawal(playerId, str(args, 0));
            }
            case "requestDeposit" -> {
                requireSignedIn(playerId);
                result = gameService.requestDeposit(playerId, str(args, 0), now);
            }
            case "deposit" -> {
                requireSignedIn(playerId);
                result = gameService.deposit(playerId, str(args, 0));
            }
            case "crowdAccount" -> result = gameService.crowdAccount();
            case "ratings" -> result = gameService.ratings(str(args, 0));
            case "publicProfile" -> result = gameService.publicProfile(str(args, 0));
            case "myProfile" -> {
                requireSignedIn(playerId);
                result = gameService.publicProfile(playerId);
            }
            case "cancellations" -> result = gameService.cancellations(pageParams(args, 0));
            case "home" -> result = gameService.home(playerId);
            case "serverInfo" -> result = gameService.serverInfo();
            default -> throw ApiException.notFound("unknown RPC method: " + method);
        }

        // A null result is a real JSON null (e.g. currentPlayer with no session), not {}:
        // void methods already set an explicit empty map above.
        Response.ResponseBuilder builder = result == null
                ? Response.ok("null", MediaType.APPLICATION_JSON_TYPE)
                : Response.ok(result);
        if (setCookie != null) {
            builder.cookie(setCookie);
        }
        if (clearCookie != null) {
            builder.cookie(clearCookie);
        }
        return builder.build();
    }

    private static void requireSignedIn(String playerId) {
        if (playerId == null) {
            throw ApiException.notSignedIn();
        }
    }

    private NewCookie sessionCookie(String sessionId) {
        return new NewCookie.Builder(SESSION_COOKIE)
                .value(sessionId)
                .path("/")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge((int) (7L * 24 * 3600))
                .build();
    }

    private NewCookie clearSessionCookie() {
        return new NewCookie.Builder(SESSION_COOKIE).value("").path("/").httpOnly(true).maxAge(0).build();
    }

    // --- args[] parsing helpers ---

    private JsonNode argsArray(String body) {
        try {
            JsonNode root = (body == null || body.isBlank()) ? mapper.createObjectNode() : mapper.readTree(body);
            JsonNode args = root.get("args");
            return args != null && args.isArray() ? args : mapper.createArrayNode();
        } catch (Exception e) {
            throw ApiException.badRequest("invalid JSON body: " + e.getMessage());
        }
    }

    private JsonNode at(JsonNode args, int i) {
        JsonNode n = i < args.size() ? args.get(i) : null;
        return n == null ? NullNode.getInstance() : n;
    }

    private String str(JsonNode args, int i) {
        JsonNode n = at(args, i);
        return n.isNull() ? null : n.asText();
    }

    private <T> T obj(JsonNode args, int i, Class<T> type) {
        JsonNode n = at(args, i);
        return n.isNull() ? null : mapper.convertValue(n, type);
    }

    private Paging.PageParams pageParams(JsonNode args, int i) {
        Paging.PageParams p = obj(args, i, Paging.PageParams.class);
        return p == null ? new Paging.PageParams(null, null) : p;
    }
}
