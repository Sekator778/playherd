package crowdvsmarket.server.scheduler;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.chain.LogThrottle;
import crowdvsmarket.server.chain.OracleAssetGuard;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
 *
 * <p><b>Record format 2 (docs/research/markets_architecture.md §7, Т-9).</b> Every pending record goes
 * out with its market's registry index ({@code asset}; 0 = BTC), whatever the market. A chain service
 * older than format 2 would drop that field and publish the record as a BTC price — on a public
 * chain, for good — so a record with {@code asset > 0} is sent only when {@link OracleAssetGuard}
 * has just seen the chain service say format 2 or later. {@code seq} is ONE ordered stream shared by
 * all markets, which leaves two ways to treat a record that has to be held: let the later (BTC)
 * records overtake it, or stop the stream there. This class <b>stops the stream</b>: the records
 * before the first held one go, that one and everything after it wait ({@link #sendable}). Chainsvc
 * itself would accept the overtaking (a record below its watermark that it has never seen is taken
 * as a late one, chain/c2-svc {@code QueueFile::enqueue}, docs/research/chain_service.md §10.3), but
 * then the seq set on chain has holes for as long as the hold lasts — and for good if the chain
 * service is never upgraded — and the public verifier cannot tell a hole we made on purpose from an
 * anchor that got lost, which is the one thing it exists to tell. Stopping costs nothing that is not
 * only delayed: every row stays in {@code oracle_records} and goes, in {@code seq} order, the moment
 * the chain service reports format 2 (the supported deployment order is chainsvc first, then the
 * server, chain_service.md §16.2).
 *
 * <p><b>A record chainsvc already holds.</b> {@code POST /v1/oracle/records} answers with the
 * {@code seq}s it <i>newly</i> accepted; one it already has — because an earlier answer was lost after
 * chainsvc had persisted the batch — is left out as an idempotent no-op (chain_service.md §10.7).
 * "Not in {@code acceptedSeqs}" is therefore not "not held": left at that, such a row stayed
 * unqueued, was resent on every tick for good, and — never being {@code chainsvc_queued} — was never
 * reconciled, so its round showed an unknown anchor although the record was on chain. The rows a
 * response does not name are asked about ({@code GET /v1/oracle/records/{seq}}); the ones chainsvc
 * holds are marked handed over, with the state it reported (the questions stop at the first row it
 * does not hold: the POST itself was refused then). A chain service that cannot even answer that ends
 * the pass instead of timing out once per chunk.
 *
 * <p><b>"chainsvc holds seq N" is not "chainsvc holds my record N".</b> It keeps the first record it saw
 * for a seq and takes a second one as the same no-op whatever its content, and its status answer carries no
 * content — so a seq the server issued before it knew the chain service's {@code maxKnownSeq} (the floor
 * arrives late, or a second writer shares the singleton) used to be marked confirmed at some height for a
 * record chainsvc never took. Two things make the difference. (1) The attempt is on record before the POST
 * ({@code oracle_records.post_attempted_at_ms}, V8) and {@code markKnown} refuses a row without one. (2) A row
 * that was <i>never</i> POSTed is asked about before its first POST: if chainsvc holds the seq already, that
 * record cannot be ours — the row is a <b>collision</b>: not sent, state {@code conflict} (which the round's
 * {@code oracleAnchor} and the public journal then show, the web already words it), logged at ERROR, out of the
 * pending set and the reconciliation. A row POSTed before whose answer was lost still ends as known: it is the
 * only case in which the held record can be ours. What stays open — a first POST that never reached chainsvc,
 * a record that took the seq in the meantime and a resend that is then a no-op — can only be closed by the
 * status answer carrying the record's content; the server cannot see it.
 */
@ApplicationScoped
public class OraclePublisherService {

    private static final Logger LOG = Logger.getLogger(OraclePublisherService.class);
    private static final int MAX_BATCH = 20;
    /** The states in which chainsvc durably holds a record (FIXED INTERFACE (b), plus {@code
     *  conflict}: it holds a different record under that {@code seq} and reconciliation keeps asking). */
    private static final Set<String> HELD_BY_CHAINSVC = Set.of("queued", "in_flight", "confirmed", "conflict");

    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    ChainSvcClient chainSvc;
    @Inject
    OracleAssetGuard assetGuard;

    private final LogThrottle warnings = new LogThrottle(60_000);

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
            publishOnce();
        } catch (Exception e) {
            // Defensive: nothing in publishOnce is expected to escape its own try/catch, but this
            // now runs on a background thread with no caller to observe/log an escaping exception
            // otherwise (unlike the old inline call, whose caller — RoundSchedulerService.tick() —
            // wrapped it in its own try/catch purely for that reason).
            LOG.error("oracle publish failed unexpectedly", e);
        }
    }

    /** One pass over what is pending, on the calling thread (tests drive it directly; production
     *  goes through {@link #publishPending}). */
    void publishOnce() {
        List<OracleRecordRepository.Row> pending = oracleRecords.pending();
        if (pending.isEmpty()) {
            return;
        }
        List<OracleRecordRepository.Row> sendable = sendable(pending, assetGuard::assetsSupported);
        if (sendable.size() < pending.size() && warnings.ready("held")) {
            OracleRecordRepository.Row firstHeld = pending.get(sendable.size());
            LOG.warnf("%d oracle record(s) are held back from seq %d (market %s, asset %d) on: the chain service "
                            + "does not (yet) say it understands record format 2", pending.size() - sendable.size(),
                    firstHeld.seq(), firstHeld.market(), firstHeld.assetIndex());
        }
        for (int i = 0; i < sendable.size(); i += MAX_BATCH) {
            List<OracleRecordRepository.Row> chunk = sendable.subList(i, Math.min(i + MAX_BATCH, sendable.size()));
            try {
                if (!publishChunk(chunk)) {
                    LOG.debugf("chainsvc does not answer — %d oracle record(s) stay pending", sendable.size() - i);
                    return;
                }
            } catch (Exception e) {
                LOG.debugf(e, "chainsvc unreachable — %d oracle record(s) stay pending", chunk.size());
            }
        }
    }

    /**
     * The part of {@code pending} (ascending by {@code seq}) that may leave now: all of it when it holds
     * no record of a market other than BTC, or when the chain service understands record format 2;
     * otherwise the records before the first one with {@code asset > 0} — that one and every later one
     * wait (class javadoc: why the stream stops instead of letting the later BTC records overtake).
     * {@code assetsSupported} is asked at most once, and not at all for a BTC-only list, so a chain
     * service that is slow to say its format never delays BTC records that need no answer.
     */
    static List<OracleRecordRepository.Row> sendable(List<OracleRecordRepository.Row> pending,
                                                      BooleanSupplier assetsSupported) {
        int firstNonBtc = -1;
        for (int i = 0; i < pending.size(); i++) {
            if (pending.get(i).assetIndex() > 0) {
                firstNonBtc = i;
                break;
            }
        }
        if (firstNonBtc < 0 || assetsSupported.getAsBoolean()) {
            return pending;
        }
        return pending.subList(0, firstNonBtc);
    }

    /** Sends one chunk and records what chainsvc holds of it: first the collision check of the rows never sent
     *  before, then the attempt on record, then the POST. @return false when chainsvc did not
     *  answer at all, so there is no point in going on with the next chunk this pass. */
    private boolean publishChunk(List<OracleRecordRepository.Row> chunk) {
        long now = System.currentTimeMillis();
        List<OracleRecordRepository.Row> toSend = new ArrayList<>(chunk.size());
        for (OracleRecordRepository.Row row : chunk) {
            if (row.postAttemptedAtMs() != null) {
                toSend.add(row);
                continue;
            }
            // never POSTed: whatever chainsvc holds under this seq was not put there by this server
            ChainSvcClient.OracleRecordState remote = chainSvc.oracleRecordStatus(row.seq());
            if (remote == null) {
                return false; // chainsvc does not answer: no POST either, the next tick asks again
            }
            if (HELD_BY_CHAINSVC.contains(remote.state())) {
                collision(row, remote, now);
            } else {
                toSend.add(row);
            }
        }
        if (toSend.isEmpty()) {
            return true;
        }
        // the attempt is on record BEFORE the POST: a row that chainsvc then holds without having named it in
        // its answer (a lost response) can be told from one that was never sent
        oracleRecords.markPostAttempted(toSend.stream().map(OracleRecordRepository.Row::seq).toList(), now);
        List<ChainSvcClient.OracleRecord> records = toSend.stream()
                .map(r -> new ChainSvcClient.OracleRecord(r.seq(), r.ts(), r.priceCents(), r.resultsHash(),
                        r.source(), r.sourceTs(), r.assetIndex()))
                .toList();
        // FIXED INTERFACE (b): mark exactly the seqs chainsvc's response names (`acceptedSeqs`), not
        // "the whole chunk iff the count matches" — a record already known confirmed/queued at
        // chainsvc is accepted idempotently (not an error) but was not freshly queued by this call,
        // so the count alone cannot tell us which of `chunk` to stop retrying.
        ChainSvcClient.OracleRecordsResult result = chainSvc.oracleRecords(records);
        List<Long> acceptedSeqs = result.acceptedSeqs();
        if (!acceptedSeqs.isEmpty()) {
            oracleRecords.markQueued(acceptedSeqs);
        }
        Set<Long> accepted = new HashSet<>(acceptedSeqs);
        List<OracleRecordRepository.Row> notAccepted = toSend.stream().filter(r -> !accepted.contains(r.seq())).toList();
        return notAccepted.isEmpty() || settleKnown(notAccepted, toSend.size());
    }

    /**
     * A seq collision (class javadoc): chainsvc holds a record under a seq whose record this server never sent.
     * The row is not sent and is flagged {@code conflict}; the error line says what to look at.
     */
    private void collision(OracleRecordRepository.Row row, ChainSvcClient.OracleRecordState remote, long now) {
        boolean flagged = oracleRecords.markConflict(row.seq(), now);
        LOG.errorf("oracle seq collision: the chain service already holds a record under seq %d (state %s%s) although "
                + "this server never sent it — the record of market %s (asset %d) is NOT published; its round's "
                + "anchor and the public journal show 'conflict'. A second writer on this singleton, or a seq "
                + "issued before the chain service's maxKnownSeq was known (herd.oracle.require-seq-floor=false)?%s",
                row.seq(), remote.state(), remote.height() == null ? "" : ", height " + remote.height(), row.market(),
                row.assetIndex(), flagged ? "" : " (the row could not be flagged: it changed under us)");
    }

    /**
     * Asks chainsvc about the rows its POST answer did not name, in {@code seq} order, and marks the ones
     * it holds. The first one it does not hold ends the questions: a POST that chainsvc took and
     * persisted leaves every row of it held, so a row that is not means the POST itself was refused (a
     * bad record, a failed write) — and then so were the rows behind it, which asking about one by one
     * would only turn into a burst of status reads on every tick for as long as the refusal lasts.
     *
     * @return false when chainsvc did not answer the question
     */
    private boolean settleKnown(List<OracleRecordRepository.Row> notAccepted, int chunkSize) {
        long now = System.currentTimeMillis();
        int settled = 0;
        for (OracleRecordRepository.Row row : notAccepted) {
            ChainSvcClient.OracleRecordState remote = chainSvc.oracleRecordStatus(row.seq());
            if (remote == null) {
                return false;
            }
            if (!HELD_BY_CHAINSVC.contains(remote.state())) {
                break;
            }
            if (!oracleRecords.markKnown(row.seq(), remote.state(), remote.height(), remote.coinId(), now)) {
                LOG.errorf("oracle record seq=%d is held by chainsvc (%s) but has no POST attempt on record: not marked "
                        + "known", row.seq(), remote.state());
                break;
            }
            LOG.debugf("chainsvc already holds oracle record seq=%d (%s) — marked handed over", row.seq(),
                    remote.state());
            settled++;
        }
        if (settled < notAccepted.size() && warnings.ready("not-taken")) {
            LOG.warnf("chainsvc answers but did not take %d of %d oracle records of this batch — retrying the rest "
                    + "next tick", notAccepted.size() - settled, chunkSize);
        }
        return true;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
