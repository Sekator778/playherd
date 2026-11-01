package crowdvsmarket.server.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.persistence.ServerMetaRepository;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The two public endpoints the oracle verifier reads (docs/research/markets_architecture.md §7 item 5–6), over
 * real HTTP, in exactly the field names {@code tools/verify-oracle} reads:
 * {@code GET /api/markets/registry.json} and {@code GET /api/oracle/records?fromSeq=&limit=}. Public (no
 * session), {@code no-store}, and a malformed query is a 400 — never a 404, which the verifier takes for "a
 * stand without the journal".
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class OracleEndpointsTest extends LiveGameTestBase {

    @Inject
    ObjectMapper mapper;
    @Inject
    OracleRecordRepository oracleRecords;
    @Inject
    ServerMetaRepository serverMeta;

    @BeforeEach
    void emptyJournalBefore() {
        db.tx(c -> db.update(c, "DELETE FROM oracle_records"));
    }

    @AfterEach
    void emptyJournalAfter() {
        db.tx(c -> db.update(c, "DELETE FROM oracle_records"));
    }

    private JsonNode json(Response response) throws Exception {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        return mapper.readTree(response.asString());
    }

    private long insert(String market, int asset, long ordinal) {
        long seq = serverMeta.reserveOracleSeqRange(1);
        String roundId = market.equals("BTC") ? "1h-" + (1_790_000_000_000L + ordinal * 180_000)
                : market + "-1h-" + (1_790_000_000_000L + ordinal * 180_000);
        db.tx(c -> {
            oracleRecords.insert(c, new OracleRecordRepository.NewRecord(market, asset, roundId, seq,
                    1_790_000_000L + ordinal, 8_561_200L + ordinal, "0x" + "%064x".formatted(seq), "live",
                    1_789_999_990L + ordinal, 1_790_000_000_000L));
            return null;
        });
        return seq;
    }

    // ------------------------------------------------------------------------------------------
    // /api/markets/registry.json
    // ------------------------------------------------------------------------------------------

    @Test
    void theRegistryListsEveryMarketEverListedByIndexClosedOnesIncludedInTheFieldsTheVerifierReads() throws Exception {
        Market eth = list("T_OREG1", MarketKind.PERP, Category.CRYPTO, "ETHT", 25, 1);
        Market odds = list("T_OREG2", MarketKind.ODDS, Category.POLITICS, "ODDT", 10, 4);
        registry.close(odds.id(), "test: closed", System.currentTimeMillis());

        Response response = given().get("/api/markets/registry.json");
        JsonNode entries = json(response);

        assertThat(response.contentType()).startsWith("application/json");
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
        assertThat(entries.isArray()).isTrue();
        List<Integer> indexes = new ArrayList<>();
        entries.forEach(e -> indexes.add(e.path("index").asInt()));
        assertThat(indexes).as("ordered by index, none twice").isSorted().doesNotHaveDuplicates();

        JsonNode btc = entries.get(0);
        assertThat(btc.path("index").asInt()).isZero();
        assertThat(btc.path("id").asText()).isEqualTo("BTC");
        assertThat(btc.path("kind").asText()).isEqualTo("PERP");
        assertThat(btc.path("symbol").asText()).isEqualTo("BTC");
        assertThat(btc.path("priceDecimals").asInt()).isEqualTo(2);
        assertThat(btc.path("source").asText()).isEqualTo("hyperliquid");
        assertThat(btc.path("sourceRef").asText()).isEqualTo("BTC");
        assertThat(btc.path("listedAtMs").asLong()).isZero();

        JsonNode ethEntry = entry(entries, eth.id());
        assertThat(ethEntry.path("index").asInt()).isEqualTo(eth.oracleIndex()).isPositive();
        assertThat(ethEntry.path("kind").asText()).isEqualTo("PERP");
        assertThat(ethEntry.path("symbol").asText()).isEqualTo("ETHT");
        assertThat(ethEntry.path("priceDecimals").asInt()).as("2 + lotExp").isEqualTo(3);
        assertThat(ethEntry.path("source").asText()).isEqualTo("synthetic");
        assertThat(ethEntry.path("sourceRef").asText()).isEqualTo("T_OREG1");
        assertThat(ethEntry.path("listedAtMs").asLong()).isEqualTo(eth.listedAtMs()).isPositive();

        JsonNode oddsEntry = entry(entries, odds.id());
        assertThat(oddsEntry.path("kind").asText()).isEqualTo("ODDS");
        assertThat(oddsEntry.path("priceDecimals").asInt()).isEqualTo(6);
        assertThat(oddsEntry.path("index").asInt()).as("a closed market keeps its index").isEqualTo(odds.oracleIndex());

        // exactly the documented fields, nothing else leaks
        Set<String> fields = new TreeSet<>();
        ethEntry.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("backup", "id", "index", "kind", "listedAtMs", "priceDecimals", "source",
                "sourceRef", "symbol");
        assertThat(ethEntry.path("backup").isNull()).as("a market with no backup exchange says null").isTrue();
    }

    private static JsonNode entry(JsonNode entries, String id) {
        for (JsonNode e : entries) {
            if (e.path("id").asText().equals(id)) {
                return e;
            }
        }
        throw new AssertionError("no registry entry for " + id + " in " + entries);
    }

    @Test
    void theRegistryShowsTheMainAndTheBackupExchangeOfAMarketThatHasOne() throws Exception {
        Market spot = listWithBackup("T_OREG3", "SPT", "kraken", "SPT/USD");

        JsonNode entry = entry(json(given().get("/api/markets/registry.json")), spot.id());

        assertThat(entry.path("source").asText()).as("the main exchange").isEqualTo("coinbase");
        assertThat(entry.path("sourceRef").asText()).isEqualTo("SPT-USD");
        assertThat(entry.path("backup").path("source").asText()).isEqualTo("kraken");
        assertThat(entry.path("backup").path("ref").asText()).as("what the backup calls the pair").isEqualTo("SPT/USD");
        Set<String> backupFields = new TreeSet<>();
        entry.path("backup").fieldNames().forEachRemaining(backupFields::add);
        assertThat(backupFields).containsExactly("ref", "source");
    }

    @Test
    void theRegistryNeedsNoSession() {
        given().get("/api/markets/registry.json").then().statusCode(200);
    }

    // ------------------------------------------------------------------------------------------
    // /api/oracle/records
    // ------------------------------------------------------------------------------------------

    @Test
    void anEmptyJournalIsA200WithNullFirstSeqAndNoRowsNotA404() throws Exception {
        Response response = given().get("/api/oracle/records");
        JsonNode body = json(response);

        assertThat(body.has("firstSeq")).isTrue();
        assertThat(body.get("firstSeq").isNull()).isTrue();
        assertThat(body.get("records").isArray()).isTrue();
        assertThat(body.get("records")).isEmpty();
    }

    @Test
    void theJournalAnswersInTheFieldNamesTheVerifierReadsAscendingFromFromSeqInclusive() throws Exception {
        Market eth = list("T_OREC1", MarketKind.PERP, Category.CRYPTO, "ETHJ", 25, 1);
        long btc = insert("BTC", 0, 1);
        long ethSeq = insert("T_OREC1", eth.oracleIndex(), 2);
        long btc2 = insert("BTC", 0, 3);
        oracleRecords.updateReconciledState(ethSeq, "confirmed", 4_737_632L, "0xcoin", 1_790_000_100_000L);

        Response response = given().get("/api/oracle/records");
        JsonNode body = json(response);

        assertThat(response.contentType()).startsWith("application/json");
        assertThat(response.header("Cache-Control")).isEqualTo("no-store");
        assertThat(body.path("firstSeq").asLong()).isEqualTo(btc);
        JsonNode records = body.get("records");
        assertThat(records).hasSize(3);
        assertThat(records.get(0).path("seq").asLong()).isEqualTo(btc);
        assertThat(records.get(1).path("seq").asLong()).isEqualTo(ethSeq);
        assertThat(records.get(2).path("seq").asLong()).isEqualTo(btc2);

        JsonNode first = records.get(0);
        assertThat(first.path("roundId").asText()).isEqualTo("1h-" + (1_790_000_000_000L + 180_000));
        assertThat(first.path("marketId").asText()).isEqualTo("BTC");
        assertThat(first.path("asset").asInt()).isZero();
        assertThat(first.path("ts").asLong()).isEqualTo(1_790_000_001L);
        assertThat(first.path("priceCents").asLong()).isEqualTo(8_561_201L);
        assertThat(first.path("resultsHash").asText()).isEqualTo("0x" + "%064x".formatted(btc));
        assertThat(first.path("state").asText()).isEqualTo("unknown");

        JsonNode second = records.get(1);
        assertThat(second.path("roundId").asText()).startsWith("T_OREC1-1h-");
        assertThat(second.path("marketId").asText()).isEqualTo("T_OREC1");
        assertThat(second.path("asset").asInt()).isEqualTo(eth.oracleIndex());
        assertThat(second.path("state").asText()).isEqualTo("confirmed");

        // JSON types the verifier checks: seq an integer, roundId a non-empty string, asset an integer
        assertThat(first.get("seq").isIntegralNumber()).isTrue();
        assertThat(first.get("roundId").isTextual()).isTrue();
        assertThat(first.get("asset").isIntegralNumber()).isTrue();
        Set<String> fields = new TreeSet<>();
        first.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("asset", "marketId", "priceCents", "resultsHash", "roundId", "seq", "state",
                "ts", "venue");
        assertThat(first.path("venue").isNull()).as("a round of a market with no backup exchange: no venue").isTrue();

        JsonNode fromSecond = json(given().queryParam("fromSeq", ethSeq).get("/api/oracle/records"));
        assertThat(fromSecond.get("records")).extracting(r -> r.path("seq").asLong()).containsExactly(ethSeq, btc2);
        assertThat(fromSecond.path("firstSeq").asLong()).as("firstSeq is the database's, not the page's")
                .isEqualTo(btc);
    }

    @Test
    void theJournalNamesTheExchangeARoundRanOnAndNullForARoundThatRanOnNone() throws Exception {
        Market spot = listWithBackup("T_OREC2", "SPJ", "kraken", "SPJ/USD");
        long onKraken = insert(spot.id(), spot.oracleIndex(), 1);
        long onCoinbase = insert(spot.id(), spot.oracleIndex(), 2);
        long onNone = insert(spot.id(), spot.oracleIndex(), 3);
        // the exchange is the round's: the result row says it, and the journal reads it from there
        for (long[] round : new long[][] {{1, 0}, {2, 1}}) {
            String venue = round[1] == 0 ? "kraken" : "coinbase";
            String roundId = spot.id() + "-1h-" + (1_790_000_000_000L + round[0] * 180_000);
            db.tx(c -> db.update(c, "INSERT INTO round_results (round_id, market, interval, window_start_ms, "
                    + "window_end_ms, status, created_at_ms, venue) VALUES (?, ?, 'HOURLY', ?, ?, 'SETTLED', ?, ?)",
                    roundId, spot.id(), 1_790_000_000_000L + round[0] * 180_000, 1_790_000_180_000L + round[0] * 180_000,
                    1_790_000_000_000L, venue));
        }

        JsonNode records = json(given().get("/api/oracle/records")).get("records");

        assertThat(records).hasSize(3);
        assertThat(records.get(0).path("seq").asLong()).isEqualTo(onKraken);
        assertThat(records.get(0).path("venue").asText()).isEqualTo("kraken");
        assertThat(records.get(1).path("seq").asLong()).isEqualTo(onCoinbase);
        assertThat(records.get(1).path("venue").asText()).isEqualTo("coinbase");
        assertThat(records.get(2).path("seq").asLong()).isEqualTo(onNone);
        assertThat(records.get(2).get("venue").isNull()).as("an explicit null: no exchange of its own").isTrue();
    }

    @Test
    void theLimitDefaultsTo200IsCappedAt1000AndPagingBySeqPlusOneCoversTheJournal() throws Exception {
        long firstSeq = serverMeta.reserveOracleSeqRange(1_005); // 1_005 consecutive seqs for rows inserted in bulk
        db.tx(c -> db.update(c, """
                INSERT INTO oracle_records (seq, round_id, ts, price_cents, results_hash, source, source_ts,
                    queued_at_ms, chainsvc_queued, market, asset_index)
                SELECT g, '1h-' || g, 1790000000, 8561200, '0x' || lpad(to_hex(g), 64, '0'), 'live', 1789999990,
                    1790000000000, FALSE, 'BTC', 0
                FROM generate_series(?::bigint, ?::bigint) g
                """, firstSeq, firstSeq + 1_004));

        assertThat(json(given().get("/api/oracle/records")).get("records")).as("default").hasSize(200);
        assertThat(json(given().queryParam("limit", 7).get("/api/oracle/records")).get("records")).hasSize(7);
        assertThat(json(given().queryParam("limit", 5000).get("/api/oracle/records")).get("records"))
                .as("capped at 1000").hasSize(1000);

        // what the verifier does: pages of 1000 by last seq + 1, until a page is short
        List<Long> seen = new ArrayList<>();
        long cursor = 0;
        while (true) {
            JsonNode page = json(given().queryParam("fromSeq", cursor).queryParam("limit", 1000)
                    .get("/api/oracle/records"));
            page.get("records").forEach(r -> seen.add(r.path("seq").asLong()));
            if (page.get("records").size() < 1000) {
                break;
            }
            cursor = page.get("records").get(999).path("seq").asLong() + 1;
        }
        assertThat(seen).hasSize(1_005).isSorted().doesNotHaveDuplicates();
        assertThat(seen.get(0)).isEqualTo(firstSeq);
    }

    @Test
    void aMalformedQueryIsA400InTheUsualEnvelopeNeverA404() throws Exception {
        for (String[] bad : new String[][] {{"fromSeq", "abc"}, {"fromSeq", "-1"}, {"fromSeq", "1.5"}, {"limit", "abc"},
                {"limit", "0"}, {"limit", "-5"}, {"limit", "99999999999999999999"}}) {
            Response response = given().queryParam(bad[0], bad[1]).get("/api/oracle/records");
            assertThat(response.statusCode()).as(bad[0] + "=" + bad[1] + ": " + response.asString()).isEqualTo(400);
            assertThat(response.jsonPath().getString("error.code")).isEqualTo("BAD_REQUEST");
        }
    }

    @Test
    void anEmptyQueryValueIsTheDefault() throws Exception {
        insert("BTC", 0, 1);

        JsonNode body = json(given().queryParam("fromSeq", "").queryParam("limit", "").get("/api/oracle/records"));

        assertThat(body.get("records")).hasSize(1);
    }

    @Test
    void theJournalNeedsNoSession() {
        given().get("/api/oracle/records").then().statusCode(200);
    }
}
