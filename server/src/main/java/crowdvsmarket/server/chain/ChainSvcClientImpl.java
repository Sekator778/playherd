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
                            r.resultsHash(), r.source(), r.sourceTs(), r.asset()))
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
                    r.conflicts() == null ? List.of() : r.conflicts(), r.maxKnownSeq(), r.recordFormat(),
                    r.feeCoinsFree(), r.feeCoinsFreeMojos());
        } catch (Exception e) {
            LOG.debugf(e, "chainsvc /v1/oracle/status unreachable");
            return null;
        }
    }

    // --- Р-53: real on-chain PLAY withdraw/deposit ---

    @Override
    public PlayWithdrawalSubmitResult submitPlayWithdrawal(String requestId, String toAddress, long amountMojos) {
        try {
            ChainSvcRestClient.PlayWithdrawalAcceptedResponse r = rest.submitPlayWithdrawal(bearerToken(),
                    new ChainSvcRestClient.PlayWithdrawalRequest(requestId, toAddress, amountMojos));
            return new PlayWithdrawalSubmitResult(r.accepted());
        } catch (Exception e) {
            LOG.warnf(e, "chainsvc /v1/play/withdrawals unreachable for requestId=%s — will retry", requestId);
            return null;
        }
    }

    @Override
    public PlayWithdrawalStatus playWithdrawalStatus(String requestId) {
        try {
            ChainSvcRestClient.PlayWithdrawalStatusResponse r = rest.playWithdrawalStatus(bearerToken(), requestId);
            return new PlayWithdrawalStatus(r.requestId(), r.state(), r.coinId(), r.height(), r.reason());
        } catch (Exception e) {
            LOG.debugf(e, "chainsvc /v1/play/withdrawals/%s unreachable", requestId);
            return null;
        }
    }

    @Override
    public PlayDepositAddress playDepositAddress(int playerIndex) {
        try {
            ChainSvcRestClient.PlayDepositAddressResponse r = rest.playDepositAddress(bearerToken(),
                    new ChainSvcRestClient.PlayDepositAddressRequest(playerIndex));
            return new PlayDepositAddress(r.address(), r.innerPuzzleHash(), r.catPuzzleHash());
        } catch (Exception e) {
            LOG.warnf(e, "chainsvc /v1/play/deposit-address unreachable for playerIndex=%d", playerIndex);
            return null;
        }
    }

    @Override
    public PlayDepositsResult playDeposits(long sinceHeight) {
        try {
            ChainSvcRestClient.PlayDepositsResponse r = rest.playDeposits(bearerToken(), sinceHeight);
            List<PlayDeposit> deposits = r.deposits() == null ? List.of() : r.deposits().stream()
                    .map(d -> new PlayDeposit(d.coinId(), d.playerIndex(), d.amountMojos(), d.height()))
                    .toList();
            return new PlayDepositsResult(deposits, r.tipHeight());
        } catch (Exception e) {
            LOG.debugf(e, "chainsvc /v1/play/deposits unreachable (sinceHeight=%d)", sinceHeight);
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
