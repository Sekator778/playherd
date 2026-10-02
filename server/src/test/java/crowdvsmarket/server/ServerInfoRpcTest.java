package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.LiveRpcTestBase;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * {@code serverInfo} over HTTP in live mode (docs/research/markets_architecture.md §8 additions of
 * 2026-10-02): the contract.ts fields — {@code priceSource}, {@code markets}, {@code oracle.firstSeq}, {@code
 * playAssetId}, {@code testWallet} — in the shape contract.ts declares, with the oracle's first {@code seq}
 * read from the database. The values behind each field are tested on plain objects in {@code
 * ServerInfoServiceTest}; replay mode's are in {@code ReplayModeApiTest}.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class ServerInfoRpcTest extends LiveRpcTestBase {

    private static void assertKeys(JsonNode node, String... keys) {
        Set<String> found = new TreeSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(found::add);
        assertThat(found).as("the fields of %s", node).isEqualTo(new TreeSet<>(Set.of(keys)));
    }

    @Test
    void serverInfoHasTheFieldsOfContractTs() throws Exception {
        JsonNode info = ok(null, "serverInfo");

        assertKeys(info, "mode", "priceSource", "markets", "replay", "roundDurationsSec", "bots", "oracle",
                "serverTimeMs", "playAssetId", "testWallet", "engineReady");
        assertKeys(info.path("oracle"), "network", "launcherId", "lastConfirmedSeq", "lastConfirmedHeight",
                "pendingRecords", "operatorBalanceMojos", "firstSeq");
        // the keys are the Interval names contract.ts declares ({ HOURLY, DAILY }) — lower-case ones printed "NaN SEC"
        // on the Status page (parity review, finding 1)
        assertKeys(info.path("roundDurationsSec"), "HOURLY", "DAILY");
        assertThat(info.path("mode").asText()).isEqualTo("live");
        assertThat(info.path("priceSource").asText()).as("herd.prices.source defaults to live").isEqualTo("live");
        assertThat(info.get("replay").isNull()).as("replay is for mode=replay only").isTrue();
        assertThat(info.path("roundDurationsSec").path("HOURLY").asLong()).isEqualTo(60);
        assertThat(info.path("roundDurationsSec").path("DAILY").asLong()).isEqualTo(120);
        assertThat(info.path("bots").asInt()).isZero();
        assertThat(info.path("playAssetId").asText())
                .isEqualTo("0x4b88ce9712e97e73ad3759541fc23d38704e31d27d6586c28be14ee716080b70");
        assertThat(info.path("testWallet").asBoolean()).isTrue();
        assertThat(info.path("engineReady").asBoolean()).as("a server that is up and has rebuilt says so").isTrue();
        assertThat(info.path("oracle").path("network").asText()).isEqualTo("testnet11");
    }

    @Test
    void marketsCountsTheActiveMarkets() throws Exception {
        Market market = list("T_SI_COUNT", MarketKind.PERP, Category.CRYPTO, "SIC", 20, 0);
        int active = registry.active().size();
        assertThat(ok(null, "serverInfo").path("markets").asInt()).isEqualTo(active);

        registry.close(market.id(), "gone", System.currentTimeMillis());

        assertThat(ok(null, "serverInfo").path("markets").asInt()).isEqualTo(active - 1);
    }

    @Test
    void theFirstSeqIsTheLowestSeqOfTheOracleLogAndNullWhileItIsEmpty() throws Exception {
        db.tx(c -> db.update(c, "DELETE FROM oracle_records"));
        assertThat(ok(null, "serverInfo").path("oracle").get("firstSeq").isNull()).isTrue();

        Market market = list("T_SI_SEQ", MarketKind.PERP, Category.CRYPTO, "SIQ", 20, 0);
        prices.setPrice(market, "100");
        prices.setPrice(registry.require("BTC"), "84000");
        // a settled round of each series enqueues one oracle record (Р-40)
        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        scheduler.tick(running.endMs());
        long lowest = db.read(c -> db.queryOne(c, "SELECT MIN(seq) AS m FROM oracle_records",
                rs -> (Long) rs.getObject("m")));
        long records = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM oracle_records", rs -> rs.getLong("n")));
        assertThat(records).as("the tick settled at least one round").isPositive();

        JsonNode oracle = ok(null, "serverInfo").path("oracle");
        assertThat(oracle.path("firstSeq").asLong()).isEqualTo(lowest);
        // later records do not move it
        scheduler.tick(running.endMs() + 120_000L);
        assertThat(ok(null, "serverInfo").path("oracle").path("firstSeq").asLong()).isEqualTo(lowest);
    }

    @Test
    void serverInfoAsksForNoSession() throws Exception {
        Session me = signIn("info");

        assertThat(ok(me, "serverInfo").path("mode").asText()).isEqualTo("live");
        assertThat(ok(null, "serverInfo").path("mode").asText()).isEqualTo("live");
    }
}
