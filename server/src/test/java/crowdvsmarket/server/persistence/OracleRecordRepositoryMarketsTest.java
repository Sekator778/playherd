package crowdvsmarket.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The oracle record carries its market and the market's registry index (V7, docs/research/
 * markets_architecture.md §6/§7). Until chainsvc's record format 2 is wired, only asset index 0 (BTC)
 * leaves the server: a record of any other asset would be announced on chain as a BTC price.
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

    private void insert(String market, int asset, String roundId) {
        long seq = serverMeta.reserveOracleSeqRange(1);
        db.tx(c -> {
            oracleRecords.insert(c, new OracleRecordRepository.NewRecord(market, asset, roundId, seq, 1_700_000_000L,
                    2_400_000L, "0xhash" + seq, "live", 1_700_000_000L, 1_700_000_000_000L));
            return null;
        });
    }

    @Test
    void theRowCarriesMarketAndAssetIndexAndOnlyBtcIsPending() {
        insert("BTC", 0, "orm-test-1h-1");
        insert("ETH", 3, "orm-test-ETH-1h-1");

        List<OracleRecordRepository.Row> pending = oracleRecords.pending();

        assertThat(pending).extracting(OracleRecordRepository.Row::market).contains("BTC").doesNotContain("ETH");
        assertThat(pending).allSatisfy(row -> assertThat(row.assetIndex()).isZero());
        String storedMarket = db.read(c -> db.queryOne(c, "SELECT market FROM oracle_records WHERE round_id = ?",
                rs -> rs.getString("market"), "orm-test-ETH-1h-1"));
        Integer storedAsset = db.read(c -> db.queryOne(c, "SELECT asset_index FROM oracle_records WHERE round_id = ?",
                rs -> rs.getInt("asset_index"), "orm-test-ETH-1h-1"));
        assertThat(storedMarket).isEqualTo("ETH");
        assertThat(storedAsset).isEqualTo(3);
        // the held-back row is still counted as waiting, and still unqueued
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
}
