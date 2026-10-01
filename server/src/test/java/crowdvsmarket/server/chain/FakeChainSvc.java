package crowdvsmarket.server.chain;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A chain service for tests of the oracle wiring, with the queue rules of the real one
 * ({@code chain/c2-svc/src/oracle/queue.rs}, docs/research/chain_service.md §10.3/§10.7/§16) and
 * switches for the ways it differs from one deployment to the next. No network, no Mockito of its own:
 * it implements {@link ChainSvcClient} directly (so a plain unit test can hand it to a service), and
 * {@link #install} routes a {@code @InjectMock ChainSvcClient} mock into it for {@code @QuarkusTest}s.
 *
 * <ul>
 *   <li>{@code POST /v1/oracle/records} ({@link #oracleRecords}): a {@code seq} it already holds — in any
 *       state, or at or below {@link #confirmedUpTo}, the history already on chain — is an idempotent
 *       no-op and is <b>left out</b> of {@code acceptedSeqs}; every other record is queued, in any order
 *       (a late record below its watermark is taken). On a format below 2 the {@code asset} key is
 *       dropped on the way in, exactly as the real DTO without {@code deny_unknown_fields} does — what
 *       would be published as a BTC price shows up in {@link #publishedAsBtcByMistake}.
 *   <li>{@link #oracleStatus}: {@code maxKnownSeq} (switch it off with {@link #reportsMaxKnownSeq} for
 *       a chain service that predates it), {@code recordFormat} ({@link #recordFormat}; {@code null} omits
 *       it), a few more fields as the real one has them.
 *   <li>{@link #down}: every call answers like an outage — {@code null}, or an empty result for the
 *       POST — as {@code ChainSvcClientImpl} turns a connection error.
 *   <li>{@link #loseTheResponseOfThePostWith}: the first POST that carries a given {@code seq} is
 *       processed and persisted, but its answer is lost (it comes back as an outage) — the case that
 *       makes a server resend a record chainsvc already holds. Tied to a {@code seq}, not to "the next
 *       POST", so a test that shares the application with a background publisher still loses the answer
 *       it means to.
 * </ul>
 */
public class FakeChainSvc implements ChainSvcClient {

    public static final String LAUNCHER_ID = "c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20";

    public volatile boolean down;
    /** {@code null}: the status carries no {@code recordFormat} (a chain service older than format 2). */
    public volatile Integer recordFormat = 2;
    public volatile boolean reportsMaxKnownSeq = true;
    /** The chain already holds {@code seq} 1..N, confirmed — a singleton with history. */
    public volatile long confirmedUpTo;
    private volatile long loseResponseOfSeq = -1;
    /** Answers every POST with "nothing taken" and stores nothing (a chain service that refuses). */
    public volatile boolean refuseAll;

    private final TreeMap<Long, OracleRecord> held = new TreeMap<>();
    private final Map<Long, String> stateOf = new HashMap<>();
    private final List<List<OracleRecord>> posts = new CopyOnWriteArrayList<>();
    private final List<OracleRecord> publishedAsBtcByMistake = new CopyOnWriteArrayList<>();
    private final AtomicInteger statusReads = new AtomicInteger();
    private final AtomicInteger recordStatusReads = new AtomicInteger();

    // --- ChainSvcClient ---

    @Override
    public synchronized OracleRecordsResult oracleRecords(List<OracleRecord> records) {
        posts.add(List.copyOf(records));
        if (down || refuseAll) {
            return new OracleRecordsResult(0, List.of());
        }
        List<Long> accepted = new ArrayList<>();
        List<OracleRecord> sorted = new ArrayList<>(records);
        sorted.sort(Comparator.comparingLong(OracleRecord::seq));
        for (OracleRecord record : sorted) {
            if (record.seq() <= confirmedUpTo || held.containsKey(record.seq())) {
                continue; // already known: not an error, not accepted again
            }
            boolean understandsAssets = recordFormat != null && recordFormat >= 2;
            OracleRecord stored = understandsAssets ? record : new OracleRecord(record.seq(), record.ts(),
                    record.priceCents(), record.resultsHash(), record.source(), record.sourceTs(), 0);
            if (record.asset() > 0 && stored.asset() == 0) {
                publishedAsBtcByMistake.add(record);
            }
            held.put(record.seq(), stored);
            stateOf.put(record.seq(), "queued");
            accepted.add(record.seq());
        }
        if (loseResponseOfSeq >= 0 && records.stream().anyMatch(r -> r.seq() == loseResponseOfSeq)) {
            loseResponseOfSeq = -1;
            return new OracleRecordsResult(0, List.of());
        }
        return new OracleRecordsResult(accepted.size(), accepted);
    }

    @Override
    public synchronized OracleRecordState oracleRecordStatus(long seq) {
        recordStatusReads.incrementAndGet();
        if (down) {
            return null;
        }
        if (seq <= confirmedUpTo) {
            return new OracleRecordState(seq, "confirmed", 4_000_000L + seq, "0xcoin" + seq);
        }
        String state = stateOf.getOrDefault(seq, "unknown");
        boolean confirmed = state.equals("confirmed");
        return new OracleRecordState(seq, state, confirmed ? 4_000_000L + seq : null, confirmed ? "0xcoin" + seq : null);
    }

    @Override
    public synchronized OracleStatus oracleStatus() {
        statusReads.incrementAndGet();
        if (down) {
            return null;
        }
        long pending = stateOf.values().stream().filter(s -> s.equals("queued")).count();
        return new OracleStatus(LAUNCHER_ID, "testnet11", confirmedUpTo, 4_000_000L + confirmedUpTo, (int) pending,
                null, "10000000000", null, List.of(), reportsMaxKnownSeq ? maxKnownSeq() : null, recordFormat, null,
                null);
    }

    @Override
    public boolean health() {
        return !down;
    }

    @Override
    public AuthVerifyResult authVerify(String address, String publicKey, String message, String signature) {
        return new AuthVerifyResult(true, null);
    }

    @Override
    public PlayWithdrawalSubmitResult submitPlayWithdrawal(String requestId, String toAddress, long amountMojos) {
        return null;
    }

    @Override
    public PlayWithdrawalStatus playWithdrawalStatus(String requestId) {
        return null;
    }

    @Override
    public PlayDepositAddress playDepositAddress(int playerIndex) {
        return null;
    }

    @Override
    public PlayDepositsResult playDeposits(long sinceHeight) {
        return null;
    }

    // --- what a test sets up and looks at ---

    /** The first POST that carries {@code seq} is persisted but answered like an outage. */
    public void loseTheResponseOfThePostWith(long seq) {
        loseResponseOfSeq = seq;
    }

    /** The highest {@code seq} known in any state, 0 for an empty chain service — what the real status
     *  calls {@code maxKnownSeq}. */
    public synchronized long maxKnownSeq() {
        return Math.max(confirmedUpTo, held.isEmpty() ? 0 : held.lastKey());
    }

    /** The record as the chain service holds it (after a format below 2 dropped the asset), or null. */
    public synchronized OracleRecord held(long seq) {
        return held.get(seq);
    }

    public synchronized boolean holds(long seq) {
        return seq <= confirmedUpTo || held.containsKey(seq);
    }

    public synchronized void confirm(long seq) {
        if (held.containsKey(seq)) {
            stateOf.put(seq, "confirmed");
        }
    }

    /** The chain service lost the record (a queue file restored from an old backup, say). */
    public synchronized void forget(long seq) {
        held.remove(seq);
        stateOf.remove(seq);
    }

    /** Every POST as it arrived, in order, whether it was accepted or not. */
    public List<List<OracleRecord>> posts() {
        return List.copyOf(posts);
    }

    /** Every record of every POST, flattened. */
    public List<OracleRecord> postedRecords() {
        return posts.stream().flatMap(List::stream).toList();
    }

    public List<Long> postedSeqs() {
        return postedRecords().stream().map(OracleRecord::seq).toList();
    }

    /** The records with {@code asset > 0} that a chain service of format below 2 took and would
     *  publish as BTC — the thing the server must never cause. */
    public List<OracleRecord> publishedAsBtcByMistake() {
        return List.copyOf(publishedAsBtcByMistake);
    }

    public int statusReads() {
        return statusReads.get();
    }

    public int recordStatusReads() {
        return recordStatusReads.get();
    }

    /** A real {@link OracleAssetGuard} asking this fake — for a plain unit test of a class that depends on
     *  one, outside this package, where the guard's injection point is not reachable. */
    public OracleAssetGuard assetGuard() {
        OracleAssetGuard guard = new OracleAssetGuard();
        guard.chainSvc = this;
        return guard;
    }

    /** Routes the {@code @InjectMock ChainSvcClient} {@code mock} into this fake, for the calls the oracle
     *  wiring makes. Other methods keep Mockito's defaults. */
    public FakeChainSvc install(ChainSvcClient mock) {
        when(mock.oracleRecords(anyList())).thenAnswer(inv -> oracleRecords(inv.getArgument(0)));
        when(mock.oracleStatus()).thenAnswer(inv -> oracleStatus());
        when(mock.oracleRecordStatus(anyLong())).thenAnswer(inv -> oracleRecordStatus(inv.getArgument(0)));
        when(mock.health()).thenAnswer(inv -> health());
        return this;
    }
}
