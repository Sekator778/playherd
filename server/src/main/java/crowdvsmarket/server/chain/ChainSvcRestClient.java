package crowdvsmarket.server.chain;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** FIXED INTERFACE (3) verbatim — the raw MicroProfile REST Client to chainsvc (port 8090). Wrapped
 *  by {@link ChainSvcClientImpl} into the mockable {@link ChainSvcClient}.
 *
 *  <p>FIXED INTERFACE (a): every method below except {@link #health()} takes the bearer token as
 *  its own explicit {@code Authorization} header parameter (rather than e.g. a
 *  {@code ClientRequestFilter}) — {@link ChainSvcClientImpl} builds it once from {@code
 *  GameConfig#chainsvcToken()} and passes it explicitly, which keeps this interface a plain,
 *  stateless description of the wire contract and the token itself out of any REST-client-level
 *  provider/filter machinery. */
@RegisterRestClient(configKey = "chainsvc")
public interface ChainSvcRestClient {

    record AuthVerifyRequest(String address, String publicKey, String message, String signature) {
    }

    record AuthVerifyResponse(boolean valid, String reason) {
    }

    @POST
    @Path("/v1/auth/verify")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    AuthVerifyResponse authVerify(@HeaderParam("Authorization") String authorization, AuthVerifyRequest request);

    record OracleRecordJson(long seq, long ts, long priceCents, String resultsHash, String source, long sourceTs) {
    }

    record OracleRecordsRequest(List<OracleRecordJson> records) {
    }

    /** FIXED INTERFACE (b): {@code {queued:int, acceptedSeqs:[int]}}. */
    record OracleRecordsResponse(int queued, List<Long> acceptedSeqs) {
    }

    @POST
    @Path("/v1/oracle/records")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    OracleRecordsResponse oracleRecords(@HeaderParam("Authorization") String authorization,
                                         OracleRecordsRequest request);

    /** FIXED INTERFACE (b): {@code GET /v1/oracle/records/{seq}} →
     *  {@code {seq, state, height, coinId}}. */
    record OracleRecordStateResponse(long seq, String state, Long height, String coinId) {
    }

    @GET
    @Path("/v1/oracle/records/{seq}")
    @Produces(MediaType.APPLICATION_JSON)
    OracleRecordStateResponse oracleRecordStatus(@HeaderParam("Authorization") String authorization,
                                                   @PathParam("seq") long seq);

    record OracleStatusResponse(String launcherId, String network, Long lastConfirmedSeq, Long lastConfirmedHeight,
                                  int pendingRecords, String inFlightBundleId, String operatorBalanceMojos) {
    }

    @GET
    @Path("/v1/oracle/status")
    @Produces(MediaType.APPLICATION_JSON)
    OracleStatusResponse oracleStatus(@HeaderParam("Authorization") String authorization);

    record HealthResponse(boolean ok) {
    }

    /** FIXED INTERFACE (a) explicitly exempts this one endpoint from auth ("every /v1/* endpoint
     *  except GET /health requires…", and this isn't under {@code /v1} either) — no {@code
     *  Authorization} header sent here, on purpose. */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    HealthResponse health();
}
