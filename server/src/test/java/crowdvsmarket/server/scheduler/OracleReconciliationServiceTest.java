package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.persistence.ServerMetaRepository;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Task item 1c — FIXED INTERFACE (b)/(c) durable-delivery reconciliation (Р-40/41/47). Every
 * {@code seq} used here comes from {@link ServerMetaRepository#reserveOracleSeqRange} so it can
 * never collide with a real one another test (or this app's own background bots/scheduler) is
 * concurrently generating against the same shared Dev Services Postgres.
 */
@QuarkusTest
class OracleReconciliationServiceTest {

    @InjectMock
    ChainSvcClient chainSvcClient;

    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    OracleReconciliationService reconciliationService;
    @Inject
    ServerMetaRepository serverMeta;

    @BeforeEach
    void stubBackgroundNoise() {
        // Only this test's own stubs (below, per test) matter for oracleRecordStatus/oracleRecords
        // assertions; these two just keep OraclePublisherService/OracleStatusCache's own
        // concurrent background ticks quiet (never asserted on here).
        when(chainSvcClient.oracleStatus()).thenReturn(null);
        when(chainSvcClient.health()).thenReturn(true);
    }

    private long insertQueuedRecord(String roundIdPrefix, long queuedAtMs) {
        long seq = serverMeta.reserveOracleSeqRange(1);
        oracleRecords.insert(roundIdPrefix + seq, seq, queuedAtMs / 1000, 5_000_000L,
                "0xhash" + seq, "replay", queuedAtMs / 1000, queuedAtMs);
        oracleRecords.markQueued(List.of(seq));
        return seq;
    }

    @Test
    void reconcileStoresChainsvcStateAndExposesItAsRoundAnchor() {
        long now = System.currentTimeMillis();
        long seq = insertQueuedRecord("reconcile-confirmed-", now);
        when(chainSvcClient.oracleRecordStatus(seq))
                .thenReturn(new ChainSvcClient.OracleRecordState(seq, "confirmed", 123456L, "0xcoinid"));

        reconciliationService.reconcile();

        Rounds.OracleAnchorDto anchor = oracleRecords.findAnchorByRoundId("reconcile-confirmed-" + seq)
                .orElseThrow();
        assertThat(anchor.seq()).isEqualTo(seq);
        assertThat(anchor.state()).isEqualTo("confirmed");
        assertThat(anchor.height()).isEqualTo(123456L);
    }

    @Test
    void reconcileNeverPollsARecordAlreadyKnownConfirmed() {
        long now = System.currentTimeMillis();
        long seq = insertQueuedRecord("reconcile-skip-confirmed-", now);
        oracleRecords.updateReconciledState(seq, "confirmed", 1L, "0xcoin", now);

        reconciliationService.reconcile();

        verify(chainSvcClient, never()).oracleRecordStatus(seq);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconcileBackfillsARecordStillUnknownPastTheAgeThreshold() {
        long oldQueuedAtMs = System.currentTimeMillis() - 200_000; // > the 120s backfill threshold
        long seq = insertQueuedRecord("reconcile-backfill-", oldQueuedAtMs);
        when(chainSvcClient.oracleRecordStatus(seq))
                .thenReturn(new ChainSvcClient.OracleRecordState(seq, "unknown", null, null));
        when(chainSvcClient.oracleRecords(anyList())).thenReturn(new ChainSvcClient.OracleRecordsResult(0, List.of()));

        reconciliationService.reconcile();

        ArgumentCaptor<List<ChainSvcClient.OracleRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(chainSvcClient, org.mockito.Mockito.atLeastOnce()).oracleRecords(captor.capture());
        boolean backfilled = captor.getAllValues().stream().flatMap(List::stream)
                .anyMatch(r -> r.seq() == seq && r.resultsHash().equals("0xhash" + seq));
        assertThat(backfilled).as("expected a backfill re-POST for the still-unknown, aged record").isTrue();
    }

    @Test
    void reconcileDoesNotBackfillARecordStillUnknownButRecentlyQueued() {
        long now = System.currentTimeMillis();
        long seq = insertQueuedRecord("reconcile-too-recent-", now);
        when(chainSvcClient.oracleRecordStatus(seq))
                .thenReturn(new ChainSvcClient.OracleRecordState(seq, "unknown", null, null));

        reconciliationService.reconcile();

        verify(chainSvcClient, never()).oracleRecords(org.mockito.ArgumentMatchers.argThat(
                records -> records.stream().anyMatch(r -> r.seq() == seq)));
        // still recorded the freshly-polled state, even though it wasn't old enough to backfill
        Rounds.OracleAnchorDto anchor = oracleRecords.findAnchorByRoundId("reconcile-too-recent-" + seq)
                .orElseThrow();
        assertThat(anchor.state()).isEqualTo("unknown");
    }

    @Test
    void reconcileSkipsWhenChainsvcUnreachable() {
        long now = System.currentTimeMillis();
        long seq = insertQueuedRecord("reconcile-unreachable-", now);
        when(chainSvcClient.oracleRecordStatus(seq)).thenReturn(null); // ChainSvcClientImpl's own contract

        reconciliationService.reconcile(); // must not throw

        Rounds.OracleAnchorDto anchor = oracleRecords.findAnchorByRoundId("reconcile-unreachable-" + seq)
                .orElseThrow();
        assertThat(anchor.state()).isEqualTo("unknown"); // still the V2 migration's default — never polled successfully
    }
}
