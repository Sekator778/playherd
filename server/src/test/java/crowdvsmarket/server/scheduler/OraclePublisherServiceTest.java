package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.persistence.ServerMetaRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
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

    private long insertPendingRecord(String roundIdPrefix) {
        long seq = serverMeta.reserveOracleSeqRange(1);
        long now = System.currentTimeMillis();
        oracleRecords.insert(roundIdPrefix + seq, seq, now / 1000, 5_000_000L, "0xhash" + seq, "replay", now / 1000, now);
        return seq;
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
