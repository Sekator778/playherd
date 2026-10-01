package crowdvsmarket.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The oracle record carries its market and the market's registry index (V7, docs/research/
 * markets_architecture.md §6/§7). Every market's record is pending for chainsvc with its asset index; whether
 * a record of a market other than BTC may actually leave is decided by {@code OraclePublisherService}, which
 * knows what the chain service says about its record format (see {@code OraclePublisherFormatTest}) — not by
 * this query, which used to hide such rows as a stop-gap until record format 2 was wired.
 */
@QuarkusTest
class OracleRecordRepositoryMarketsTest {

    @Inject
    Db db;
    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    ServerMetaRepository serverMeta;

    @AfterEach
    void cleanUp() {
        db.tx(c -> db.update(c, "DELETE FROM oracle_records WHERE round_id LIKE 'orm-test-%'"));
    }

    private long insert(String market, int asset, String roundId) {
        long seq = serverMeta.reserveOracleSeqRange(1);
        db.tx(c -> {
            oracleRecords.insert(c, new OracleRecordRepository.NewRecord(market, asset, roundId, seq, 1_700_000_000L,
                    2_400_000L, "0xhash" + seq, "live", 1_700_000_000L, 1_700_000_000_000L));
            return null;
        });
        return seq;
    }

    @Test
    void theRowCarriesMarketAndAssetIndexAndEveryMarketIsPending() {
        long btc = insert("BTC", 0, "orm-test-1h-1");
        long eth = insert("ETH", 3, "orm-test-ETH-1h-1");

        List<OracleRecordRepository.Row> pending = oracleRecords.pending();

        OracleRecordRepository.Row btcRow = pending.stream().filter(r -> r.seq() == btc).findFirst().orElseThrow();
        OracleRecordRepository.Row ethRow = pending.stream().filter(r -> r.seq() == eth).findFirst().orElseThrow();
        assertThat(btcRow.market()).isEqualTo("BTC");
        assertThat(btcRow.assetIndex()).isZero();
        assertThat(ethRow.market()).isEqualTo("ETH");
        assertThat(ethRow.assetIndex()).isEqualTo(3);
        assertThat(ethRow.priceCents()).isEqualTo(2_400_000L);
        assertThat(pending).extracting(OracleRecordRepository.Row::seq).isSorted();
        String storedMarket = db.read(c -> db.queryOne(c, "SELECT market FROM oracle_records WHERE round_id = ?",
                rs -> rs.getString("market"), "orm-test-ETH-1h-1"));
        Integer storedAsset = db.read(c -> db.queryOne(c, "SELECT asset_index FROM oracle_records WHERE round_id = ?",
                rs -> rs.getInt("asset_index"), "orm-test-ETH-1h-1"));
        assertThat(storedMarket).isEqualTo("ETH");
        assertThat(storedAsset).isEqualTo(3);
        assertThat(oracleRecords.pendingCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void theBeforeMarketsInsertIsBtcWithAssetIndexZero() {
        long seq = serverMeta.reserveOracleSeqRange(1);
        oracleRecords.insert("orm-test-1h-legacy", seq, 1L, 5_000_000L, "0xlegacy", "replay", 1L, 1L);

        OracleRecordRepository.Row row = oracleRecords.pending().stream().filter(r -> r.seq() == seq).findFirst()
                .orElseThrow();

        assertThat(row.market()).isEqualTo("BTC");
        assertThat(row.assetIndex()).isZero();
    }

    @Test
    void aRowHandedOverOrKnownToChainsvcIsNoLongerPending() {
        long handedOver = insert("ETH", 3, "orm-test-ETH-1h-2");
        long alreadyKnown = insert("SOL", 7, "orm-test-SOL-1h-2");
        oracleRecords.markQueued(List.of(handedOver));
        oracleRecords.markKnown(alreadyKnown, "confirmed", 4_737_632L, "0xcoin", 1_700_000_001_000L);

        assertThat(oracleRecords.pending()).extracting(OracleRecordRepository.Row::seq)
                .doesNotContain(handedOver, alreadyKnown);
        // what chainsvc reported is kept, and the row is now one reconciliation looks at only until confirmed
        var anchor = oracleRecords.findAnchorByRoundId("orm-test-SOL-1h-2").orElseThrow();
        assertThat(anchor.state()).isEqualTo("confirmed");
        assertThat(anchor.height()).isEqualTo(4_737_632L);
        assertThat(oracleRecords.needsReconciliation()).extracting(OracleRecordRepository.ReconciliationRow::seq)
                .contains(handedOver).doesNotContain(alreadyKnown);
    }

    @Test
    void theReconciliationRowKnowsTheMarketAndAssetIndexOfARecordItMayHaveToResend() {
        long seq = insert("xyz_NVDA", 200, "orm-test-xyz_NVDA-1h-3");
        oracleRecords.markQueued(List.of(seq));

        OracleRecordRepository.ReconciliationRow row = oracleRecords.needsReconciliation().stream()
                .filter(r -> r.seq() == seq).findFirst().orElseThrow();

        assertThat(row.market()).isEqualTo("xyz_NVDA");
        assertThat(row.assetIndex()).isEqualTo(200);
        assertThat(row.priceCents()).isEqualTo(2_400_000L);
    }
}
