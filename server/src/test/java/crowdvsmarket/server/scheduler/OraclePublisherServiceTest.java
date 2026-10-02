package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.chain.FakeChainSvc;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.persistence.ServerMetaRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Wave-2 MINOR fix: the oracle-record push to chainsvc must never block round processing —
 * {@link OraclePublisherService#publishPending()} (called inline from
 * {@code RoundSchedulerService.tick()}, the same thread that resolves rounds and runs bots) must
 * return immediately even when the chainsvc call itself is slow, with the actual HTTP work moved to
 * a background thread.
 */
@QuarkusTest
class OraclePublisherServiceTest {

    @InjectMock
    ChainSvcClient chainSvcClient;

    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    OraclePublisherService publisherService;
    @Inject
    ServerMetaRepository serverMeta;
    @Inject
    Db db;

    /** A seq no chain service holds yet: the question that precedes the first POST of a row is answered "free". */
    @BeforeEach
    void theSeqsAreFree() {
        when(chainSvcClient.oracleRecordStatus(anyLong())).thenAnswer(
                inv -> new ChainSvcClient.OracleRecordState(inv.getArgument(0), "unknown", null, null));
    }

    private long insertPendingRecord(String roundIdPrefix) {
        long seq = serverMeta.reserveOracleSeqRange(1);
        long now = System.currentTimeMillis();
        oracleRecords.insert(roundIdPrefix + seq, seq, now / 1000, 5_000_000L, "0xhash" + seq, "replay", now / 1000, now);
        return seq;
    }

    private long insertPendingRecordOfMarket(String roundIdPrefix, String market, int asset) {
        return insertPendingRecordOfMarket(roundIdPrefix, market, asset, seq -> {
        });
    }

    /** {@code beforeInsert} sees the {@code seq} before the row exists — before the application's own
     *  scheduler can find it pending and send it. */
    private long insertPendingRecordOfMarket(String roundIdPrefix, String market, int asset,
                                             java.util.function.LongConsumer beforeInsert) {
        long seq = serverMeta.reserveOracleSeqRange(1);
        beforeInsert.accept(seq);
        long now = System.currentTimeMillis();
        db.tx(c -> {
            oracleRecords.insert(c, new OracleRecordRepository.NewRecord(market, asset, roundIdPrefix + seq, seq,
                    now / 1000, 2_400_000L, "0xhash" + seq, "live", now / 1000, now));
            return null;
        });
        return seq;
    }

    private List<Long> pendingSeqs() {
        return oracleRecords.pending().stream().map(OracleRecordRepository.Row::seq).toList();
    }

    @Test
    void recordsOfOtherMarketsGoWithTheirAssetOnlyOnceChainsvcSaysFormatTwoAndTheStreamStopsAtThemBefore() {
        FakeChainSvc chain = new FakeChainSvc().install(chainSvcClient);
        chain.recordFormat = 1; // an older chainsvc
        long btc = insertPendingRecordOfMarket("publisher-fmt-btc-", "BTC", 0);
        long eth = insertPendingRecordOfMarket("publisher-fmt-ETH-", "ETH", 3);
        long btcLater = insertPendingRecordOfMarket("publisher-fmt-btc2-", "BTC", 0);
        try {
            publisherService.publishPending();
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                    .until(() -> !pendingSeqs().contains(btc));

            // the record before the first held one went; the held one and the BTC record behind it wait, over
            // more than one pass (the scheduler's own tick runs once a second)
            await().during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(5))
                    .until(() -> pendingSeqs().containsAll(List.of(eth, btcLater)));
            assertThat(chain.postedSeqs()).contains(btc).doesNotContain(eth, btcLater);
            assertThat(chain.publishedAsBtcByMistake()).isEmpty();

            chain.recordFormat = 2; // chainsvc upgraded
            publisherService.publishPending();
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                    .until(() -> !pendingSeqs().contains(eth) && !pendingSeqs().contains(btcLater));

            assertThat(chain.held(eth).asset()).isEqualTo(3);
            assertThat(chain.held(btcLater).asset()).isZero();
            assertThat(chain.publishedAsBtcByMistake()).isEmpty();
        } finally {
            db.tx(c -> db.update(c, "DELETE FROM oracle_records WHERE round_id LIKE 'publisher-fmt-%'"));
        }
    }

    @Test
    void aRecordChainsvcAlreadyHoldsIsNotResentOnEveryTick() {
        FakeChainSvc chain = new FakeChainSvc().install(chainSvcClient);
        // chainsvc persists the batch, the answer never arrives
        long seq = insertPendingRecordOfMarket("publisher-known-", "BTC", 0, chain::loseTheResponseOfThePostWith);
        try {
            publisherService.publishPending();
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                    .until(() -> !pendingSeqs().contains(seq));

            assertThat(chain.postedSeqs().stream().filter(s -> s == seq)).as("sent once, not on every tick").hasSize(1);
            assertThat(chain.holds(seq)).isTrue();
            Rounds.OracleAnchorDto anchor = oracleRecords.findAnchorByRoundId("publisher-known-" + seq).orElseThrow();
            assertThat(anchor.state()).isEqualTo("queued"); // and reconciliation will now follow it to confirmed
            assertThat(oracleRecords.needsReconciliation()).extracting(OracleRecordRepository.ReconciliationRow::seq)
                    .contains(seq);
        } finally {
            db.tx(c -> db.update(c, "DELETE FROM oracle_records WHERE round_id LIKE 'publisher-known-%'"));
        }
    }

    @Test
    void publishPendingReturnsImmediatelyEvenWhenChainsvcIsSlow() {
        long seq = insertPendingRecord("publisher-slow-");
        when(chainSvcClient.oracleRecords(anyList())).thenAnswer(inv -> {
            Thread.sleep(1500);
            List<ChainSvcClient.OracleRecord> records = inv.getArgument(0);
            List<Long> seqs = records.stream().map(ChainSvcClient.OracleRecord::seq).toList();
            return new ChainSvcClient.OracleRecordsResult(seqs.size(), seqs);
        });

        long startNanos = System.nanoTime();
        publisherService.publishPending();
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMs)
                .as("publishPending() must not block on the (slow) chainsvc call itself")
                .isLessThan(500);

        // the record still gets published — just asynchronously, off the caller's thread
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> oracleRecords.pending().stream().noneMatch(r -> r.seq() == seq));
    }

    @Test
    void publishPendingDiscardsAnOverlappingCallInsteadOfQueuingUnboundedWork() throws InterruptedException {
        long seq = insertPendingRecord("publisher-overlap-");
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        when(chainSvcClient.oracleRecords(anyList())).thenAnswer(inv -> {
            callCount.incrementAndGet();
            release.await();
            List<ChainSvcClient.OracleRecord> records = inv.getArgument(0);
            List<Long> seqs = records.stream().map(ChainSvcClient.OracleRecord::seq).toList();
            return new ChainSvcClient.OracleRecordsResult(seqs.size(), seqs);
        });

        // Fire several overlapping calls the way consecutive scheduler ticks would if a previous
        // publish were still in flight — none of them should block the calling thread, and the
        // bounded (capacity-1) queue should discard the extras rather than pile up concurrent
        // chainsvc calls.
        for (int i = 0; i < 5; i++) {
            publisherService.publishPending();
        }
        release.countDown();

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                .until(() -> oracleRecords.pending().stream().noneMatch(r -> r.seq() == seq));
        // At most a small, bounded number of the 5 overlapping calls actually ran concurrently
        // (1 in flight + at most 1 queued) — nowhere near 5, and never blocked this test's thread.
        assertThat(callCount.get()).isLessThanOrEqualTo(2);
    }
}
