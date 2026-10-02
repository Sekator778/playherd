package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.persistence.Db;
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
    @Inject
    Db db;

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

    /** A record of a market other than BTC that was handed to chainsvc {@code queuedAtMs} ago. */
    private long insertQueuedRecordOfMarket(String roundIdPrefix, String market, int asset, long queuedAtMs) {
        long seq = serverMeta.reserveOracleSeqRange(1);
        db.tx(c -> {
            oracleRecords.insert(c, new OracleRecordRepository.NewRecord(market, asset, roundIdPrefix + seq, seq,
                    queuedAtMs / 1000, 2_400_000L, "0xhash" + seq, "live", queuedAtMs / 1000, queuedAtMs));
            return null;
        });
        oracleRecords.markQueued(List.of(seq));
        return seq;
    }

    private static ChainSvcClient.OracleStatus statusOfFormat(Integer recordFormat) {
        return new ChainSvcClient.OracleStatus("c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20",
                "testnet11", 1L, 4_735_705L, 0, null, "10000000000", null, List.of(), recordFormat == null ? null : 1145L,
                recordFormat, null, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theBackfillOfARecordOfAnotherMarketCarriesItsAssetWhenChainsvcIsOnFormatTwo() {
        long oldQueuedAtMs = System.currentTimeMillis() - 200_000;
        long seq = insertQueuedRecordOfMarket("reconcile-asset-", "ETH", 3, oldQueuedAtMs);
        when(chainSvcClient.oracleStatus()).thenReturn(statusOfFormat(2));
        when(chainSvcClient.oracleRecordStatus(seq))
                .thenReturn(new ChainSvcClient.OracleRecordState(seq, "unknown", null, null));
        when(chainSvcClient.oracleRecords(anyList())).thenReturn(new ChainSvcClient.OracleRecordsResult(1, List.of(seq)));

        reconciliationService.reconcile();

        ArgumentCaptor<List<ChainSvcClient.OracleRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(chainSvcClient, org.mockito.Mockito.atLeastOnce()).oracleRecords(captor.capture());
        List<ChainSvcClient.OracleRecord> resent = captor.getAllValues().stream().flatMap(List::stream)
                .filter(r -> r.seq() == seq).toList();
        assertThat(resent).as("the unknown record is re-sent").isNotEmpty();
        assertThat(resent).allSatisfy(r -> {
            assertThat(r.asset()).as("with the asset the first POST carried").isEqualTo(3);
            assertThat(r.priceCents()).isEqualTo(2_400_000L);
        });
    }

    @Test
    void theBackfillOfARecordOfAnotherMarketWaitsWhenChainsvcPredatesFormatTwo() {
        long oldQueuedAtMs = System.currentTimeMillis() - 200_000;
        long seq = insertQueuedRecordOfMarket("reconcile-asset-held-", "ETH", 3, oldQueuedAtMs);
        when(chainSvcClient.oracleStatus()).thenReturn(statusOfFormat(null)); // an old chainsvc: no recordFormat
        when(chainSvcClient.oracleRecordStatus(seq))
                .thenReturn(new ChainSvcClient.OracleRecordState(seq, "unknown", null, null));

        reconciliationService.reconcile();

        // it would be published as a BTC price
        verify(chainSvcClient, never()).oracleRecords(org.mockito.ArgumentMatchers.argThat(
                records -> records != null && records.stream().anyMatch(r -> r.seq() == seq)));
        // …and the polled state is still recorded
        assertThat(oracleRecords.findAnchorByRoundId("reconcile-asset-held-" + seq).orElseThrow().state())
                .isEqualTo("unknown");
    }

    @Test
    void theBackfillOfABtcRecordNeverNeedsTheChainServicesFormatAndSendsAssetZero() {
        long oldQueuedAtMs = System.currentTimeMillis() - 200_000;
        long seq = insertQueuedRecord("reconcile-btc-asset-", oldQueuedAtMs);
        when(chainSvcClient.oracleStatus()).thenReturn(statusOfFormat(null)); // old chainsvc: BTC still goes
        when(chainSvcClient.oracleRecordStatus(seq))
                .thenReturn(new ChainSvcClient.OracleRecordState(seq, "unknown", null, null));
        when(chainSvcClient.oracleRecords(anyList())).thenReturn(new ChainSvcClient.OracleRecordsResult(1, List.of(seq)));

        reconciliationService.reconcile();

        verify(chainSvcClient, org.mockito.Mockito.atLeastOnce()).oracleRecords(org.mockito.ArgumentMatchers.argThat(
                records -> records != null && records.stream().anyMatch(r -> r.seq() == seq && r.asset() == 0)));
    }

    @Test
    void theReconciliationTickNeverOverlapsItself() throws NoSuchMethodException {
        io.quarkus.scheduler.Scheduled scheduled = OracleReconciliationService.class.getDeclaredMethod("reconcile")
                .getAnnotation(io.quarkus.scheduler.Scheduled.class);

        assertThat(scheduled.every()).isEqualTo("60s");
        assertThat(scheduled.concurrentExecution()).isEqualTo(io.quarkus.scheduler.Scheduled.ConcurrentExecution.SKIP);
    }

    @Test
    void reconcileNeverPollsOrOverwritesARecordFlaggedAsASeqCollision() {
        long now = System.currentTimeMillis();
        long seq = serverMeta.reserveOracleSeqRange(1);
        oracleRecords.insert("reconcile-conflict-" + seq, seq, now / 1000, 5_000_000L, "0xhash" + seq, "replay",
                now / 1000, now);
        assertThat(oracleRecords.markConflict(seq, now)).isTrue(); // chainsvc holds somebody else's record under this seq
        when(chainSvcClient.oracleRecordStatus(seq))
                .thenReturn(new ChainSvcClient.OracleRecordState(seq, "confirmed", 123456L, "0xcoinid"));
        org.mockito.Mockito.clearInvocations(chainSvcClient);

        reconciliationService.reconcile();

        verify(chainSvcClient, never()).oracleRecordStatus(seq); // not asked: it is not ours to follow
        Rounds.OracleAnchorDto anchor = oracleRecords.findAnchorByRoundId("reconcile-conflict-" + seq).orElseThrow();
        assertThat(anchor.state()).isEqualTo("conflict"); // and not "confirmed at 123456" for the other record
        assertThat(anchor.height()).isNull();
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
