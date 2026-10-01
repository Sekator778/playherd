package crowdvsmarket.server.chain;

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

    /** {@code asset}: the record's registry index (record format 2), always sent — 0 for BTC. A chain
     *  service older than format 2 ignores the key (its DTO has no {@code deny_unknown_fields}). */
    record OracleRecordJson(long seq, long ts, long priceCents, String resultsHash, String source, long sourceTs,
                              int asset) {
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

    /** {@code stuckReason}/{@code conflicts}: task item 5 (contract test Java<->Rust) fix — a
     *  real field-drift the new contract-fixture test caught: chainsvc's actual
     *  {@code OracleStatusResponse} (chain/c2-svc/src/oracle/types.rs) has carried these since its
     *  §11/§12 postmortem fixes, silently dropped on the Java side by Jackson's default
     *  ignore-unknown-properties behavior until now. The last four fields are record format 2's
     *  ({@code maxKnownSeq}, {@code recordFormat}, {@code feeCoinsFree}, {@code feeCoinsFreeMojos},
     *  docs/research/chain_service.md §16.3): boxed, so a chainsvc that predates them reads as
     *  {@code null}, not 0 — "absent" and "zero" mean different things for every one of them. */
    record OracleStatusResponse(String launcherId, String network, Long lastConfirmedSeq, Long lastConfirmedHeight,
                                  int pendingRecords, String inFlightBundleId, String operatorBalanceMojos,
                                  String stuckReason, List<Long> conflicts, Long maxKnownSeq, Integer recordFormat,
                                  Integer feeCoinsFree, String feeCoinsFreeMojos) {
    }

    @GET
    @Path("/v1/oracle/status")
    @Produces(MediaType.APPLICATION_JSON)
    OracleStatusResponse oracleStatus(@HeaderParam("Authorization") String authorization);

    // --- Р-53: real on-chain PLAY withdraw/deposit (FIXED INTERFACE, /v1/play/*) ---

    record PlayWithdrawalRequest(String requestId, String toAddress, long amountMojos) {
    }

    record PlayWithdrawalAcceptedResponse(boolean accepted) {
    }

    @POST
    @Path("/v1/play/withdrawals")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    PlayWithdrawalAcceptedResponse submitPlayWithdrawal(@HeaderParam("Authorization") String authorization,
                                                          PlayWithdrawalRequest request);

    record PlayWithdrawalStatusResponse(String requestId, String state, String coinId, Long height, String reason) {
    }

    @GET
    @Path("/v1/play/withdrawals/{requestId}")
    @Produces(MediaType.APPLICATION_JSON)
    PlayWithdrawalStatusResponse playWithdrawalStatus(@HeaderParam("Authorization") String authorization,
                                                        @PathParam("requestId") String requestId);

    record PlayDepositAddressRequest(int playerIndex) {
    }

    record PlayDepositAddressResponse(String address, String innerPuzzleHash, String catPuzzleHash) {
    }

    @POST
    @Path("/v1/play/deposit-address")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    PlayDepositAddressResponse playDepositAddress(@HeaderParam("Authorization") String authorization,
                                                    PlayDepositAddressRequest request);

    record PlayDepositItem(String coinId, int playerIndex, long amountMojos, long height) {
    }

    record PlayDepositsResponse(List<PlayDepositItem> deposits, long tipHeight) {
    }

    @GET
    @Path("/v1/play/deposits")
    @Produces(MediaType.APPLICATION_JSON)
    PlayDepositsResponse playDeposits(@HeaderParam("Authorization") String authorization,
                                        @QueryParam("sinceHeight") long sinceHeight);

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
