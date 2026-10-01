package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.chain.FakeChainSvc;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OraclePublisherService} and record format 2 (docs/research/markets_architecture.md §7): every
 * pending record goes out with its asset, but a record of a market other than BTC never reaches a chain
 * service that does not say it understands the field — and because {@code seq} is one ordered stream,
 * the stream stops at the first such record instead of letting later BTC records overtake it. Plain unit
 * test: the repository is an in-memory stand-in, the chain service is {@link FakeChainSvc} with the real
 * queue rules.
 */
class OraclePublisherFormatTest {

    private final List<OracleRecordRepository.Row> pending = new ArrayList<>();
    private final List<Long> markedQueued = new ArrayList<>();
    private final Map<Long, String> markedKnown = new HashMap<>();
    private final OracleRecordRepository repository = mock(OracleRecordRepository.class);
    private final FakeChainSvc chain = new FakeChainSvc();
    private final OraclePublisherService service = new OraclePublisherService();

    @BeforeEach
    void wire() {
        when(repository.pending()).thenAnswer(inv -> List.copyOf(pending));
        doAnswer(inv -> {
            List<Long> seqs = inv.getArgument(0);
            markedQueued.addAll(seqs);
            pending.removeIf(r -> seqs.contains(r.seq()));
            return null;
        }).when(repository).markQueued(anyList());
        doAnswer(inv -> {
            long seq = inv.getArgument(0);
            markedKnown.put(seq, inv.getArgument(1));
            pending.removeIf(r -> r.seq() == seq);
            return null;
        }).when(repository).markKnown(anyLong(), any(), any(), any(), anyLong());
        service.oracleRecords = repository;
        service.chainSvc = chain;
        service.assetGuard = chain.assetGuard();
    }

    private static OracleRecordRepository.Row row(long seq, String market, int asset) {
        return new OracleRecordRepository.Row(seq, 1_790_000_000L + seq, 8_000_000L + seq, "0x" + "%064x".formatted(seq),
                "live", 1_790_000_000L, market, asset);
    }

    private void pend(OracleRecordRepository.Row... rows) {
        pending.addAll(List.of(rows));
    }

    private List<Long> pendingSeqs() {
        return pending.stream().map(OracleRecordRepository.Row::seq).toList();
    }

    @Test
    void everyRecordGoesOutWithItsAssetOnAChainServiceThatUnderstandsFormatTwo() {
        chain.recordFormat = 2;
        pend(row(1, "BTC", 0), row(2, "ETH", 3), row(3, "xyz_NVDA", 200), row(4, "BTC", 0));

        service.publishOnce();

        assertThat(chain.postedRecords()).extracting(ChainSvcClient.OracleRecord::seq, ChainSvcClient.OracleRecord::asset)
                .containsExactly(tuple(1L, 0), tuple(2L, 3),
                        tuple(3L, 200), tuple(4L, 0));
        assertThat(markedQueued).containsExactly(1L, 2L, 3L, 4L);
        assertThat(pending).isEmpty();
        assertThat(chain.publishedAsBtcByMistake()).isEmpty();
        // what the chain service holds is what the server sent, asset included
        assertThat(chain.held(3).asset()).isEqualTo(200);
        assertThat(chain.held(3).priceCents()).isEqualTo(8_000_003L);
    }

    @Test
    void onFormatOneTheStreamStopsAtTheFirstRecordOfAnotherMarketAndLaterBtcRecordsDoNotOvertakeIt() {
        chain.recordFormat = 1;
        pend(row(1, "BTC", 0), row(2, "BTC", 0), row(3, "ETH", 3), row(4, "BTC", 0), row(5, "SOL", 7), row(6, "BTC", 0));

        service.publishOnce();

        assertThat(chain.postedSeqs()).containsExactly(1L, 2L);
        assertThat(pendingSeqs()).containsExactly(3L, 4L, 5L, 6L);
        assertThat(chain.publishedAsBtcByMistake()).isEmpty();
        assertThat(chain.holds(4)).as("the BTC record behind the held one must not overtake it").isFalse();
    }

    @Test
    void aStatusWithoutRecordFormatIsAChainServiceOlderThanFormatTwoAndHoldsTheSame() {
        chain.recordFormat = null;
        pend(row(1, "BTC", 0), row(2, "ETH", 3), row(3, "BTC", 0));

        service.publishOnce();

        assertThat(chain.postedSeqs()).containsExactly(1L);
        assertThat(pendingSeqs()).containsExactly(2L, 3L);
        assertThat(chain.publishedAsBtcByMistake()).isEmpty();
    }

    @Test
    void aChainServiceThatDoesNotAnswerHoldsTheRecordsOfOtherMarketsToo() {
        chain.down = true;
        pend(row(1, "BTC", 0), row(2, "ETH", 3));

        service.publishOnce();

        assertThat(chain.postedSeqs()).containsExactly(1L); // BTC needs no answer to be tried; ETH is never sent
        assertThat(pendingSeqs()).containsExactly(1L, 2L);
        assertThat(chain.publishedAsBtcByMistake()).isEmpty();
    }

    @Test
    void theHeldRecordsGoInSeqOrderThePassAfterTheChainServiceSaysFormatTwo() {
        chain.recordFormat = 1;
        pend(row(1, "BTC", 0), row(2, "ETH", 3), row(3, "BTC", 0), row(4, "SOL", 7));
        service.publishOnce();
        assertThat(chain.postedSeqs()).containsExactly(1L);

        chain.recordFormat = 2; // chainsvc upgraded
        service.publishOnce();

        assertThat(chain.postedSeqs()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(pending).isEmpty();
        assertThat(chain.held(2).asset()).isEqualTo(3);
        assertThat(chain.held(4).asset()).isEqualTo(7);
        assertThat(chain.publishedAsBtcByMistake()).isEmpty();
    }

    @Test
    void aBtcOnlyBacklogNeverReadsTheStatus() {
        chain.recordFormat = 1; // would hold a non-BTC record; irrelevant to these
        pend(row(1, "BTC", 0), row(2, "BTC", 0), row(3, "BTC", 0));

        service.publishOnce();

        assertThat(chain.postedSeqs()).containsExactly(1L, 2L, 3L);
        assertThat(chain.statusReads()).isZero();
    }

    @Test
    void theStatusIsReadLiveForEveryPassThatHasAnotherMarketsRecordSoARollbackIsSeenAtOnce() {
        chain.recordFormat = 2;
        pend(row(1, "ETH", 3));
        service.publishOnce();
        assertThat(chain.postedSeqs()).containsExactly(1L);

        chain.recordFormat = 1; // chainsvc rolled back to the older image between two passes
        pend(row(2, "ETH", 3));
        service.publishOnce();

        assertThat(chain.postedSeqs()).containsExactly(1L); // seq 2 was not sent
        assertThat(pendingSeqs()).containsExactly(2L);
        assertThat(chain.statusReads()).isEqualTo(2);
        assertThat(chain.publishedAsBtcByMistake()).isEmpty();
    }

    @Test
    void sendableKeepsEverythingBeforeTheFirstHeldRecordAndAsksTheFormatOnlyWhenItMatters() {
        List<OracleRecordRepository.Row> rows = List.of(row(1, "BTC", 0), row(2, "ETH", 3), row(3, "BTC", 0));
        int[] asked = {0};

        assertThat(OraclePublisherService.sendable(rows, () -> {
            asked[0]++;
            return false;
        })).extracting(OracleRecordRepository.Row::seq).containsExactly(1L);
        assertThat(OraclePublisherService.sendable(rows, () -> true)).isEqualTo(rows);
        assertThat(OraclePublisherService.sendable(List.of(row(1, "BTC", 0)), () -> {
            asked[0]++;
            return false;
        })).hasSize(1);
        assertThat(OraclePublisherService.sendable(List.of(), () -> false)).isEmpty();
        assertThat(asked[0]).as("asked once for the list with a held record, never for the BTC-only one").isEqualTo(1);

        // the first record itself is held: nothing goes
        assertThat(OraclePublisherService.sendable(List.of(row(5, "ETH", 3), row(6, "BTC", 0)), () -> false)).isEmpty();
    }

    @Test
    void aRecordChainsvcAlreadyHoldsIsMarkedHandedOverInsteadOfBeingResentForever() {
        // the answer to the first POST is lost after chainsvc had persisted the batch
        chain.loseTheResponseOfThePostWith(1);
        pend(row(1, "BTC", 0), row(2, "BTC", 0));

        service.publishOnce();

        assertThat(chain.postedSeqs()).containsExactly(1L, 2L);
        assertThat(markedQueued).isEmpty(); // the answer named nothing
        assertThat(markedKnown).containsOnlyKeys(1L, 2L).containsValue("queued");
        assertThat(pending).isEmpty();

        service.publishOnce(); // nothing left to send
        assertThat(chain.posts()).hasSize(1);
    }

    @Test
    void aRecordChainsvcTookEarlierAndHasConfirmedSinceKeepsItsConfirmedState() {
        chain.confirmedUpTo = 1; // the chain already holds seq 1 (a record we sent whose answer never arrived)
        pend(row(1, "BTC", 0), row(2, "BTC", 0));

        service.publishOnce();

        assertThat(markedQueued).containsExactly(2L);
        assertThat(markedKnown).containsExactly(Map.entry(1L, "confirmed"));
        assertThat(pending).isEmpty();
    }

    @Test
    void aRecordChainsvcAnswersUnknownForStaysPendingAndIsNotMarkedHandedOver() {
        chain.refuseAll = true; // reachable, but it takes nothing
        pend(row(1, "BTC", 0), row(2, "BTC", 0));

        service.publishOnce();

        assertThat(markedQueued).isEmpty();
        assertThat(markedKnown).isEmpty();
        assertThat(pendingSeqs()).containsExactly(1L, 2L);
        assertThat(chain.recordStatusReads()).as("the questions stop at the first row it does not hold").isEqualTo(1);
    }

    @Test
    void aPersistentRefusalCostsOneStatusReadPerChunkNotOnePerRecord() {
        chain.refuseAll = true;
        IntStream.rangeClosed(1, 45).forEach(i -> pend(row(i, "BTC", 0)));

        service.publishOnce();

        assertThat(chain.posts()).extracting(List::size).containsExactly(20, 20, 5);
        assertThat(chain.recordStatusReads()).isEqualTo(3);
        assertThat(pending).hasSize(45);
    }

    @Test
    void rowsHeldBeforeTheFirstRefusedOneAreStillMarkedHandedOver() {
        // seqs 1 and 2 were taken by an earlier POST whose answer was lost; 3 and 4 are new and the POST is
        // refused this time — 1 and 2 are settled, 3 and 4 stay pending
        chain.confirmedUpTo = 0;
        chain.oracleRecords(List.of(new ChainSvcClient.OracleRecord(1, 1L, 1L, "0x" + "00".repeat(32), "live", 1L),
                new ChainSvcClient.OracleRecord(2, 2L, 2L, "0x" + "00".repeat(32), "live", 2L)));
        chain.refuseAll = true;
        pend(row(1, "BTC", 0), row(2, "BTC", 0), row(3, "BTC", 0), row(4, "BTC", 0));

        service.publishOnce();

        assertThat(markedKnown).containsOnlyKeys(1L, 2L);
        assertThat(pendingSeqs()).containsExactly(3L, 4L);
    }

    @Test
    void recordsGoInChunksOfTwentyInSeqOrder() {
        IntStream.rangeClosed(1, 45).forEach(i -> pend(row(i, "BTC", 0)));

        service.publishOnce();

        assertThat(chain.posts()).extracting(List::size).containsExactly(20, 20, 5);
        assertThat(chain.postedSeqs()).isSorted();
        assertThat(pending).isEmpty();
    }

    @Test
    void anOutageEndsThePassAfterOneChunkInsteadOfTimingOutOnceForEveryChunk() {
        chain.down = true;
        IntStream.rangeClosed(1, 45).forEach(i -> pend(row(i, "BTC", 0)));

        service.publishOnce();

        assertThat(chain.posts()).hasSize(1);
        assertThat(pending).hasSize(45);
    }

    @Test
    void nothingPendingMeansNoCallAtAll() {
        service.publishOnce();

        assertThat(chain.posts()).isEmpty();
        assertThat(chain.statusReads()).isZero();
    }
}
