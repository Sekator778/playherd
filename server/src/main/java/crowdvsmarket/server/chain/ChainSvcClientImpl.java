package crowdvsmarket.server.chain;

import crowdvsmarket.server.config.GameConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/** Default {@link ChainSvcClient}: a thin adapter over {@link ChainSvcRestClient} — never lets a
 *  chainsvc outage propagate as an exception to game logic (task: "never block the game on chain
 *  confirmation"); callers get {@code null}/best-effort results instead. */
@ApplicationScoped
public class ChainSvcClientImpl implements ChainSvcClient {

    private static final Logger LOG = Logger.getLogger(ChainSvcClientImpl.class);

    @RestClient
    ChainSvcRestClient rest;
    @Inject
    GameConfig config;

    /** FIXED INTERFACE (a): {@code Authorization: Bearer <C2_SVC_TOKEN>} on every {@code /v1/*}
     *  call. Built fresh per call (not cached) — negligible cost, and means a token rotated via
     *  config (MicroProfile Config's own reload, e.g. in a test) takes effect immediately without
     *  needing to also invalidate a cached value here. */
    private String bearerToken() {
        return "Bearer " + config.chainsvcToken();
    }

    @Override
    public AuthVerifyResult authVerify(String address, String publicKey, String message, String signature) {
        ChainSvcRestClient.AuthVerifyResponse response = rest.authVerify(bearerToken(),
                new ChainSvcRestClient.AuthVerifyRequest(address, publicKey, message, signature));
        return new AuthVerifyResult(response.valid(), response.reason());
    }

    @Override
    public OracleRecordsResult oracleRecords(List<OracleRecord> records) {
        try {
            List<ChainSvcRestClient.OracleRecordJson> json = records.stream()
                    .map(r -> new ChainSvcRestClient.OracleRecordJson(r.seq(), r.ts(), r.priceCents(),
                            r.resultsHash(), r.source(), r.sourceTs()))
                    .toList();
            ChainSvcRestClient.OracleRecordsResponse response =
                    rest.oracleRecords(bearerToken(), new ChainSvcRestClient.OracleRecordsRequest(json));
            List<Long> accepted = response.acceptedSeqs() == null ? List.of() : response.acceptedSeqs();
            return new OracleRecordsResult(response.queued(), accepted);
        } catch (Exception e) {
            LOG.warnf(e, "chainsvc /v1/oracle/records unreachable — will retry next tick");
            return new OracleRecordsResult(0, List.of());
        }
    }

    @Override
    public OracleRecordState oracleRecordStatus(long seq) {
        try {
            ChainSvcRestClient.OracleRecordStateResponse r = rest.oracleRecordStatus(bearerToken(), seq);
            return new OracleRecordState(r.seq(), r.state(), r.height(), r.coinId());
        } catch (Exception e) {
            LOG.debugf(e, "chainsvc /v1/oracle/records/%d unreachable", seq);
            return null;
        }
    }

    @Override
    public OracleStatus oracleStatus() {
        try {
            ChainSvcRestClient.OracleStatusResponse r = rest.oracleStatus(bearerToken());
            return new OracleStatus(r.launcherId(), r.network(), r.lastConfirmedSeq(), r.lastConfirmedHeight(),
                    r.pendingRecords(), r.inFlightBundleId(), r.operatorBalanceMojos(), r.stuckReason(),
                    r.conflicts() == null ? List.of() : r.conflicts());
        } catch (Exception e) {
            LOG.debugf(e, "chainsvc /v1/oracle/status unreachable");
            return null;
        }
    }

    @Override
    public boolean health() {
        try {
            return rest.health().ok();
        } catch (Exception e) {
            return false;
        }
    }
}
