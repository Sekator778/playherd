package crowdvsmarket.server.chain;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.ServerMetaRepository;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ChainOracleSeqGate} — the oracle seq floor (docs/research/markets_architecture.md §7 item 4) against a
 * real database counter and {@link FakeChainSvc}. One oracle singleton lives on chain for good while a
 * server database can be new: a fresh database whose counter starts at 1 although the chain already holds
 * {@code seq} 1..1145 would hand out numbers the chain service ignores as already known. The gate raises the
 * counter to the chain service's {@code maxKnownSeq} before the first {@code seq} is issued.
 *
 * <p>{@link LiveMarketsProfile}: no scheduler (the test calls {@code refresh()} itself, which is what the
 * 5-second tick calls) and no bots, so nothing else reserves a {@code seq} while a test looks at the counter.
 * The counter is a database-wide value that survives test classes — each test sets it where it wants it and
 * puts it back at or above where it found it, never lower.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class ChainOracleSeqGateTest {

    @Inject
    ServerMetaRepository serverMeta;
    @Inject
    Db db;

    private final FakeChainSvc chain = new FakeChainSvc();
    private long counterBefore;

    @BeforeEach
    void rememberTheCounter() {
        counterBefore = counter();
    }

    @AfterEach
    void putTheCounterBackAtOrAboveWhereItWas() {
        setCounter(Math.max(counterBefore, counter()));
    }

    private long counter() {
        String value = db.read(c -> db.queryOne(c, "SELECT value FROM server_meta WHERE key = 'oracle_seq'",
                rs -> rs.getString("value")));
        return value == null ? 0 : Long.parseLong(value);
    }

    private void setCounter(long value) {
        db.tx(c -> db.update(c, """
                INSERT INTO server_meta (key, value) VALUES ('oracle_seq', ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                """, Long.toString(value)));
    }

    private ChainOracleSeqGate gate(boolean required) {
        ChainOracleSeqGate gate = new ChainOracleSeqGate();
        gate.requireSeqFloor = required;
        gate.chainSvc = chain;
        gate.serverMeta = serverMeta;
        return gate;
    }

    @Test
    void aFreshDatabaseAgainstAChainAt1145IssuesSeq1146First() {
        setCounter(0); // a database that never issued a seq
        chain.confirmedUpTo = 1145;
        ChainOracleSeqGate gate = gate(true);
        assertThat(gate.open()).as("nothing read from the chain service yet").isFalse();

        gate.refresh();

        assertThat(gate.open()).isTrue();
        assertThat(serverMeta.reserveOracleSeqRange(1)).isEqualTo(1146);
        assertThat(serverMeta.reserveOracleSeqRange(1)).isEqualTo(1147);
    }

    @Test
    void aFreshDatabaseWithoutTheFloorWouldIssueSeqsTheChainServiceIgnoresAsAlreadyKnown() {
        setCounter(0);
        chain.confirmedUpTo = 1145;
        long first = serverMeta.reserveOracleSeqRange(1);

        ChainSvcClient.OracleRecordsResult result = chain.oracleRecords(List.of(
                new ChainSvcClient.OracleRecord(first, 1_790_000_000L, 8_561_200L, "0x" + "ab".repeat(32), "live",
                        1_790_000_000L)));

        assertThat(first).isEqualTo(1);
        assertThat(result.acceptedSeqs()).as("a seq below the chain's history is taken as a replay").isEmpty();
    }

    @Test
    void aFloorBelowTheCounterChangesNothingAndStillOpensARequiredGate() {
        setCounter(5000);
        chain.confirmedUpTo = 1145;
        ChainOracleSeqGate gate = gate(true);

        gate.refresh();

        assertThat(counter()).isEqualTo(5000);
        assertThat(gate.open()).isTrue();
        assertThat(serverMeta.reserveOracleSeqRange(1)).isEqualTo(5001);
    }

    @Test
    void aRequiredGateStaysClosedWhileTheChainServiceIsDownAndOpensWhenItAnswers() {
        setCounter(0);
        chain.confirmedUpTo = 1145;
        chain.down = true;
        ChainOracleSeqGate gate = gate(true);

        gate.refresh();
        gate.refresh();
        gate.refresh();

        assertThat(gate.open()).isFalse();
        assertThat(counter()).as("nothing was raised without an answer").isZero();

        chain.down = false;
        gate.refresh();

        assertThat(gate.open()).isTrue();
        assertThat(counter()).isEqualTo(1145);
    }

    @Test
    void aStatusWithoutMaxKnownSeqNeverOpensARequiredGate() {
        setCounter(0);
        chain.reportsMaxKnownSeq = false; // a chain service older than record format 2
        chain.recordFormat = null;
        chain.confirmedUpTo = 1145;
        ChainOracleSeqGate gate = gate(true);

        gate.refresh();
        gate.refresh();

        assertThat(gate.open()).isFalse();
        assertThat(counter()).isZero();

        chain.reportsMaxKnownSeq = true; // upgraded
        chain.recordFormat = 2;
        gate.refresh();

        assertThat(gate.open()).isTrue();
        assertThat(counter()).isEqualTo(1145);
    }

    @Test
    void aGateThatIsNotRequiredIsAlwaysOpenButStillRaisesTheCounterOnceItCanRead() {
        setCounter(0);
        chain.confirmedUpTo = 1145;
        ChainOracleSeqGate gate = gate(false);
        assertThat(gate.open()).isTrue();

        chain.down = true;
        gate.refresh();
        assertThat(gate.open()).isTrue();
        assertThat(counter()).isZero();

        chain.down = false;
        gate.refresh();
        assertThat(gate.open()).isTrue();
        assertThat(counter()).isEqualTo(1145);
    }

    @Test
    void aGateThatIsNotRequiredIsOpenEvenAgainstAChainServiceWithoutMaxKnownSeq() {
        chain.reportsMaxKnownSeq = false;
        chain.recordFormat = null;
        ChainOracleSeqGate gate = gate(false);

        gate.refresh();

        assertThat(gate.open()).isTrue();
    }

    @Test
    void aLaterHigherMaxKnownSeqRaisesTheCounterAgainAndALowerOneNeverLowersIt() {
        setCounter(0);
        chain.confirmedUpTo = 1145;
        ChainOracleSeqGate gate = gate(true);
        gate.refresh();
        assertThat(counter()).isEqualTo(1145);

        chain.confirmedUpTo = 2000; // another writer, or the chain service finished its bootstrap from the chain
        gate.refresh();
        assertThat(counter()).isEqualTo(2000);
        assertThat(serverMeta.reserveOracleSeqRange(1)).isEqualTo(2001);

        chain.confirmedUpTo = 1500; // a chain service that lost state
        gate.refresh();
        assertThat(counter()).as("never lowered").isEqualTo(2001);
        assertThat(gate.open()).isTrue();
    }

    @Test
    void onceOpenTheGateStaysOpenWhenTheChainServiceGoesAwayForTheFloorWasObtainedForThisProcess() {
        setCounter(0);
        chain.confirmedUpTo = 1145;
        ChainOracleSeqGate gate = gate(true);
        gate.refresh();
        assertThat(gate.open()).isTrue();

        chain.down = true;
        gate.refresh();
        gate.refresh();

        assertThat(gate.open()).isTrue();
        assertThat(counter()).isEqualTo(1145);
    }

    @Test
    void aFloorFromACorruptAnswerIsNotAppliedAndDoesNotOpenTheGate() {
        setCounter(0);
        chain.confirmedUpTo = 1L << 60; // far beyond anything a real chain holds
        ChainOracleSeqGate gate = gate(true);

        gate.refresh();

        assertThat(gate.open()).isFalse();
        assertThat(counter()).isZero();
    }

    @Test
    void aChainServiceThatHasNothingYetGivesFloorZeroWhichStillOpensTheGate() {
        setCounter(7);
        chain.confirmedUpTo = 0; // maxKnownSeq 0: an empty queue
        ChainOracleSeqGate gate = gate(true);

        gate.refresh();

        assertThat(gate.open()).isTrue();
        assertThat(counter()).isEqualTo(7);
    }

    @Test
    void theRefreshRunsEveryFiveSecondsAndNeverOverlapsItself() throws NoSuchMethodException {
        Scheduled scheduled = ChainOracleSeqGate.class.getDeclaredMethod("refresh").getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.every()).isEqualTo("5s");
        assertThat(scheduled.concurrentExecution()).isEqualTo(Scheduled.ConcurrentExecution.SKIP);
    }
}
