package crowdvsmarket.server.chain;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/** FIXED INTERFACE (3) verbatim — the raw MicroProfile REST Client to chainsvc (port 8090). Wrapped
 *  by {@link ChainSvcClientImpl} into the mockable {@link ChainSvcClient}. */
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
    AuthVerifyResponse authVerify(AuthVerifyRequest request);

    record OracleRecordJson(long seq, long ts, long priceCents, String resultsHash, String source, long sourceTs) {
    }

    record OracleRecordsRequest(List<OracleRecordJson> records) {
    }

    record OracleRecordsResponse(int queued) {
    }

    @POST
    @Path("/v1/oracle/records")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    OracleRecordsResponse oracleRecords(OracleRecordsRequest request);

    record OracleStatusResponse(String launcherId, String network, Long lastConfirmedSeq, Long lastConfirmedHeight,
                                  int pendingRecords, String inFlightBundleId, String operatorBalanceMojos) {
    }

    @GET
    @Path("/v1/oracle/status")
    @Produces(MediaType.APPLICATION_JSON)
    OracleStatusResponse oracleStatus();

    record HealthResponse(boolean ok) {
    }

    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    HealthResponse health();
}
