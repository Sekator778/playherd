package crowdvsmarket.server.chain;

import jakarta.enterprise.context.ApplicationScoped;
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

    @Override
    public AuthVerifyResult authVerify(String address, String publicKey, String message, String signature) {
        ChainSvcRestClient.AuthVerifyResponse response = rest.authVerify(
                new ChainSvcRestClient.AuthVerifyRequest(address, publicKey, message, signature));
        return new AuthVerifyResult(response.valid(), response.reason());
    }

    @Override
    public int oracleRecords(List<OracleRecord> records) {
        try {
            List<ChainSvcRestClient.OracleRecordJson> json = records.stream()
                    .map(r -> new ChainSvcRestClient.OracleRecordJson(r.seq(), r.ts(), r.priceCents(),
                            r.resultsHash(), r.source(), r.sourceTs()))
                    .toList();
            ChainSvcRestClient.OracleRecordsResponse response =
                    rest.oracleRecords(new ChainSvcRestClient.OracleRecordsRequest(json));
            return response.queued();
        } catch (Exception e) {
            LOG.warnf(e, "chainsvc /v1/oracle/records unreachable — will retry next tick");
            return 0;
        }
    }

    @Override
    public OracleStatus oracleStatus() {
        try {
            ChainSvcRestClient.OracleStatusResponse r = rest.oracleStatus();
            return new OracleStatus(r.launcherId(), r.network(), r.lastConfirmedSeq(), r.lastConfirmedHeight(),
                    r.pendingRecords(), r.inFlightBundleId(), r.operatorBalanceMojos());
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
