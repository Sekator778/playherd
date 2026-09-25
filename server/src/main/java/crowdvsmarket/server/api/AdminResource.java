package crowdvsmarket.server.api;

import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.dto.ServerInfo;
import crowdvsmarket.server.service.GameService;
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
        if (authorizationHeader == null || !constantTimeEquals(authorizationHeader, expected)) {
            throw ApiException.unauthorized("missing or invalid operator bearer token");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
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

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public AdminStatusDto status(@HeaderParam("Authorization") String auth) {
        requireOperator(auth);
        ServerInfo.ServerInfoDto info = gameService.serverInfo();
        Paging.Page<Rounds.CancellationDto> recentCancellations = gameService.cancellations(new Paging.PageParams(20, null));
        return new AdminStatusDto(info, recentCancellations.items());
    }

    public record AdminStatusDto(ServerInfo.ServerInfoDto server, java.util.List<Rounds.CancellationDto> recentCancellations) {
    }
}
