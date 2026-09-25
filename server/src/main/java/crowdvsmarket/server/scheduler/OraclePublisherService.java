package crowdvsmarket.server.scheduler;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Р-40/41/47 best-effort chainsvc publisher — drains {@code oracle_records} rows that have not
 * been queued yet, at most 20 at a time (FIXED INTERFACE 3: "several pending records go into ONE
 * singleton spend (max 20)"). Never throws to its caller ({@code RoundSchedulerService}'s tick) —
 * an unreachable chainsvc just leaves rows pending for the next tick (task: "never block the game
 * on chain confirmation").
 */
@ApplicationScoped
public class OraclePublisherService {

    private static final Logger LOG = Logger.getLogger(OraclePublisherService.class);
    private static final int MAX_BATCH = 20;

    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    ChainSvcClient chainSvc;

    public void publishPending() {
        List<OracleRecordRepository.Row> pending = oracleRecords.pending();
        for (int i = 0; i < pending.size(); i += MAX_BATCH) {
            List<OracleRecordRepository.Row> chunk = pending.subList(i, Math.min(i + MAX_BATCH, pending.size()));
            try {
                List<ChainSvcClient.OracleRecord> records = chunk.stream()
                        .map(r -> new ChainSvcClient.OracleRecord(r.seq(), r.ts(), r.priceCents(), r.resultsHash(),
                                r.source(), r.sourceTs()))
                        .toList();
                // FIXED INTERFACE (b): mark exactly the seqs chainsvc's response names
                // (`acceptedSeqs`), not "the whole chunk iff the count matches" — a record already
                // known confirmed/queued at chainsvc is accepted idempotently (not an error) but
                // was not freshly queued by this call, so `queued` alone cannot tell us which of
                // `chunk` to stop retrying.
                ChainSvcClient.OracleRecordsResult result = chainSvc.oracleRecords(records);
                if (!result.acceptedSeqs().isEmpty()) {
                    oracleRecords.markQueued(result.acceptedSeqs());
                }
                if (result.acceptedSeqs().size() < chunk.size()) {
                    LOG.warnf("chainsvc accepted only %d/%d oracle records this batch — retrying the rest next tick",
                            result.acceptedSeqs().size(), chunk.size());
                }
            } catch (Exception e) {
                LOG.debugf(e, "chainsvc unreachable — %d oracle record(s) stay pending", chunk.size());
            }
        }
    }
}
