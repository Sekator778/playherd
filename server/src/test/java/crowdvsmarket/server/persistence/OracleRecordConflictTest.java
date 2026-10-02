package crowdvsmarket.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * F2 on the table (V8, {@code oracle_records.post_attempted_at_ms}): a record may be marked as already known to
 * chainsvc only when this server has POSTed it before; a record that chainsvc holds without this server ever
 * sending it is a seq collision — state {@code conflict}, which the round's {@code oracleAnchor} and the public
 * journal show, out of the pending set, the pending count and the reconciliation, and never overwritten by it.
 * {@link LiveMarketsProfile}: no scheduler, no bots — nothing else writes {@code oracle_records} while a test
 * counts its rows.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class OracleRecordConflictTest {

    @Inject
    Db db;
    @Inject
    OracleRecordRepository oracleRecords;

    @BeforeEach
    void emptyBefore() {
        db.tx(c -> db.update(c, "DELETE FROM oracle_records"));
    }

    @AfterEach
    void emptyAfter() {
        db.tx(c -> db.update(c, "DELETE FROM oracle_records"));
    }

    private void insert(long seq, String market, int asset) {
        String roundId = market.equals("BTC") ? "1h-" + (1_790_000_000_000L + seq * 180_000)
                : market + "-1h-" + (1_790_000_000_000L + seq * 180_000);
        db.tx(c -> {
            oracleRecords.insert(c, new OracleRecordRepository.NewRecord(market, asset, roundId, seq,
                    1_790_000_000L + seq, 8_000_000L + seq, "0x" + "%064x".formatted(seq), "live",
                    1_789_999_990L + seq, 1_790_000_000_000L));
            return null;
        });
    }

    private static String roundId(long seq) {
        return "1h-" + (1_790_000_000_000L + seq * 180_000);
    }

    private Long attemptedAt(long seq) {
        return db.read(c -> db.queryOne(c, "SELECT post_attempted_at_ms FROM oracle_records WHERE seq = ?",
                rs -> (Long) rs.getObject("post_attempted_at_ms"), seq));
    }

    @Test
    void aFreshRowHasNoAttemptOnRecord() {
        insert(2001, "BTC", 0);

        assertThat(attemptedAt(2001)).isNull();
        assertThat(oracleRecords.pending()).singleElement().satisfies(r -> {
            assertThat(r.seq()).isEqualTo(2001L);
            assertThat(r.postAttemptedAtMs()).isNull();
        });
    }

    @Test
    void aRowNeverPostedCannotBeMarkedKnownWhateverChainsvcSays() {
        insert(2001, "BTC", 0);

        boolean marked = oracleRecords.markKnown(2001, "confirmed", 4_000_005L, "0xcoin5", 1_790_000_100_000L);

        assertThat(marked).isFalse();
        assertThat(oracleRecords.pending()).extracting(OracleRecordRepository.Row::seq).containsExactly(2001L);
        assertThat(oracleRecords.findAnchorByRoundId(roundId(2001)).orElseThrow().state()).isEqualTo("unknown");
        assertThat(oracleRecords.findAnchorByRoundId(roundId(2001)).orElseThrow().height()).isNull();
    }

    @Test
    void theAttemptTimeIsTheFirstOneAndOnlyARowWithOneMayBeMarkedKnown() {
        insert(2001, "BTC", 0);
        insert(2002, "BTC", 0);

        oracleRecords.markPostAttempted(List.of(2001L), 1_790_000_000_100L);
        oracleRecords.markPostAttempted(List.of(2001L, 2002L), 1_790_000_000_900L); // a second pass: 2001 keeps its time

        assertThat(attemptedAt(2001)).isEqualTo(1_790_000_000_100L);
        assertThat(attemptedAt(2002)).isEqualTo(1_790_000_000_900L);
        assertThat(oracleRecords.pending()).extracting(OracleRecordRepository.Row::postAttemptedAtMs)
                .containsExactly(1_790_000_000_100L, 1_790_000_000_900L);
        assertThat(oracleRecords.markKnown(2001, "confirmed", 4_000_005L, "0xcoin5", 1_790_000_100_000L)).isTrue();
        var anchor = oracleRecords.findAnchorByRoundId(roundId(2001)).orElseThrow();
        assertThat(anchor.state()).isEqualTo("confirmed");
        assertThat(anchor.height()).isEqualTo(4_000_005L);
        assertThat(oracleRecords.pending()).extracting(OracleRecordRepository.Row::seq).containsExactly(2002L);
        oracleRecords.markPostAttempted(List.of(), 1L); // nothing to do, nothing thrown
    }

    @Test
    void aCollisionIsShownAsConflictByTheAnchorAndThePublicJournalWithNoHeightAndNoCoin() {
        insert(2001, "BTC", 0);
        insert(2002, "ETH", 3);

        assertThat(oracleRecords.markConflict(2001, 1_790_000_100_000L)).isTrue();

        var anchor = oracleRecords.findAnchorByRoundId(roundId(2001)).orElseThrow();
        assertThat(anchor.seq()).isEqualTo(2001L);
        assertThat(anchor.state()).isEqualTo("conflict");
        assertThat(anchor.height()).isNull();
        OracleRecordRepository.JournalPage page = oracleRecords.journal(0, 200);
        assertThat(page.rows()).extracting(OracleRecordRepository.JournalRow::seq).containsExactly(2001L, 2002L);
        assertThat(page.rows()).extracting(OracleRecordRepository.JournalRow::state).containsExactly("conflict", "unknown");
        String coin = db.read(c -> db.queryOne(c, "SELECT coin_id FROM oracle_records WHERE seq = 2001",
                rs -> rs.getString("coin_id")));
        assertThat(coin).isNull();
    }

    @Test
    void aCollisionIsNotPendingNotCountedNotReconciledAndNotOverwrittenByTheReconciliation() {
        insert(2001, "BTC", 0);
        insert(2002, "BTC", 0);
        int before = oracleRecords.pendingCount();
        oracleRecords.markConflict(2001, 1_790_000_100_000L);

        assertThat(oracleRecords.pending()).extracting(OracleRecordRepository.Row::seq).containsExactly(2002L);
        assertThat(oracleRecords.pendingCount()).isEqualTo(before - 1);
        assertThat(oracleRecords.needsReconciliation()).isEmpty();

        // what the reconciliation would write if it asked about this seq: the OTHER record's state
        oracleRecords.updateReconciledState(2001, "confirmed", 4_000_005L, "0xcoin5", 1_790_000_200_000L);
        oracleRecords.markQueued(List.of(2001L, 2002L)); // not even a "handed over" mark makes it one of ours

        assertThat(oracleRecords.findAnchorByRoundId(roundId(2001)).orElseThrow().state()).isEqualTo("conflict");
        assertThat(oracleRecords.findAnchorByRoundId(roundId(2001)).orElseThrow().height()).isNull();
        assertThat(oracleRecords.needsReconciliation()).extracting(OracleRecordRepository.ReconciliationRow::seq)
                .containsExactly(2002L); // the record that really went is followed to its confirmation
    }

    @Test
    void onlyARowThatWasNeverPostedCanBeFlaggedAndAFlagIsNotUndoneByAnAttempt() {
        insert(2001, "BTC", 0);
        insert(2002, "BTC", 0);
        oracleRecords.markPostAttempted(List.of(2002L), 1_790_000_000_100L);

        assertThat(oracleRecords.markConflict(2002, 1_790_000_100_000L)).as("it was sent: whatever chainsvc holds is ours").isFalse();
        assertThat(oracleRecords.markConflict(2001, 1_790_000_100_000L)).isTrue();
        assertThat(oracleRecords.markConflict(404, 1_790_000_100_000L)).as("no such row").isFalse();

        assertThat(oracleRecords.findAnchorByRoundId(roundId(2002)).orElseThrow().state()).isEqualTo("unknown");
        // a flag is terminal: neither an attempt on record nor a "known" mark can make a collision one of ours
        oracleRecords.markPostAttempted(List.of(2001L), 1_790_000_000_500L);
        assertThat(attemptedAt(2001)).isNull();
        assertThat(oracleRecords.markKnown(2001, "confirmed", 1L, "0xc", 1L)).isFalse();
        assertThat(oracleRecords.findAnchorByRoundId(roundId(2001)).orElseThrow().state()).isEqualTo("conflict");
        assertThat(oracleRecords.pending()).extracting(OracleRecordRepository.Row::seq).containsExactly(2002L);
    }
}
