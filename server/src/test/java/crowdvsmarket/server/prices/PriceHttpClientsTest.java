package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The four REST clients over a real HTTP stack — a server on the loopback interface, never the
 * network: the path, the method, the headers, the query and the body each request goes out with,
 * the parsing of the answers, the behaviour on an error status and the 5 s read timeout. A
 * {@code @QuarkusTest} because Quarkus generates the client implementations at build time.
 */
@QuarkusTest
class PriceHttpClientsTest {

    private FakeHttpServer server;
    private PriceHttpClients clients;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeHttpServer();
        clients = new PriceHttpClients(PricesConfig.defaults().withEndpoints(server.baseUrl(), 3_000, 5_000));
    }

    @AfterEach
    void tearDown() {
        clients.close();
        server.close();
    }

    private static Map<String, List<String>> query(String pathAndQuery) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        int q = pathAndQuery.indexOf('?');
        if (q < 0) {
            return out;
        }
        for (String pair : pathAndQuery.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
        return out;
    }

    private static String path(String pathAndQuery) {
        int q = pathAndQuery.indexOf('?');
        return q < 0 ? pathAndQuery : pathAndQuery.substring(0, q);
    }

    @Test
    void hyperliquidInfoPostsTheRequestAsJsonToInfoAndTheSourceReadsTheAnswer() throws Exception {
        server.reply(r -> FakeHttpServer.Reply.json(Fixtures.json("hyperliquid/meta_and_asset_ctxs_main.json").toString()));
        HyperliquidPriceSource source = new HyperliquidPriceSource(clients.hyperliquid(), PricesConfig.defaults(),
                () -> 1_790_883_900_000L);

        Map<String, PriceSource.Quote> quotes = source.poll(source.feeds().get(0), List.of(TestMarkets.btc()));

        assertThat(quotes.get("BTC").raw()).isEqualByComparingTo("84726.0");
        FakeHttpServer.Request request = server.requests().get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(path(request.pathAndQuery())).isEqualTo("/info");
        assertThat(request.contentType()).startsWith("application/json");
        assertThat(request.accept()).contains("application/json");
        assertThat(request.body()).isEqualTo("{\"type\":\"metaAndAssetCtxs\"}");
    }

    @Test
    void theXyzPollAndTheCandleSnapshotHaveTheirOwnBodies() throws Exception {
        server.reply(r -> r.body().contains("candleSnapshot")
                ? FakeHttpServer.Reply.json(Fixtures.json("hyperliquid/candle_snapshot_xyz_tsla_1m.json").toString())
                : FakeHttpServer.Reply.json(Fixtures.json("hyperliquid/meta_and_asset_ctxs_xyz.json").toString()));
        HyperliquidPriceSource source = new HyperliquidPriceSource(clients.hyperliquid(), PricesConfig.defaults(),
                () -> 1_790_883_900_000L);
        Market tsla = TestMarkets.perp("xyz_TSLA", "xyz:TSLA", Category.STOCKS, 20, 3, 6);

        Map<String, PriceSource.Quote> quotes = source.poll(source.feeds().get(1), List.of(tsla));
        List<PricePoint> history = source.history(tsla, 1_790_883_480_000L, 1_790_883_839_999L);

        assertThat(quotes.get("xyz_TSLA").raw()).isEqualByComparingTo("357.74");
        assertThat(history).hasSize(24);
        assertThat(server.requests().get(0).body()).isEqualTo("{\"type\":\"metaAndAssetCtxs\",\"dex\":\"xyz\"}");
        assertThat(server.requests().get(1).body()).isEqualTo("{\"type\":\"candleSnapshot\",\"req\":{\"coin\":\"xyz:TSLA\","
                + "\"interval\":\"1m\",\"startTime\":1790881680000,\"endTime\":1790883839999}}");
    }

    @Test
    void clobMidpointsAndPricesPostArraysAndReadStringValues() {
        server.reply(r -> path(r.pathAndQuery()).equals("/midpoints")
                ? FakeHttpServer.Reply.json(Fixtures.json("polymarket/clob_midpoints.json").toString())
                : FakeHttpServer.Reply.json(Fixtures.json("polymarket/clob_prices.json").toString()));
        PolymarketClobApi clob = clients.clob().get();
        com.fasterxml.jackson.databind.node.ArrayNode midBody = JsonNodeFactory.instance.arrayNode();
        midBody.addObject().put("token_id", "t1");
        com.fasterxml.jackson.databind.node.ArrayNode priceBody = JsonNodeFactory.instance.arrayNode();
        priceBody.addObject().put("token_id", "t1").put("side", "BUY");
        priceBody.addObject().put("token_id", "t1").put("side", "SELL");

        JsonNode mids = clob.midpoints(midBody);
        JsonNode prices = clob.prices(priceBody);

        assertThat(mids.get("55115078421062885512539156303747803058407616201213034911037320915726138659123").asText())
                .isEqualTo("0.145");
        assertThat(prices.get("55115078421062885512539156303747803058407616201213034911037320915726138659123")
                .get("BUY").asText()).isEqualTo("0.14");
        assertThat(server.requests()).extracting(FakeHttpServer.Request::method).containsOnly("POST");
        assertThat(server.requests()).extracting(r -> path(r.pathAndQuery())).containsExactly("/midpoints", "/prices");
        assertThat(server.requests().get(0).body()).isEqualTo("[{\"token_id\":\"t1\"}]");
        assertThat(server.requests().get(1).body())
                .isEqualTo("[{\"token_id\":\"t1\",\"side\":\"BUY\"},{\"token_id\":\"t1\",\"side\":\"SELL\"}]");
        assertThat(server.requests().get(0).contentType()).startsWith("application/json");
    }

    @Test
    void gammaListingsAreGetsWithTheDocumentedQuery() {
        server.reply(r -> FakeHttpServer.Reply.json("{\"markets\":[],\"events\":[],\"next_cursor\":null}"));
        PolymarketGammaApi gamma = clients.gamma().get();

        gamma.markets("2", false, "volume24hr", false, 50);
        gamma.events("1", true, false, "volume24hr", false, 20);
        gamma.marketsByTokens(List.of("tokA", "tokB"), 20);

        List<FakeHttpServer.Request> requests = server.requests();
        assertThat(requests).extracting(FakeHttpServer.Request::method).containsOnly("GET");
        assertThat(requests.get(0).accept()).contains("application/json");

        assertThat(path(requests.get(0).pathAndQuery())).isEqualTo("/markets/keyset");
        assertThat(query(requests.get(0).pathAndQuery())).containsEntry("tag_id", List.of("2"))
                .containsEntry("closed", List.of("false")).containsEntry("order", List.of("volume24hr"))
                .containsEntry("ascending", List.of("false")).containsEntry("limit", List.of("50"));

        assertThat(path(requests.get(1).pathAndQuery())).isEqualTo("/events/keyset");
        assertThat(query(requests.get(1).pathAndQuery())).containsEntry("tag_id", List.of("1"))
                .containsEntry("live", List.of("true")).containsEntry("closed", List.of("false"))
                .containsEntry("order", List.of("volume24hr")).containsEntry("ascending", List.of("false"))
                .containsEntry("limit", List.of("20"));

        assertThat(path(requests.get(2).pathAndQuery())).isEqualTo("/markets/keyset");
        assertThat(query(requests.get(2).pathAndQuery())).containsEntry("clob_token_ids", List.of("tokA", "tokB"))
                .containsEntry("limit", List.of("20")).doesNotContainKey("tag_id");
    }

    @Test
    void dataApiHistoryIsAGetWithSecondsAndSixtySecondBucketsAndNoCursorUnlessGiven() throws Exception {
        server.reply(r -> FakeHttpServer.Reply.json(Fixtures.json("polymarket/data_prices_history.json").toString()));
        PolymarketPriceSource source = new PolymarketPriceSource(clients.clob(), clients.data(),
                new PolymarketLiveState(), PricesConfig.defaults(), System::currentTimeMillis);

        List<PricePoint> points = source.history(TestMarkets.odds("PM1", "tok1"), 1_790_882_900_000L, 1_790_883_700_000L);

        assertThat(points).hasSize(8);
        FakeHttpServer.Request request = server.requests().get(0);
        assertThat(request.method()).isEqualTo("GET");
        assertThat(path(request.pathAndQuery())).isEqualTo("/v2/prices-history");
        assertThat(query(request.pathAndQuery())).containsEntry("token_id", List.of("tok1"))
                .containsEntry("start", List.of("1790882900")).containsEntry("end", List.of("1790883700"))
                .containsEntry("bucket_seconds", List.of("60")).doesNotContainKey("cursor");
    }

    @Test
    void anErrorStatusIsAnExceptionTheCallersTreatAsAFailedPoll() {
        server.reply(r -> new FakeHttpServer.Reply(503, "{\"error\":\"busy\"}", 0));

        assertThatThrownBy(() -> clients.hyperliquid().get().info(JsonNodeFactory.instance.objectNode().put("type", "meta")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> clients.gamma().get().markets("2", false, "volume24hr", false, 50))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aServerThatDoesNotAnswerIsGivenUpOnAfterTheReadTimeout() {
        server.reply(r -> FakeHttpServer.Reply.slow("{}", 5_000));
        PriceHttpClients quick = new PriceHttpClients(PricesConfig.defaults().withEndpoints(server.baseUrl(), 3_000, 400));
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> quick.hyperliquid().get().info(JsonNodeFactory.instance.objectNode().put("type", "meta")))
                    .isInstanceOf(RuntimeException.class);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(elapsedMs).as("gave up after the read timeout, not after the 5 s reply").isLessThan(3_000);
        } finally {
            quick.close();
        }
    }

    @Test
    void theDefaultTimeoutsAreThreeSecondsToConnectAndFiveToRead() {
        PricesConfig defaults = PricesConfig.defaults();

        assertThat(defaults.connectTimeoutMs()).isEqualTo(3_000);
        assertThat(defaults.readTimeoutMs()).isEqualTo(5_000);
    }
}
