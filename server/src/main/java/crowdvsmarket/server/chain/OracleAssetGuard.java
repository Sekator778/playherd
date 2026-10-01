package crowdvsmarket.server.chain;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Answers one question before a record of a market other than BTC (asset above 0) is sent to the
 * chain service: does it understand the {@code asset} field? (docs/research/markets_architecture.md
 * §7 п.4, docs/research/chain_service.md §16.2.)
 *
 * <p>A chain service older than record format 2 drops the unknown JSON key and publishes the record
 * as a BTC price — silently, on a public chain, for good. So the answer is "yes" only when the
 * chain service has <b>just said so</b>: the status is read live, never taken from
 * {@link OracleStatusCache} (up to five seconds old — the window in which a chain service rolled back
 * to an older image would still be believed to be format 2), and every way of not knowing is "no": the
 * chain service does not answer, the status carries no {@code recordFormat} (an old chain service —
 * counted as format 1), or it says a format below 2.
 *
 * <p>Shared by {@code OraclePublisherService} (the records pending since their round settled) and
 * {@code OracleReconciliationService} (the re-POST of a record the chain service lost).
 */
@ApplicationScoped
public class OracleAssetGuard {

    private static final Logger LOG = Logger.getLogger(OracleAssetGuard.class);

    @Inject
    ChainSvcClient chainSvc;

    private final LogThrottle warnings = new LogThrottle(60_000);

    /**
     * Whether records with {@code asset > 0} may be sent right now. Reads the chain service's status
     * once per call; a chain service that does not answer costs the client's own timeouts, so call it
     * from a background thread and only when such a record is actually waiting.
     */
    public boolean assetsSupported() {
        ChainSvcClient.OracleStatus status;
        try {
            status = chainSvc.oracleStatus();
        } catch (RuntimeException e) {
            // ChainSvcClientImpl never throws; a test double or a future implementation might.
            LOG.debugf(e, "reading the chain service status failed");
            status = null;
        }
        if (status == null) {
            if (warnings.ready("unreachable")) {
                LOG.warn("the chain service status is unavailable: oracle records of markets other than BTC are "
                        + "held back until it says it understands record format 2 (and so is every record "
                        + "after the first of them — the seq stream on chain stays free of gaps)");
            }
            return false;
        }
        warnings.reset("unreachable");
        if (!status.supportsAssets()) {
            if (warnings.ready("old-format")) {
                LOG.warnf("the chain service is on record format %d (%s): it would publish the records of "
                                + "markets other than BTC as BTC prices, so they are held back, and with them every "
                                + "record after the first of them — upgrade chainsvc (docs/research/chain_service.md "
                                + "§16.2: chainsvc first, then the server)", status.effectiveRecordFormat(),
                        status.recordFormat() == null ? "its status has no recordFormat" : "its status says so");
            }
            return false;
        }
        warnings.reset("old-format");
        return true;
    }
}
