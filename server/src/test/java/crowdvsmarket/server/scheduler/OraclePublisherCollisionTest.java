package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.chain.FakeChainSvc;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.persistence.ServerMetaRepository;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F2 end to end on the database (second adversarial review): the publisher against {@link FakeChainSvc} with the
 * real queue rules, the repository of the real Postgres. A seq that the chain service already holds, which this
 * server never sent, used to end as {@code confirmed at height H} in the round's anchor and in the public journal for
 * a record chainsvc never took. {@link LiveMarketsProfile}: no scheduler, so the test's own calls are the only
 * publisher.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class OraclePublisherCollisionTest {

    @InjectMock
    ChainSvcClient chainSvcClient;

    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    OraclePublisherService publisher;
    @Inject
    OracleReconciliationService reconciliation;
    @Inject
    ServerMetaRepository serverMeta;
    @Inject
    Db db;

    @BeforeEach
    void emptyBefore() {
        db.tx(c -> db.update(c, "DELETE FROM oracle_records"));
    }

    @AfterEach
    void emptyAfter() {
        db.tx(c -> db.update(c, "DELETE FROM oracle_records"));
    }

    private long insert(String roundIdPrefix, long seq) {
        long now = System.currentTimeMillis();
        db.tx(c -> {
            oracleRecords.insert(c, new OracleRecordRepository.NewRecord("BTC", 0, roundIdPrefix + seq, seq, now / 1000,
                    8_000_000L + seq, "0xhash" + seq, "live", now / 1000, now));
            return null;
        });
        return seq;
    }

    private Rounds.OracleAnchorDto anchor(String roundIdPrefix, long seq) {
        return oracleRecords.findAnchorByRoundId(roundIdPrefix + seq).orElseThrow();
    }

    @Test
    void aSeqTheChainAlreadyHoldsIsAConflictInTheAnchorAndTheJournalNotAConfirmedRecord() {
        FakeChainSvc chain = new FakeChainSvc().install(chainSvcClient);
        long seq = serverMeta.reserveOracleSeqRange(1);
        long free = serverMeta.reserveOracleSeqRange(1);
        chain.confirmedUpTo = seq; // the chain already holds everything up to our seq: the floor came too late
        insert("collision-", seq);
        insert("collision-", free);

        publisher.publishOnce();

        // not marked as anchored, not sent, not held by the chain service
        assertThat(anchor("collision-", seq).state()).isEqualTo("conflict");
        assertThat(anchor("collision-", seq).height()).isNull();
        assertThat(chain.postedSeqs()).containsExactly(free);
        assertThat(chain.held(seq)).isNull();
        // the public journal says the same, and the record after it went on as usual
        OracleRecordRepository.JournalPage page = oracleRecords.journal(seq, 10);
        assertThat(page.rows()).extracting(OracleRecordRepository.JournalRow::seq).containsExactly(seq, free);
        assertThat(page.rows().get(0).state()).isEqualTo("conflict");
        assertThat(page.rows().get(1).state()).isEqualTo("unknown"); // handed over; the reconciliation will say queued
        assertThat(chain.held(free)).isNotNull();
        assertThat(oracleRecords.needsReconciliation()).extracting(OracleRecordRepository.ReconciliationRow::seq)
                .containsExactly(free);
        // nothing of it is pending, so the next pass does not hammer chainsvc with a question per tick
        assertThat(oracleRecords.pending()).isEmpty();
        int reads = chain.recordStatusReads();
        publisher.publishOnce();
        assertThat(chain.recordStatusReads()).isEqualTo(reads);
        assertThat(chain.posts()).hasSize(1);
    }

    @Test
    void theReconciliationNeitherPollsNorOverwritesAConflict() {
        FakeChainSvc chain = new FakeChainSvc().install(chainSvcClient);
        long seq = serverMeta.reserveOracleSeqRange(1);
        chain.confirmedUpTo = seq;
        insert("collision-", seq);
        publisher.publishOnce();
        assertThat(anchor("collision-", seq).state()).isEqualTo("conflict");
        clearInvocations(chainSvcClient);

        reconciliation.reconcile();

        verify(chainSvcClient, never()).oracleRecordStatus(seq);
        assertThat(anchor("collision-", seq).state()).isEqualTo("conflict"); // the seq's record at chainsvc says "confirmed"
        assertThat(anchor("collision-", seq).height()).isNull();
    }

    @Test
    void aSeqAnotherWriterQueuedIsAConflictAndTheOtherWritersRecordIsLeftAsItWas() {
        FakeChainSvc chain = new FakeChainSvc().install(chainSvcClient);
        long seq = serverMeta.reserveOracleSeqRange(1);
        chain.oracleRecords(List.of(new ChainSvcClient.OracleRecord(seq, 1_700_000_000L, 5L, "0x" + "ab".repeat(32),
                "live", 1L))); // a second writer on the same singleton
        insert("collision-", seq);

        publisher.publishOnce();

        assertThat(anchor("collision-", seq).state()).isEqualTo("conflict");
        assertThat(chain.held(seq).resultsHash()).isEqualTo("0x" + "ab".repeat(32));
        assertThat(chain.postedSeqs()).containsExactly(seq); // only the other writer's own POST, none of ours
    }

    @Test
    void aFirstPostWhoseAnswerWasLostIsStillAnAnchorNotAConflict() {
        FakeChainSvc chain = new FakeChainSvc().install(chainSvcClient);
        long seq = serverMeta.reserveOracleSeqRange(1);
        chain.loseTheResponseOfThePostWith(seq); // chainsvc persists the batch, the answer never arrives
        insert("lost-", seq);

        publisher.publishOnce();

        assertThat(anchor("lost-", seq).state()).isEqualTo("queued");
        assertThat(chain.held(seq).resultsHash()).isEqualTo("0xhash" + seq); // it is OUR record that chainsvc holds
        assertThat(chain.postedSeqs()).containsExactly(seq);
        assertThat(oracleRecords.pending()).isEmpty();
        assertThat(oracleRecords.needsReconciliation()).extracting(OracleRecordRepository.ReconciliationRow::seq)
                .containsExactly(seq); // and the reconciliation follows it to its confirmation
    }

    @Test
    void aChainServiceThatDoesNotAnswerTheQuestionGetsNoPostAndNoAttemptOnRecord() {
        FakeChainSvc chain = new FakeChainSvc().install(chainSvcClient);
        long seq = serverMeta.reserveOracleSeqRange(1);
        insert("down-", seq);
        chain.down = true;

        publisher.publishOnce();

        assertThat(chain.posts()).isEmpty();
        assertThat(oracleRecords.pending()).singleElement().satisfies(r -> assertThat(r.postAttemptedAtMs()).isNull());
        chain.down = false;
        publisher.publishOnce();
        assertThat(chain.postedSeqs()).containsExactly(seq);
        assertThat(oracleRecords.pending()).isEmpty();
        assertThat(anchor("down-", seq).state()).isEqualTo("unknown"); // handed over, not a conflict
    }
}
