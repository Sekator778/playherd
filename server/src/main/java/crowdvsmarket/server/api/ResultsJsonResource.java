package crowdvsmarket.server.api;

import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.persistence.RoundResultRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** FIXED INTERFACE (1)/(2): {@code GET /api/rounds/{roundId}/results.json} — public verification
 *  endpoint. Returns the exact bytes that were sha256'd into {@code resultsHash} (D-41: "итоги
 *  публично лежат на сайте — любой может сверить, что их не меняли задним числом"). */
@Path("/api/rounds")
public class ResultsJsonResource {

    @Inject
    RoundResultRepository roundResults;

    @GET
    @Path("/{roundId}/results.json")
    @Produces(MediaType.APPLICATION_JSON)
    public Response results(@PathParam("roundId") String roundId) {
        String json = roundResults.canonicalJson(roundId)
                .orElseThrow(() -> ApiException.notFound("no results for round " + roundId + " yet"));
        return Response.ok(json).build();
    }
}
