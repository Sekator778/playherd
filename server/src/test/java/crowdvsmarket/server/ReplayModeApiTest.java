package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.DbMarketRegistry;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.service.MarketViewService;
import crowdvsmarket.server.testsupport.BettingWindows;
import crowdvsmarket.server.testsupport.RpcClient;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The new RPCs and {@code serverInfo} in {@code mode=replay} (the module's default mode, no bots here):
 * BTC alone — {@code markets} and {@code crowdOverview} list it and nothing else even when the registry holds
 * more, {@code priceSeries} samples the replay series and answers every other market with {@code
 * MARKET_NOT_FOUND}, {@code serverInfo} says {@code priceSource = "replay"}.
 */
@QuarkusTest
@TestProfile(ReplayModeMarketsTest.ReplayNoBotsProfile.class)
class ReplayModeApiTest {

    @InjectMock
    ChainSvcClient chainSvcClient;

    @Inject
    DbMarketRegistry registry;
    @Inject
    RoundProgress roundProgress;
    @Inject
    MarketViewService marketView;

    private static void assertKeys(JsonNode node, String... keys) {
        Set<String> found = new TreeSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(found::add);
        assertThat(found).as("the fields of %s", node).isEqualTo(new TreeSet<>(Set.of(keys)));
    }

    private void aMarketTheReplayDoesNotPlay() {
        registry.list(new NewMarket("T_RA_OTHER", MarketKind.ODDS, Category.POLITICS, "OTH", "Left over from a live run",
                null, null, "synthetic", "T_RA_OTHER", null, 10, 4, null), System.currentTimeMillis());
    }

    @Test
    void serverInfoSaysReplay() throws Exception {
        JsonNode info = RpcClient.ok(null, "serverInfo");

        assertThat(info.path("mode").asText()).isEqualTo("replay");
        assertThat(info.path("priceSource").asText()).isEqualTo("replay");
        assertThat(info.path("markets").asInt()).as("BTC alone is played").isEqualTo(1);
        assertKeys(info.path("replay"), "sourceFromIso", "sourceToIso", "speedup");
        assertThat(info.path("replay").path("speedup").asDouble()).isEqualTo(20.0);
        assertKeys(info.path("roundDurationsSec"), "HOURLY", "DAILY"); // the Interval names, as contract.ts keys them
        assertThat(info.path("roundDurationsSec").path("HOURLY").asLong()).isEqualTo(180);
        assertThat(info.path("roundDurationsSec").path("DAILY").asLong()).isEqualTo(900);
        assertThat(info.path("testWallet").asBoolean()).isTrue();
        assertThat(info.path("playAssetId").asText()).matches("0x[0-9a-f]{64}");
        assertThat(info.path("oracle").has("firstSeq")).isTrue();
    }

    @Test
    void marketsListsBtcAloneWhateverTheRegistryHolds() throws Exception {
        aMarketTheReplayDoesNotPlay();
        marketView.invalidate();

        JsonNode answer = RpcClient.ok(null, "markets");

        assertThat(answer.path("markets")).hasSize(1);
        JsonNode btc = answer.path("markets").get(0);
        assertThat(btc.path("id").asText()).isEqualTo("BTC");
        assertThat(btc.path("kind").asText()).isEqualTo("PERP");
        assertThat(btc.path("category").asText()).isEqualTo("crypto");
        assertThat(btc.path("priceDecimals").asInt()).isEqualTo(2);
        assertThat(btc.path("maxLeverage").asInt()).isEqualTo(40);
        assertThat(btc.path("oracleIndex").asInt()).isZero();
        assertThat(btc.path("price").path("price").asText()).as("the replay's price, two decimals").matches("\\d+\\.\\d{2}");
        assertThat(btc.path("price").path("stale").asBoolean()).isFalse();
        assertThat(btc.path("series")).hasSize(2);
        assertThat(btc.path("series").get(0).path("durationSec").asLong()).isEqualTo(180);
        assertThat(btc.path("series").get(1).path("durationSec").asLong()).isEqualTo(900);
        assertThat(btc.path("series").get(0).path("bettingRoundId").asText()).startsWith("1h-");
        assertThat(btc.path("series").get(1).path("bettingRoundId").asText()).startsWith("1d-");
        assertThat(answer.path("categories")).hasSize(1);
        assertThat(answer.path("categories").get(0).path("id").asText()).isEqualTo("crypto");
        assertThat(answer.path("categories").get(0).path("marketCount").asInt()).isEqualTo(1);
    }

    @Test
    void priceSeriesIsTheReplaySampledOncePerSecondAndOtherMarketsAreNotFound() throws Exception {
        aMarketTheReplayDoesNotPlay();

        JsonNode series = RpcClient.ok(null, "priceSeries", "BTC");

        assertThat(series.path("marketId").asText()).isEqualTo("BTC");
        assertThat(series.path("priceDecimals").asInt()).isEqualTo(2);
        JsonNode ticks = series.path("ticks");
        assertThat(ticks.size()).as("15 minutes of seconds, thinned to at most 600").isEqualTo(600);
        List<Long> times = new ArrayList<>();
        for (JsonNode tick : ticks) {
            assertThat(tick.path("price").asText()).matches("\\d+\\.\\d{2}");
            times.add(tick.path("atMs").asLong());
        }
        assertThat(times).isSorted().doesNotHaveDuplicates();
        assertThat(times.get(times.size() - 1)).as("the newest tick is within a second of now")
                .isBetween(System.currentTimeMillis() - 5_000L, System.currentTimeMillis() + 1_000L);

        long since = times.get(times.size() - 1);
        JsonNode later = RpcClient.ok(null, "priceSeries", "BTC", since);
        for (JsonNode tick : later.path("ticks")) {
            assertThat(tick.path("atMs").asLong()).isGreaterThan(since);
        }

        Response other = RpcClient.rpc(null, "priceSeries", "T_RA_OTHER");
        assertThat(other.statusCode()).isEqualTo(404);
        assertThat(other.jsonPath().getString("error.code")).isEqualTo("MARKET_NOT_FOUND");
        Response unknown = RpcClient.rpc(null, "priceSeries", "NOPE");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.jsonPath().getString("error.code")).isEqualTo("MARKET_NOT_FOUND");
    }

    @Test
    void crowdOverviewHasBtcAlone() throws Exception {
        aMarketTheReplayDoesNotPlay();

        JsonNode overview = RpcClient.ok(null, "crowdOverview");

        assertThat(overview.path("markets")).hasSize(1);
        assertThat(overview.path("markets").get(0).path("marketId").asText()).isEqualTo("BTC");
        assertThat(overview.path("totalStartingCapital").asText()).isEqualTo("100000.00");
    }

    @Test
    void myPositionsListsAPendingBetOnBtc() throws Exception {
        Response anonymous = RpcClient.rpc(null, "myPositions");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.jsonPath().getString("error.code")).isEqualTo("NOT_SIGNED_IN");

        Session me = RpcClient.signIn(chainSvcClient, "replay");
        assertThat(RpcClient.ok(me, "myPositions")).isEmpty();

        RoundWindow betting = BettingWindows.fresh(roundProgress, Interval.DAILY);
        RpcClient.ok(me, "placeBet", Map.of("roundId", betting.roundId(), "direction", "UP", "leverage", 5,
                "stake", "200.000"));

        JsonNode positions = RpcClient.ok(me, "myPositions");
        assertThat(positions).hasSize(1);
        JsonNode position = positions.get(0);
        assertThat(position.path("marketId").asText()).isEqualTo("BTC");
        assertThat(position.path("symbol").asText()).isEqualTo("BTC");
        assertThat(position.path("roundId").asText()).isEqualTo(betting.roundId());
        assertThat(position.path("phase").asText()).isEqualTo("BETTING");
        assertThat(position.path("interval").asText()).isEqualTo("DAILY");
        assertThat(position.path("durationSec").asLong()).isEqualTo(900);
        assertThat(position.path("stake").asText()).isEqualTo("200.000");
        assertThat(position.path("untilMs").asLong()).isEqualTo(betting.startMs());
        assertThat(position.get("entryPrice").isNull()).isTrue();
        assertThat(new java.math.BigDecimal(RpcClient.ok(me, "myAccount").path("inPlay").asText()))
                .isEqualByComparingTo("200.000");
    }
}
