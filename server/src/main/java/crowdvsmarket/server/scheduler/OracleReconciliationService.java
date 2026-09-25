package crowdvsmarket.server.scheduler;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Task item 1c — server-side half of FIXED INTERFACE (b)/(c) durable-delivery reconciliation
 * (Р-40/41/47). Every 60s: for every {@code oracle_records} row this server has already handed to
 * chainsvc at least once and does not yet know {@code confirmed}
 * ({@link OracleRecordRepository#needsReconciliation()}), polls chainsvc's
 * {@code GET /v1/oracle/records/{seq}} and stores the returned {@code state}/{@code height}/
 * {@code coinId}. A record chainsvc still reports {@code "unknown"} more than
 * {@link #UNKNOWN_BACKFILL_AGE_MS} after this server first queued it is re-POSTed once per tick as
 * an ordinary chainsvc record (same announcement format, FIXED INTERFACE b) — chainsvc's own LATE/
 * backfill handling makes this idempotent ("every seq exactly once"), covering the case where
 * chainsvc lost track of a record it once accepted (e.g. it restarted before the record made it
 * into a spend).
 *
 * <p>Never blocks/throws to its {@code @Scheduled} caller — same "never block the game on chain
 * confirmation" contract {@code OraclePublisherService} already follows; a chainsvc outage just
 * means every row here stays exactly as it was until the next tick.
 */
@ApplicationScoped
public class OracleReconciliationService {

    private static final Logger LOG = Logger.getLogger(OracleReconciliationService.class);

    /** предложение: long enough that a record queued only moments ago (chainsvc hasn't had a
     *  chance to ingest it yet) is not mistaken for one chainsvc actually lost track of. */
    private static final long UNKNOWN_BACKFILL_AGE_MS = 120_000;

    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    ChainSvcClient chainSvc;

    @Scheduled(every = "60s")
    void reconcile() {
        List<OracleRecordRepository.ReconciliationRow> rows;
        try {
            rows = oracleRecords.needsReconciliation();
        } catch (Exception e) {
            LOG.debugf(e, "failed to load oracle records pending reconciliation — will retry next tick");
            return;
        }
        long now = System.currentTimeMillis();
        for (OracleRecordRepository.ReconciliationRow row : rows) {
            reconcileOne(row, now);
        }
    }

    private void reconcileOne(OracleRecordRepository.ReconciliationRow row, long now) {
        try {
            ChainSvcClient.OracleRecordState remote = chainSvc.oracleRecordStatus(row.seq());
            if (remote == null) {
                return; // chainsvc unreachable this tick — ChainSvcClientImpl already logged it
            }
            oracleRecords.updateReconciledState(row.seq(), remote.state(), remote.height(), remote.coinId(), now);
            if ("unknown".equals(remote.state()) && now - row.queuedAtMs() > UNKNOWN_BACKFILL_AGE_MS) {
                backfill(row);
            }
        } catch (Exception e) {
            LOG.debugf(e, "reconciliation failed for oracle record seq=%d — will retry next tick", row.seq());
        }
    }

    private void backfill(OracleRecordRepository.ReconciliationRow row) {
        try {
            chainSvc.oracleRecords(List.of(new ChainSvcClient.OracleRecord(row.seq(), row.ts(), row.priceCents(),
                    row.resultsHash(), row.source(), row.sourceTs())));
        } catch (Exception e) {
            LOG.debugf(e, "backfill re-POST failed for oracle record seq=%d — will retry next tick", row.seq());
        }
    }
}
