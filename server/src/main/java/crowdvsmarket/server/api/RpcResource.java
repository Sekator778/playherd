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
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.util.Set;

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

    /** Task item 2 (wave-1 security findings): "add a simple per-IP rate limit on
     *  authChallenge/authVerify/placeBet" — the three RPC methods a single caller could otherwise
     *  hammer for free (no session required for the first two; placeBet is the one mutating,
     *  balance-affecting call under a signed-in session). See {@link RateLimiter}. */
    private static final Set<String> RATE_LIMITED_METHODS = Set.of("authChallenge", "authVerify", "placeBet");

    @Inject
    ObjectMapper mapper;
    @Inject
    GameService gameService;
    @Inject
    AuthService authService;
    @Inject
    SessionService sessions;
    @Inject
    RateLimiter rateLimiter;

    @POST
    @Path("/{method}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response call(@PathParam("method") String method, String body,
                          @CookieParam(SESSION_COOKIE) String sessionCookie,
                          @Context HttpServerRequest httpRequest) {
        if (RATE_LIMITED_METHODS.contains(method) && !rateLimiter.tryAcquire(clientIp(httpRequest) + ":" + method)) {
            throw ApiException.rateLimited();
        }
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
                Auth.AuthVerifyRequest req = requiredObj(args, 0, Auth.AuthVerifyRequest.class);
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
            case "previewLiquidation" -> result = gameService.previewLiquidation(requiredObj(args, 0, Betting.LiquidationPreviewRequest.class));
            case "placeBet" -> {
                requireSignedIn(playerId);
                result = gameService.placeBet(playerId, requiredObj(args, 0, Betting.PlaceBetRequest.class), now);
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

    /** Wave-1 finding: {@code request} is {@code null} in a unit context without a live HTTP
     *  exchange (never in production); {@code remoteAddress()} can itself be {@code null} for a
     *  connection that dropped before Vert.x recorded a peer address. Either way this must never
     *  throw — a rate limiter degrading to "treat as one shared anonymous bucket" is acceptable,
     *  silently skipping the limit entirely on this codepath is not. */
    private static String clientIp(HttpServerRequest request) {
        if (request == null || request.remoteAddress() == null) {
            return "unknown";
        }
        return request.remoteAddress().host();
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
            // Bug fix (wave-1 finding): a generic message, never e.getMessage() — Jackson's own
            // parse-error text can otherwise echo back arbitrary/unbounded client input.
            throw ApiException.badRequest("invalid JSON body");
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
        if (n.isNull()) {
            return null;
        }
        try {
            return mapper.convertValue(n, type);
        } catch (IllegalArgumentException e) {
            // Bug fix (wave-1 finding): convertValue wraps Jackson's own mapping failure as an
            // IllegalArgumentException whose message names the target Java class/field (e.g.
            // "…crowdvsmarket.server.dto.Betting$PlaceBetRequest…") — never surfaced to a client.
            throw ApiException.badRequest("invalid request body");
        }
    }

    /** Same as {@link #obj}, but a missing/{@code null} arg — e.g. a request body of {@code
     *  {"args":[]}} or {@code {"args":[null]}} — is itself a client error, not a value for the
     *  handler to interpret (wave-1 finding: authVerify/previewLiquidation/placeBet used to pass
     *  a {@code null} request record straight into code that immediately dereferences one of its
     *  components, turning a bad request into an uncaught {@code NullPointerException} → 500). */
    private <T> T requiredObj(JsonNode args, int i, Class<T> type) {
        T value = obj(args, i, type);
        if (value == null) {
            throw ApiException.badRequest("missing request body");
        }
        return value;
    }

    private Paging.PageParams pageParams(JsonNode args, int i) {
        Paging.PageParams p = obj(args, i, Paging.PageParams.class);
        return p == null ? new Paging.PageParams(null, null) : p;
    }
}
