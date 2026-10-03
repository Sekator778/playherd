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
        assertThat(history).hasSize(19); // six candles: the first with its open, five without (R3)
        assertThat(server.requests().get(0).body()).isEqualTo("{\"type\":\"metaAndAssetCtxs\",\"dex\":\"xyz\"}");
        assertThat(server.requests().get(1).body()).isEqualTo("{\"type\":\"candleSnapshot\",\"req\":{\"coin\":\"xyz:TSLA\","
                + "\"interval\":\"1m\",\"startTime\":1790881680000,\"endTime\":1790883839998}}"); // 1 ms before the range end (R3)
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
        gamma.marketsByTokens(List.of("tokA", "tokB"), false, 20);
        gamma.marketsByTokens(List.of("tokA", "tokB"), true, 20);

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
                .containsEntry("closed", List.of("false")).containsEntry("limit", List.of("20")).doesNotContainKey("tag_id");
        // the same lookup for the markets that closed at the source: closed=true (2026-10-02: without it they are left out)
        assertThat(path(requests.get(3).pathAndQuery())).isEqualTo("/markets/keyset");
        assertThat(query(requests.get(3).pathAndQuery())).containsEntry("clob_token_ids", List.of("tokA", "tokB"))
                .containsEntry("closed", List.of("true")).containsEntry("limit", List.of("20"));
    }

    @Test
    void dataApiHistoryIsAGetWithSecondsAndSixtySecondBucketsAndNoCursorUnlessGiven() throws Exception {
        server.reply(r -> FakeHttpServer.Reply.json(Fixtures.json("polymarket/data_prices_history.json").toString()));
        PolymarketPriceSource source = new PolymarketPriceSource(clients.clob(), clients.data(),
                new PolymarketLiveState(), PricesConfig.defaults(), System::currentTimeMillis);

        List<PricePoint> points = source.history(TestMarkets.odds("PM1", "tok1"), 1_790_882_900_000L, 1_790_883_700_000L);

        // 8 rows (7 buckets and the terminal tick), the gaps between the buckets carried every 30 s (R1) — but not the
        // 311 s between the last bucket and the terminal tick: that is the materialisation lag, not a flat price (F6)
        assertThat(points).hasSize(14);
        FakeHttpServer.Request request = server.requests().get(0);
        assertThat(request.method()).isEqualTo("GET");
        assertThat(path(request.pathAndQuery())).isEqualTo("/v2/prices-history");
        assertThat(query(request.pathAndQuery())).containsEntry("token_id", List.of("tok1"))
                .containsEntry("start", List.of("1790882900")).containsEntry("end", List.of("1790883700"))
                .containsEntry("bucket_seconds", List.of("60")).doesNotContainKey("cursor");
    }

    @Test
    void krakenIsAskedWithGetsThePairsInTheQueryAndTheSecondsOfSince() throws Exception {
        server.reply(r -> FakeHttpServer.Reply.json("{\"error\":[],\"result\":{}}"));
        PriceHttpClients withKraken = new PriceHttpClients(PricesConfig.defaults().withEndpoints(server.baseUrl(), 3_000, 5_000),
                SpotPricesConfig.defaults().withKrakenEndpoints(server.baseUrl(), "ws://127.0.0.1:1/unused"));
        try {
            KrakenApi kraken = withKraken.kraken().get();

            kraken.ticker("BTC/USD,ETH/USD");
            kraken.assetPairs("DOGE/USD");
            kraken.ohlc("BTC/USD", 1, 1_791_027_599L);

            List<FakeHttpServer.Request> requests = server.requests();
            assertThat(requests).extracting(FakeHttpServer.Request::method).containsOnly("GET");
            assertThat(requests.get(0).accept()).contains("application/json");
            assertThat(path(requests.get(0).pathAndQuery())).isEqualTo("/0/public/Ticker");
            assertThat(query(requests.get(0).pathAndQuery())).as("the pairs as the stream names them, decoded")
                    .containsOnlyKeys("pair").containsEntry("pair", List.of("BTC/USD,ETH/USD"));
            assertThat(path(requests.get(1).pathAndQuery())).isEqualTo("/0/public/AssetPairs");
            assertThat(query(requests.get(1).pathAndQuery())).containsEntry("pair", List.of("DOGE/USD"));
            assertThat(path(requests.get(2).pathAndQuery())).isEqualTo("/0/public/OHLC");
            assertThat(query(requests.get(2).pathAndQuery())).containsEntry("pair", List.of("BTC/USD"))
                    .containsEntry("interval", List.of("1")).containsEntry("since", List.of("1791027599"));
        } finally {
            withKraken.close();
        }
    }

    @Test
    void coinbaseIsAskedWithGetsTheProductInThePathAndTheMinuteGranularityInTheQuery() throws Exception {
        server.reply(r -> FakeHttpServer.Reply.json("[]"));
        PriceHttpClients withCoinbase = new PriceHttpClients(PricesConfig.defaults().withEndpoints(server.baseUrl(), 3_000, 5_000),
                SpotPricesConfig.defaults().withEndpoints(server.baseUrl(), "ws://127.0.0.1:1/unused", "ws://127.0.0.1:1/unused"));
        try {
            CoinbaseExchangeApi coinbase = withCoinbase.coinbase().get();

            coinbase.ticker("BTC-USD");
            coinbase.candles("HYPE-USD", 60, "2026-10-03T12:00:00Z", "2026-10-03T12:04:00Z");

            List<FakeHttpServer.Request> requests = server.requests();
            assertThat(path(requests.get(0).pathAndQuery())).isEqualTo("/products/BTC-USD/ticker");
            assertThat(path(requests.get(1).pathAndQuery())).isEqualTo("/products/HYPE-USD/candles");
            assertThat(query(requests.get(1).pathAndQuery())).containsEntry("granularity", List.of("60"))
                    .containsEntry("start", List.of("2026-10-03T12:00:00Z"))
                    .containsEntry("end", List.of("2026-10-03T12:04:00Z"));
        } finally {
            withCoinbase.close();
        }
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
