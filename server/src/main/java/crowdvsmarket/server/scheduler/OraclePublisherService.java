package crowdvsmarket.server.scheduler;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * Р-40/41/47 best-effort chainsvc publisher — drains {@code oracle_records} rows that have not
 * been queued yet, at most 20 at a time (FIXED INTERFACE 3: "several pending records go into ONE
 * singleton spend (max 20)"). Never throws to its caller ({@code RoundSchedulerService}'s tick) —
 * an unreachable chainsvc just leaves rows pending for the next tick (task: "never block the game
 * on chain confirmation").
 *
 * <p><b>Wave-2 MINOR fix:</b> the actual chainsvc HTTP call ({@code doPublish}) now runs on a
 * dedicated background thread, never on the caller's thread — {@link #publishPending} (called
 * inline from {@code RoundSchedulerService.tick()}, the same thread that resolves rounds and runs
 * bots) used to call {@code chainSvc.oracleRecords(...)} directly and block on it, so a
 * slow/unresponsive chainsvc (up to {@code connect-timeout + read-timeout}, tens of seconds worst
 * case) delayed every other thing that tick does, including resolving an already-overdue round.
 * The executor's queue is bounded to exactly one pending task ({@link ArrayBlockingQueue} of
 * capacity 1): if a previous publish is still running when the next tick calls this again, the new
 * attempt is silently discarded ({@link ThreadPoolExecutor.DiscardPolicy}) rather than piling up
 * concurrent publish attempts or blocking the caller to wait for room — safe because nothing here
 * is lost: the row(s) that attempt would have sent simply stay {@code chainsvc_queued=false} and
 * get retried by a later tick, and {@code OracleReconciliationService} independently covers durable
 * retry for a record chainsvc silently drops after accepting it. {@code
 * quarkus.rest-client.chainsvc.*-timeout} was raised accordingly (application.properties) — now
 * safe to be more generous since a slow chainsvc no longer costs round-processing latency.
 */
@ApplicationScoped
public class OraclePublisherService {

    private static final Logger LOG = Logger.getLogger(OraclePublisherService.class);
    private static final int MAX_BATCH = 20;

    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    ChainSvcClient chainSvc;

    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1),
            r -> {
                Thread t = new Thread(r, "oracle-publisher");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    /** Never blocks the caller (the scheduler tick) — see the class javadoc. */
    public void publishPending() {
        executor.execute(this::doPublish);
    }

    private void doPublish() {
        try {
            List<OracleRecordRepository.Row> pending = oracleRecords.pending();
            for (int i = 0; i < pending.size(); i += MAX_BATCH) {
                List<OracleRecordRepository.Row> chunk = pending.subList(i, Math.min(i + MAX_BATCH, pending.size()));
                try {
                    List<ChainSvcClient.OracleRecord> records = chunk.stream()
                            .map(r -> new ChainSvcClient.OracleRecord(r.seq(), r.ts(), r.priceCents(), r.resultsHash(),
                                    r.source(), r.sourceTs()))
                            .toList();
                    // FIXED INTERFACE (b): mark exactly the seqs chainsvc's response names
                    // (`acceptedSeqs`), not "the whole chunk iff the count matches" — a record
                    // already known confirmed/queued at chainsvc is accepted idempotently (not an
                    // error) but was not freshly queued by this call, so `queued` alone cannot tell
                    // us which of `chunk` to stop retrying.
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
        } catch (Exception e) {
            // Defensive: nothing above this line is expected to escape its own try/catch, but this
            // now runs on a background thread with no caller to observe/log an escaping exception
            // otherwise (unlike the old inline call, whose caller — RoundSchedulerService.tick() —
            // wrapped it in its own try/catch purely for that reason).
            LOG.error("oracle publish failed unexpectedly", e);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
