package crowdvsmarket.server.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import crowdvsmarket.server.auth.AuthService;
import crowdvsmarket.server.auth.SessionService;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Auth;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Feedback;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.jfr.HerdRpc;
import crowdvsmarket.server.service.CandleService;
import crowdvsmarket.server.service.FeedbackService;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.service.MarketViewService;
import crowdvsmarket.server.service.PriceSeriesService;
import crowdvsmarket.server.service.ServerInfoService;
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
import java.util.List;
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
     *  balance-affecting call under a signed-in session) — and {@code cancelBet}, which takes the engine's
     *  write lock like {@code placeBet} does and was one more call a signed-in player (the browser test wallet
     *  makes any visitor one) could repeat for free, a thousand times a second (review of the core, finding 9).
     *  See {@link RateLimiter}.
     *
     *  <p>The read methods of the markets ({@code markets}, {@code priceSeries}, {@code myPositions}, {@code
     *  crowdOverview}) are deliberately NOT in this set: every open lobby and room POLLS them every 2–3 s
     *  (a per-IP bucket of 30 refilling at 0.5 a second would throttle a single honest tab, and every player
     *  behind one NAT), and each is cheap by construction — answered from memory, {@code markets} from a
     *  cache of about a second, {@code priceSeries} at most 600 points, {@code myPositions} for the one
     *  session's own bets. Only what mutates, or can be hammered for free into a database or a chain call,
     *  is limited. */
    private static final Set<String> RATE_LIMITED_METHODS =
            Set.of("authChallenge", "authVerify", "placeBet", "cancelBet");

    /** Р-81: in-app feedback. Open to everyone (a visitor who is not signed in can send it) and written to the database,
     *  so it has a per-IP budget of its own, far stricter than the shared one ({@code herd.feedback.rate-limit.*}); a cap
     *  on everyone together per day is {@code FeedbackService}'s. Not in {@link #RATE_LIMITED_METHODS}: that set is the
     *  shared budget. */
    private static final String FEEDBACK_METHOD = "submitFeedback";

    @Inject
    ObjectMapper mapper;
    @Inject
    GameService gameService;
    @Inject
    MarketViewService marketView;
    @Inject
    PriceSeriesService priceSeriesService;
    @Inject
    CandleService candleService;
    @Inject
    ServerInfoService serverInfoService;
    @Inject
    FeedbackService feedbackService;
    @Inject
    AuthService authService;
    @Inject
    SessionService sessions;
    @Inject
    RateLimiter rateLimiter;
    @Inject
    GameConfig config;

    @POST
    @Path("/{method}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response call(@PathParam("method") String method, String body,
                          @CookieParam(SESSION_COOKIE) String sessionCookie,
                          @Context HttpServerRequest httpRequest) {
        HerdRpc jfr = new HerdRpc(); // I-6: the server's own time for the call, to set against the client's (Little's law)
        boolean rec = jfr.isEnabled();
        if (rec) {
            jfr.begin();
            jfr.method = method;
            jfr.result = "ERROR";
        }
        try {
            Response response = dispatch(method, body, sessionCookie, httpRequest);
            jfr.result = String.valueOf(response.getStatus());
            return response;
        } catch (ApiException e) {
            jfr.result = e.code().name();
            throw e;
        } finally {
            if (rec) {
                jfr.end();
                jfr.commit();
            }
        }
    }

    private Response dispatch(String method, String body, String sessionCookie, HttpServerRequest httpRequest) {
        if (RATE_LIMITED_METHODS.contains(method) && !rateLimiter.tryAcquire(clientIp(httpRequest) + ":" + method)) {
            throw ApiException.rateLimited();
        }
        if (FEEDBACK_METHOD.equals(method) && !rateLimiter.tryAcquire(clientIp(httpRequest) + ":" + method,
                config.feedbackRateLimitCapacity(), config.feedbackRateLimitRefillPerHour() / 3600.0)) {
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
                AuthService.VerifyResult vr = authService.verify(req, clientIp(httpRequest));
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
            // The markets read model (§8): `markets` and `crowdOverview` are public, `priceSeries(marketId,
            // sinceMs?)` too; `myPositions` is the signed-in player's own. None is rate-limited (see
            // RATE_LIMITED_METHODS: they are polled).
            case "markets" -> result = marketView.markets(now);
            case "priceSeries" -> result = priceSeriesService.priceSeries(requiredStr(args, 0, "marketId"),
                    optionalLong(args, 1, "sinceMs"), now);
            // `candles(marketId, minutes?)`: the finished 1-minute candles (a read-model of the price ticks, memory only,
            // public and polled like priceSeries, so not rate-limited either).
            case "candles" -> result = candleService.candles(requiredStr(args, 0, "marketId"),
                    optionalMinutes(args, 1, "minutes"), now);
            case "myPositions" -> {
                requireSignedIn(playerId);
                result = marketView.myPositions(playerId, now);
            }
            case "crowdOverview" -> result = marketView.crowdOverview();
            // `marketId` is the optional second argument (omitted → BTC, docs/research/markets_architecture.md §8).
            case "rounds" -> result = gameService.roundsPair(Enums.Interval.valueOf(str(args, 0)), str(args, 1), playerId);
            case "round" -> result = gameService.round(str(args, 0), playerId);
            // Р-62: public, no session required — same visibility as the round itself during BETTING.
            case "betFeed" -> result = gameService.betFeed(str(args, 0), longOrNull(args, 1));
            case "btcReferencePrice" -> result = gameService.btcReferencePrice();
            case "referencePrice" -> result = gameService.referencePrice(requiredStr(args, 0, "marketId"));
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
            // Р-53: additive, not in contract.ts (same "additive" situation as ledgerCorrections
            // above) — the real on-chain deposit address a player sends PLAY to; deposits credit
            // automatically (DepositPollerService), no further RPC needed on the player's end.
            case "myDepositAddress" -> {
                requireSignedIn(playerId);
                result = gameService.myDepositAddress(playerId);
            }
            case "myInvite" -> {
                requireSignedIn(playerId);
                result = gameService.myInvite(playerId);
            }
            case "crowdAccount" -> result = gameService.crowdAccount(str(args, 0));
            case "ratings" -> result = gameService.ratings(str(args, 0));
            case "publicProfile" -> result = gameService.publicProfile(str(args, 0), playerId);
            case "myProfile" -> {
                requireSignedIn(playerId);
                result = gameService.publicProfile(playerId, playerId);
            }
            // Р-62 "while you were away" — RPC method names match contract.ts's `Api.unseenResults`/
            // `Api.acknowledgeResults` exactly (web already wrote+wired these against MockApi; the
            // real implementation just has to answer under the same names for RealApi to work
            // unchanged once it calls them).
            case "unseenResults" -> {
                requireSignedIn(playerId);
                result = gameService.myUnseenResults(playerId);
            }
            case "acknowledgeResults" -> {
                requireSignedIn(playerId);
                gameService.ackResults(playerId, strList(args, 0), now);
                result = java.util.Map.of();
            }
            case "cancellations" -> result = gameService.cancellations(pageParams(args, 0));
            // Wave-2 security fix: public, additive (not in contract.ts, same "additive" situation
            // as the extra ApiErrorCode values above) — the public audit log for operator ledger
            // corrections (Р-32/Р-43 pattern), unauthenticated exactly like "cancellations".
            case "ledgerCorrections" -> result = gameService.ledgerCorrections(pageParams(args, 0));
            case "home" -> result = gameService.home(playerId);
            // Р-81: public, no session required (the player's id is stored when there is one); additive (contract.ts:
            // submitFeedback). The client's address is used for the country and then dropped — never stored or logged.
            case FEEDBACK_METHOD -> result = feedbackService.submit(requiredObj(args, 0, Feedback.SubmitRequest.class),
                    playerId, clientIp(httpRequest), now);
            case "serverInfo" -> result = serverInfoService.serverInfo();
            // Economy stage C (C5): public, no session; the supply and where it sits, never HERD on its own (Р-90)
            case "supply" -> result = gameService.supply(now);
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

    /** Task item 3 (security minor): {@code Secure} follows {@code herd.cookie.secure}
     *  (GameConfig) — {@code false} for the local http-only Docker stand (Р-44), must be set
     *  {@code true} once this is ever deployed behind TLS (Р-38 VPS), so the session cookie is
     *  never sent in cleartext over the open internet. */
    private NewCookie sessionCookie(String sessionId) {
        return new NewCookie.Builder(SESSION_COOKIE)
                .value(sessionId)
                .path("/")
                .httpOnly(true)
                .secure(config.cookieSecure())
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge((int) (7L * 24 * 3600))
                .build();
    }

    private NewCookie clearSessionCookie() {
        return new NewCookie.Builder(SESSION_COOKIE).value("").path("/").httpOnly(true)
                .secure(config.cookieSecure()).maxAge(0).build();
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

    /** A string arg that must be there — a missing/{@code null} one is a client error, not a value
     *  for the handler to interpret (same reasoning as {@link #requiredObj}). */
    private String requiredStr(JsonNode args, int i, String name) {
        String value = str(args, i);
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("missing " + name);
        }
        return value;
    }

    /** Optional numeric arg — {@code null} when omitted/JSON {@code null} (e.g. {@code betFeed}'s
     *  {@code sinceSeq}, first-page call). */
    private Long longOrNull(JsonNode args, int i) {
        JsonNode n = at(args, i);
        return n.isNull() ? null : n.asLong();
    }

    /** Optional integral arg ({@code priceSeries}' {@code sinceMs}): omitted or JSON {@code null} is {@code null};
     *  a number that is not an integer, or anything that is not a number, is a client error — unlike {@link
     *  #longOrNull}'s lenient {@code asLong()}, under which {@code "abc"} would silently be 0 and ask for every
     *  tick there is. */
    private Long optionalLong(JsonNode args, int i, String name) {
        JsonNode n = at(args, i);
        if (n.isNull()) {
            return null;
        }
        if (!n.isIntegralNumber() || !n.canConvertToLong()) {
            throw ApiException.badRequest("invalid " + name);
        }
        return n.asLong();
    }

    /** Optional whole-number arg that fits an {@code int} ({@code candles}' {@code minutes}): omitted or JSON {@code null} is
     *  {@code null}; anything else that is not such a number is a client error. */
    private Integer optionalMinutes(JsonNode args, int i, String name) {
        Long value = optionalLong(args, i, name);
        if (value == null) {
            return null;
        }
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw ApiException.badRequest("invalid " + name);
        }
        return value.intValue();
    }

    /** {@code string[]} arg (e.g. {@code ackResults}' {@code roundIds}) — an absent/{@code null}
     *  arg is an empty list, not an error (mirrors {@link #pageParams}'s "missing means default"
     *  treatment), since {@code ackResults([])} is itself already a defined no-op
     *  (see {@code RoundResultRepository.ackResults}'s javadoc). */
    private List<String> strList(JsonNode args, int i) {
        JsonNode n = at(args, i);
        if (n.isNull()) {
            return List.of();
        }
        try {
            return mapper.convertValue(n, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
            });
        } catch (IllegalArgumentException e) {
            // Same wave-1-finding reasoning as obj()/requiredObj() above: never echo Jackson's own
            // mapping-failure message (it can name internal types) back to the client.
            throw ApiException.badRequest("invalid request body");
        }
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
