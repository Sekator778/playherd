package crowdvsmarket.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OracleRecordRepository#journal}: the read behind the public {@code GET /api/oracle/records}
 * (docs/research/markets_architecture.md §7 item 6) — rows of every market, ascending by {@code seq}, from a
 * {@code seq} inclusive, capped by a limit, plus {@code firstSeq}, the start of this database's range.
 * {@link LiveMarketsProfile}: no scheduler, no bots, so nothing else writes {@code oracle_records} while a
 * test counts its rows.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class OracleRecordJournalTest {

    @Inject
    Db db;
    @Inject
    OracleRecordRepository oracleRecords;

    @BeforeEach
    void emptyJournalBefore() {
        db.tx(c -> db.update(c, "DELETE FROM oracle_records"));
    }

    @AfterEach
    void emptyJournalAfter() {
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

    @Test
    void anEmptyDatabaseHasNoFirstSeqAndNoRows() {
        OracleRecordRepository.JournalPage page = oracleRecords.journal(0, 200);

        assertThat(page.firstSeq()).isNull();
        assertThat(page.rows()).isEmpty();
    }

    @Test
    void rowsAreAscendingFromTheGivenSeqInclusiveWithTheMarketAndAssetOfEveryRow() {
        insert(1150, "ETH", 3);
        insert(1146, "BTC", 0);
        insert(1148, "xyz_NVDA", 200);
        insert(1147, "ETH", 3);
        insert(1149, "BTC", 0);

        OracleRecordRepository.JournalPage page = oracleRecords.journal(1148, 200);

        assertThat(page.firstSeq()).as("the start of the database, whatever fromSeq says").isEqualTo(1146L);
        assertThat(page.rows()).extracting(OracleRecordRepository.JournalRow::seq).containsExactly(1148L, 1149L, 1150L);
        OracleRecordRepository.JournalRow nvda = page.rows().get(0);
        assertThat(nvda.market()).isEqualTo("xyz_NVDA");
        assertThat(nvda.assetIndex()).isEqualTo(200);
        assertThat(nvda.roundId()).startsWith("xyz_NVDA-1h-");
        assertThat(nvda.ts()).isEqualTo(1_790_000_000L + 1148);
        assertThat(nvda.priceCents()).isEqualTo(8_000_000L + 1148);
        assertThat(nvda.resultsHash()).isEqualTo("0x" + "%064x".formatted(1148L));
        assertThat(page.rows().get(1).roundId()).startsWith("1h-"); // a BTC round id has no market in it
        assertThat(page.rows().get(1).market()).isEqualTo("BTC");
        assertThat(page.rows().get(1).assetIndex()).isZero();
    }

    @Test
    void theLimitCapsThePageAndPagingByLastSeqPlusOneVisitsEveryRowOnce() {
        for (long seq = 1146; seq < 1146 + 7; seq++) {
            insert(seq, seq % 2 == 0 ? "BTC" : "ETH", seq % 2 == 0 ? 0 : 3);
        }

        List<Long> seen = new ArrayList<>();
        long cursor = 0;
        int pages = 0;
        while (true) {
            OracleRecordRepository.JournalPage page = oracleRecords.journal(cursor, 3);
            pages++;
            page.rows().forEach(r -> seen.add(r.seq()));
            if (page.rows().size() < 3) {
                break;
            }
            cursor = page.rows().get(page.rows().size() - 1).seq() + 1;
        }

        assertThat(seen).containsExactly(1146L, 1147L, 1148L, 1149L, 1150L, 1151L, 1152L);
        assertThat(pages).isEqualTo(3); // 3 + 3 + 1
    }

    @Test
    void aFromSeqBeyondTheLastRowIsAnEmptyPageThatStillKnowsWhereTheDatabaseStarts() {
        insert(1146, "BTC", 0);

        OracleRecordRepository.JournalPage page = oracleRecords.journal(5000, 200);

        assertThat(page.rows()).isEmpty();
        assertThat(page.firstSeq()).isEqualTo(1146L);
    }

    @Test
    void theStateIsWhatReconciliationLastStoredAndUnknownBeforeTheFirstPoll() {
        insert(1146, "BTC", 0);
        insert(1147, "ETH", 3);
        oracleRecords.updateReconciledState(1147, "confirmed", 4_737_632L, "0xcoin", 1_790_000_100_000L);

        OracleRecordRepository.JournalPage page = oracleRecords.journal(0, 200);

        assertThat(page.rows()).extracting(OracleRecordRepository.JournalRow::state)
                .containsExactly("unknown", "confirmed");
    }

    @Test
    void everyRowIsInTheJournalWhetherItWasHandedToChainsvcOrNot() {
        insert(1146, "BTC", 0);
        insert(1147, "ETH", 3);
        oracleRecords.markQueued(List.of(1146L));

        assertThat(oracleRecords.journal(0, 200).rows()).extracting(OracleRecordRepository.JournalRow::seq)
                .containsExactly(1146L, 1147L);
    }
}
