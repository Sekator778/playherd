package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import crowdvsmarket.server.markets.Market;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Kraken source against messages and answers of 2026-10-03 (samples of the live API, fixtures/kraken/): the mid of a
 * {@code ticker} message by symbol, a poll answered from memory while the stream is heard from — a quote that has not
 * changed is still the quote under {@code bbo} — and the REST ticker for the pairs whose stream has gone quiet, the
 * subscription that follows the markets, the pair names (the symbol {@code BTC/USD} is what the REST API answers under;
 * were it ever another, AssetPairs says what the pair is called), and the 1-minute candles as history (committed ones
 * only, the filler of a minute without trades, the pages). A scripted WebSocket and a scripted REST client: nothing here
 * touches the network.
 */
class KrakenPriceSourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 2026-10-03T12:07:06Z: when the sampled stream was subscribed. */
    private static final long T0 = 1_791_029_226_000L;
    /** 2026-10-03T12:17:51Z: when the REST samples were taken; their candle of 12:17 is the one in progress. */
    private static final long SAMPLED = 1_791_029_871_000L;

    private final AtomicLong now = new AtomicLong(T0);
    private final FakeApis.Kraken api = new FakeApis.Kraken();
    private final FakeWebSocketTransport transport = new FakeWebSocketTransport();
    private final Market btc = shadow("BTC", 0, 1);
    private final Market eth = shadow("ETH", 0, 2);
    private final Market sol = shadow("SOL", 1, 3);
    private final Market xrp = shadow("XRP", 3, 4);
    private final Market doge = shadow("DOGE", 5, 5);
    private KrakenPriceSource source;
    private PriceSource.Feed feed;

    /** The Kraken instrument of a Coinbase market that has Kraken as its backup: {@code spot_BTC~kraken}, source kraken, ref {@code BTC/USD}. */
    private static Market shadow(String symbol, int lotExp, int oracleIndex) {
        return Instruments.shadowOf(TestMarkets.coinbaseWithKraken(symbol, lotExp, oracleIndex)).orElseThrow();
    }

    @BeforeEach
    void setUp() {
        source = PipelineTestSupport.kraken(api, transport, now::get);
        feed = source.feeds().get(0);
        api.ticker.handler = pairs -> Fixtures.json("kraken/rest_ticker_symbols.json");
        api.assetPairs.handler = this::assetPairsOf;
    }

    private JsonNode assetPairsOf(String requested) {
        boolean bySymbol = requested.contains("/");
        JsonNode fixture = Fixtures.json(bySymbol ? "kraken/rest_asset_pairs_symbols.json" : "kraken/rest_asset_pairs_altnames.json");
        ObjectNode result = JSON.createObjectNode();
        for (Map.Entry<String, JsonNode> pair : fixture.get("result").properties()) {
            if (pair.getKey().equals(requested) || pair.getValue().path("altname").asText().equals(requested)) {
                result.set(pair.getKey(), pair.getValue()); // a single-pair request answers a single pair
            }
        }
        ObjectNode answer = JSON.createObjectNode();
        answer.set("error", JSON.createArrayNode());
        answer.set("result", result);
        return answer;
    }

    private static String ws(String fixture) {
        return Fixtures.json("kraken/" + fixture).toString();
    }

    private void deliver(String fixture) {
        transport.last().deliver(ws(fixture));
    }

    private void connectTheStream() {
        PipelineTestSupport.stepStream(source, now.get());
        assertThat(transport.attempts).isPositive();
    }

    private static BigDecimal midOf(String fixture) {
        JsonNode d = Fixtures.json("kraken/" + fixture).get("data").get(0);
        return d.get("bid").decimalValue().add(d.get("ask").decimalValue()).divide(BigDecimal.valueOf(2));
    }

    /** Everything a healthy stream says in its first moments, for BTC. */
    private void subscribedAndSnapshotted() {
        deliver("ws_status.json");
        deliver("ws_subscribe_ack.json");
        deliver("ws_ticker_snapshot_btc.json");
        deliver("ws_heartbeat.json");
    }

    // ---------------------------------------------------------------------- the feed

    @Test
    void oneFeedForTheKrakenMarketsWithTheLimitsOfTheConfiguration() {
        assertThat(source.name()).isEqualTo("kraken");
        assertThat(source.feeds()).hasSize(1);
        assertThat(feed.id()).isEqualTo("kraken");
        assertThat(feed.staleMs()).isEqualTo(15_000L);
        assertThat(feed.maxGapMs()).isEqualTo(30_000L);
        assertThat(feed.maxBasis()).as("this feed's own tolerance: 15 bp").isEqualTo(0.0015);
        assertThat(feed.handles().test(btc)).isTrue();
        assertThat(feed.handles().test(TestMarkets.coinbase("BTC", 0, 1))).as("the Coinbase market").isFalse();
        assertThat(feed.handles().test(TestMarkets.hlSpot("HYPE", "@107", 2, 9))).isFalse();
        assertThat(feed.handles().test(TestMarkets.perp("ETH", "ETH", "crypto", 25, 0, 1))).isFalse();
        assertThat(source.historyKind()).isEqualTo(PriceSource.HistoryKind.TRADE_PRICES);
        assertThat(source.historyWeight(86_400_000L)).as("not paced by the seeder beyond its second between seeds").isZero();
    }

    @Test
    void theLimitsAreTheConfiguredOnes() {
        SpotPricesConfig config = SpotPricesConfig.defaults()
                .withKraken(new SpotPricesConfig.Limits(20_000, 45_000, 0.001, 3_000, 7_000));
        KrakenPriceSource custom = new KrakenPriceSource(() -> api, config, transport, now::get, false);

        PriceSource.Feed f = custom.feeds().get(0);

        assertThat(f.staleMs()).isEqualTo(20_000L);
        assertThat(f.maxGapMs()).isEqualTo(45_000L);
        assertThat(f.maxBasis()).isEqualTo(0.001);
    }

    // ---------------------------------------------------------------------- the stream

    @Test
    void theSubscriptionAsksForTheBboTriggerForEveryWantedSymbolAtOnce() throws Exception {
        source.poll(feed, List.of(btc, doge, eth)); // the stream is wanted from here on
        connectTheStream();

        assertThat(transport.last().uri).isEqualTo(URI.create("wss://ws.kraken.com/v2"));
        assertThat(transport.last().sent).containsExactly("{\"method\":\"subscribe\",\"params\":{\"channel\":\"ticker\","
                + "\"symbol\":[\"BTC/USD\",\"DOGE/USD\",\"ETH/USD\"],\"event_trigger\":\"bbo\"}}");
    }

    @Test
    void aPollIsAnsweredFromTheNewestQuoteOfTheStreamWithNoRequestAtAll() throws Exception {
        // the stream is not up yet: the first poll reads the REST ticker (ask 84703.0, bid 84702.9) and asks for the stream
        Map<String, PriceSource.Quote> first = source.poll(feed, List.of(btc));
        assertThat(first.get("spot_BTC~kraken").raw()).isEqualByComparingTo("84702.95");
        assertThat(api.ticker.requests).containsExactly("BTC/USD");

        connectTheStream();
        subscribedAndSnapshotted();
        deliver("ws_ticker_update_btc.json"); // bid 84672.9, ask 84673.0
        now.addAndGet(1_000);
        deliver("ws_heartbeat.json");

        Map<String, PriceSource.Quote> second = source.poll(feed, List.of(btc));

        assertThat(second.get("spot_BTC~kraken").raw()).as("(bid + ask) / 2").isEqualByComparingTo("84672.95");
        assertThat(api.ticker.requests).as("no further request").hasSize(1);
        // last 84673.0 and change -1748.5 in the same message: 24 hours ago it was 86421.5
        assertThat(second.get("spot_BTC~kraken").change24h()).isCloseTo((84672.95 - 86421.5) / 86421.5,
                org.assertj.core.data.Offset.offset(1e-7));
    }

    @Test
    void everySymbolIsPricedByItsOwnMessage() throws Exception {
        List<Market> markets = List.of(btc, eth, sol, xrp, doge);
        source.poll(feed, markets);
        api.ticker.requests.clear();
        connectTheStream();
        deliver("ws_heartbeat.json");

        for (String symbol : List.of("btc", "eth", "sol", "xrp", "doge")) {
            deliver("ws_ticker_update_" + symbol + ".json");
        }
        now.addAndGet(500);
        deliver("ws_heartbeat.json");
        Map<String, PriceSource.Quote> quotes = source.poll(feed, markets);

        assertThat(quotes).containsOnlyKeys("spot_BTC~kraken", "spot_ETH~kraken", "spot_SOL~kraken", "spot_XRP~kraken",
                "spot_DOGE~kraken");
        assertThat(quotes.get("spot_BTC~kraken").raw()).isEqualByComparingTo(midOf("ws_ticker_update_btc.json"));
        assertThat(quotes.get("spot_ETH~kraken").raw()).isEqualByComparingTo("2684.285");
        assertThat(quotes.get("spot_SOL~kraken").raw()).isEqualByComparingTo("119.435");
        assertThat(quotes.get("spot_XRP~kraken").raw()).isEqualByComparingTo("1.48805");
        assertThat(quotes.get("spot_DOGE~kraken").raw()).isEqualByComparingTo("0.09287935");
        assertThat(api.ticker.requests).isEmpty();
    }

    @Test
    void aQuoteThatHasNotChangedIsStillTheQuoteForAsLongAsTheStreamIsHeardFrom() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();
        subscribedAndSnapshotted(); // the quote is from T0
        api.ticker.requests.clear();

        // forty seconds with nothing but heartbeats: the book did not move, and a bbo stream says so by saying nothing
        for (int second = 1; second <= 40; second++) {
            now.set(T0 + second * 1_000L);
            deliver("ws_heartbeat.json");
        }
        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(btc));

        assertThat(quotes.get("spot_BTC~kraken").raw()).isEqualByComparingTo(midOf("ws_ticker_snapshot_btc.json"));
        assertThat(api.ticker.requests).as("the connection lives: the quote of 40 s ago is current").isEmpty();
    }

    @Test
    void aQuoteOlderThanAMinuteIsConfirmedOverRestEvenOnALivingConnection() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();
        subscribedAndSnapshotted();
        api.ticker.requests.clear();

        now.set(T0 + KrakenPriceSource.QUOTE_MAX_AGE_MS); // exactly a minute
        deliver("ws_heartbeat.json");
        source.poll(feed, List.of(btc));
        assertThat(api.ticker.requests).isEmpty();

        now.set(T0 + KrakenPriceSource.QUOTE_MAX_AGE_MS + 1);
        deliver("ws_heartbeat.json");
        Map<String, PriceSource.Quote> past = source.poll(feed, List.of(btc));

        assertThat(api.ticker.requests).as("a subscription silently dropped would look the same: asked").containsExactly("BTC/USD");
        assertThat(past.get("spot_BTC~kraken").raw()).isEqualByComparingTo("84702.95");
    }

    @Test
    void aStreamThatHasNotBeenHeardFromForFiveSecondsIsReadOverRestForEveryPairAtOnce() throws Exception {
        List<Market> markets = List.of(btc, doge);
        source.poll(feed, markets);
        connectTheStream();
        deliver("ws_ticker_snapshot_btc.json");
        deliver("ws_ticker_snapshot_doge.json");
        api.ticker.requests.clear();

        now.set(T0 + 5_000); // the last message was at T0: exactly the limit
        assertThat(source.poll(feed, markets)).hasSize(2);
        assertThat(api.ticker.requests).isEmpty();

        now.set(T0 + 5_001);
        Map<String, PriceSource.Quote> past = source.poll(feed, markets);

        assertThat(api.ticker.requests).as("one call for both pairs").containsExactly("BTC/USD,DOGE/USD");
        assertThat(past.get("spot_BTC~kraken").raw()).isEqualByComparingTo("84702.95");
        assertThat(past.get("spot_DOGE~kraken").raw()).as("(0.0928109 + 0.0928183) / 2").isEqualByComparingTo("0.0928146");
    }

    @Test
    void aStreamThatCannotBeHadMeansOneRestReadPerPollAndNoFailure() throws Exception {
        transport.connectFailure = new IOException("connection refused");
        List<Market> markets = List.of(btc, doge);

        Map<String, PriceSource.Quote> first = source.poll(feed, markets);
        connectTheStream(); // the supervisor tries, in vain
        now.addAndGet(2_000);
        Map<String, PriceSource.Quote> second = source.poll(feed, markets);

        assertThat(first).containsOnlyKeys("spot_BTC~kraken", "spot_DOGE~kraken");
        assertThat(second).containsOnlyKeys("spot_BTC~kraken", "spot_DOGE~kraken");
        assertThat(api.ticker.requests).containsExactly("BTC/USD,DOGE/USD", "BTC/USD,DOGE/USD");
        assertThat(transport.attempts).isEqualTo(1);
    }

    @Test
    void aSilentStreamIsDroppedAndTheRestReadTakesOver() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();
        deliver("ws_ticker_snapshot_btc.json");
        api.ticker.requests.clear();

        now.set(T0 + 20_000); // 20 s of silence: past the 10 s this stream is allowed
        PipelineTestSupport.stepStream(source, now.get());
        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(btc));

        assertThat(transport.last().aborted).isTrue();
        assertThat(quotes.get("spot_BTC~kraken").raw()).isEqualByComparingTo("84702.95");
        assertThat(api.ticker.requests).containsExactly("BTC/USD");
    }

    @Test
    void theQuotesOfAnEarlierConnectionAreNoQuotesOfTheNextUntilItsSnapshotComes() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();
        subscribedAndSnapshotted();
        FakeWebSocketTransport.Link first = transport.last();
        first.serverCloses(1006, "");
        now.addAndGet(100);
        PipelineTestSupport.stepStream(source, now.get()); // the loss is seen
        now.addAndGet(1_100);
        PipelineTestSupport.stepStream(source, now.get()); // and the next attempt is made
        assertThat(transport.links).hasSize(2);
        deliver("ws_status.json");
        deliver("ws_heartbeat.json");
        api.ticker.requests.clear();

        Map<String, PriceSource.Quote> before = source.poll(feed, List.of(btc));

        assertThat(api.ticker.requests).as("alive, but no snapshot of this connection yet: the old quote is not used")
                .containsExactly("BTC/USD");
        assertThat(before.get("spot_BTC~kraken").raw()).isEqualByComparingTo("84702.95");

        deliver("ws_ticker_snapshot_eth.json"); // another pair's snapshot is not BTC's
        api.ticker.requests.clear();
        source.poll(feed, List.of(btc));
        assertThat(api.ticker.requests).containsExactly("BTC/USD");

        deliver("ws_ticker_update_btc.json");
        api.ticker.requests.clear();
        Map<String, PriceSource.Quote> after = source.poll(feed, List.of(btc));

        assertThat(api.ticker.requests).isEmpty();
        assertThat(after.get("spot_BTC~kraken").raw()).isEqualByComparingTo(midOf("ws_ticker_update_btc.json"));
    }

    @Test
    void aRestReadThatFailsCostsThatMarketItsTickOnly() throws Exception {
        source.poll(feed, List.of(btc, doge));
        connectTheStream();
        deliver("ws_ticker_update_btc.json");
        deliver("ws_heartbeat.json");
        api.ticker.handler = pairs -> {
            throw new IllegalStateException("HTTP 503");
        };

        now.addAndGet(100);
        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(btc, doge)); // DOGE has no quote and its read fails

        assertThat(quotes).containsOnlyKeys("spot_BTC~kraken");
    }

    @Test
    void aPollFailsOnlyWhenNoMarketGotAPrice() {
        api.ticker.handler = pairs -> {
            throw new IllegalStateException("HTTP 503");
        };

        assertThatThrownBy(() -> source.poll(feed, List.of(btc, doge))).isInstanceOf(IOException.class)
                .hasMessageContaining("no Kraken price for any market");
    }

    @Test
    void anAnswerWithAnErrorIsAFailureAndAWarningIsNot() throws Exception {
        api.ticker.handler = pairs -> Fixtures.parse("{\"error\":[\"EGeneral:Too many requests\"]}");
        assertThatThrownBy(() -> source.poll(feed, List.of(btc))).isInstanceOf(IOException.class)
                .hasMessageContaining("Too many requests");

        api.ticker.handler = pairs -> {
            ObjectNode answer = Fixtures.json("kraken/rest_ticker_symbols.json").deepCopy();
            answer.set("error", JSON.createArrayNode().add("WGeneral:Something to know"));
            return answer;
        };
        assertThat(source.poll(feed, List.of(btc))).containsOnlyKeys("spot_BTC~kraken");
    }

    @Test
    void aBookThatIsNoBookIsNotAQuote() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();
        deliver("ws_ticker_snapshot_btc.json");
        BigDecimal good = midOf("ws_ticker_snapshot_btc.json");

        // crossed, a missing side, zero, text: none replaces the good quote
        String base = ws("ws_ticker_update_btc.json");
        for (String[] sides : new String[][] {{"84740.0", "84739.0"}, {"0", "84739.0"}, {"84739.0", "-1"}, {"\"x\"", "84740.0"}}) {
            transport.last().deliver(base.replace("\"bid\":84672.9", "\"bid\":" + sides[0])
                    .replace("\"ask\":84673.0", "\"ask\":" + sides[1]).replace("\"ask\":84673,", "\"ask\":" + sides[1] + ","));
        }
        transport.last().deliver("{\"channel\":\"ticker\",\"type\":\"update\",\"data\":[{\"symbol\":\"BTC/USD\",\"last\":84640}]}");
        deliver("ws_heartbeat.json");
        now.addAndGet(500);

        assertThat(source.poll(feed, List.of(btc)).get("spot_BTC~kraken").raw()).isEqualByComparingTo(good);
        assertThat(KrakenPriceSource.midOf(Fixtures.parse("{\"a\":[\"2\",\"1\",\"1.000\"],\"b\":[\"1\",\"1\",\"1.000\"]}")))
                .isEqualByComparingTo("1.5");
        assertThat(KrakenPriceSource.midOf(Fixtures.parse("{\"a\":[\"1\",\"1\",\"1.000\"],\"b\":[\"2\",\"1\",\"1.000\"]}")))
                .as("crossed").isNull();
        assertThat(KrakenPriceSource.midOf(Fixtures.parse("{}"))).isNull();
        assertThat(KrakenPriceSource.midOf(null)).isNull();
    }

    @Test
    void aPairMissingFromTheAnswerGetsNoTickAndTheOthersAreUnharmed() throws Exception {
        api.ticker.handler = pairs -> {
            ObjectNode answer = Fixtures.json("kraken/rest_ticker_symbols.json").deepCopy();
            ((ObjectNode) answer.get("result")).remove("DOGE/USD");
            return answer;
        };

        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(btc, doge));

        assertThat(quotes).containsOnlyKeys("spot_BTC~kraken");
    }

    @Test
    void messagesThatAreNoTickerAreNotQuotesAndNeverFailAnything() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();

        deliver("ws_status.json");
        deliver("ws_subscribe_ack.json");
        deliver("ws_subscribe_error.json");
        deliver("ws_heartbeat.json");
        transport.last().deliver("this is not json");
        transport.last().deliver("{\"channel\":\"something-new\",\"data\":[{}]}");
        transport.last().deliver("{\"channel\":\"ticker\"}");
        api.ticker.requests.clear();
        now.addAndGet(500);

        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(btc));

        assertThat(quotes.get("spot_BTC~kraken").raw()).as("no quote from the stream: the REST read").isEqualByComparingTo("84702.95");
        assertThat(api.ticker.requests).containsExactly("BTC/USD");
    }

    // ---------------------------------------------------------------------- subscriptions follow the markets

    @Test
    void aMarketListedLaterIsSubscribedOnTheOpenConnectionAndEverythingAgainAfterAReconnect() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();
        FakeWebSocketTransport.Link first = transport.last();
        assertThat(first.sent).hasSize(1);

        now.addAndGet(2_000);
        deliver("ws_heartbeat.json");
        source.poll(feed, List.of(btc, doge)); // DOGE/USD was listed meanwhile

        assertThat(first.sent).hasSize(2);
        assertThat(first.sent.get(1)).isEqualTo("{\"method\":\"subscribe\",\"params\":{\"channel\":\"ticker\","
                + "\"symbol\":[\"DOGE/USD\"],\"event_trigger\":\"bbo\"}}");
        now.addAndGet(2_000);
        deliver("ws_heartbeat.json");
        source.poll(feed, List.of(btc, doge));
        assertThat(first.sent).as("told once").hasSize(2);

        first.serverCloses(1006, "");
        now.addAndGet(2_000);
        PipelineTestSupport.stepStream(source, now.get()); // the loss is seen
        now.addAndGet(1_100);
        PipelineTestSupport.stepStream(source, now.get()); // and the next attempt is due

        assertThat(transport.links).hasSize(2);
        assertThat(transport.last().sent).containsExactly("{\"method\":\"subscribe\",\"params\":{\"channel\":\"ticker\","
                + "\"symbol\":[\"BTC/USD\",\"DOGE/USD\"],\"event_trigger\":\"bbo\"}}");
    }

    // ---------------------------------------------------------------------- pair names

    @Test
    void theRestAnswerIsReadUnderTheSymbolWhenThatIsHowTheVenueKeysIt() throws Exception {
        source.poll(feed, List.of(btc));

        assertThat(api.assetPairs.requests).as("no lookup of names: the symbol is the key").isEmpty();
    }

    @Test
    void theRestAnswerIsReadUnderThePairsOwnKeyWhenThatIsHowTheVenueKeysIt() throws Exception {
        // an answer keyed XXBTZUSD, XDGUSD — what asking by altname gives: the names come from AssetPairs, not from this code
        api.ticker.handler = pairs -> Fixtures.json("kraken/rest_ticker_altnames.json");

        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(btc, doge, eth));

        assertThat(quotes.get("spot_BTC~kraken").raw()).isEqualByComparingTo("84702.95");
        assertThat(quotes.get("spot_DOGE~kraken").raw()).isEqualByComparingTo("0.0928146");
        assertThat(quotes.get("spot_ETH~kraken").raw()).isEqualByComparingTo("2683.365");
        assertThat(api.assetPairs.requests).as("by symbol, then by the altname it gave, for each pair once")
                .containsExactlyInAnyOrder("BTC/USD", "XBTUSD", "DOGE/USD", "XDGUSD", "ETH/USD", "ETHUSD");
        api.assetPairs.requests.clear();

        source.poll(feed, List.of(btc, doge, eth));

        assertThat(api.assetPairs.requests).as("learned once").isEmpty();
    }

    @Test
    void theNamesOfAPairAreWhatAssetPairsSaysAndNothingIsHardCoded() throws Exception {
        assertThat(source.aliasesOf("BTC/USD")).containsExactlyInAnyOrder("BTC/USD", "XBT/USD", "XBTUSD", "XXBTZUSD");
        assertThat(source.aliasesOf("DOGE/USD")).containsExactlyInAnyOrder("DOGE/USD", "XDG/USD", "XDGUSD");
        assertThat(source.aliasesOf("SOL/USD")).containsExactlyInAnyOrder("SOL/USD", "SOLUSD");
    }

    @Test
    void aLookupOfNamesThatFailsLeavesThePairWithoutATickAndIsRetriedOnTheNextPoll() throws Exception {
        api.ticker.handler = pairs -> Fixtures.json("kraken/rest_ticker_altnames.json");
        api.assetPairs.handler = requested -> {
            throw new IllegalStateException("HTTP 502");
        };

        assertThat(source.poll(feed, List.of(btc))).as("the answer is not under the symbol and its names cannot be had").isEmpty();

        api.assetPairs.handler = this::assetPairsOf;
        assertThat(source.poll(feed, List.of(btc)).get("spot_BTC~kraken").raw()).isEqualByComparingTo("84702.95");
    }

    // ---------------------------------------------------------------------- history

    private static JsonNode ohlc(String fixture) {
        return Fixtures.json("kraken/" + fixture);
    }

    /** One OHLC row: {@code [time, open, high, low, close, vwap, volume, count]}. */
    private static ArrayNode row(long seconds, String open, String high, String low, String close, int count) {
        ArrayNode row = JSON.createArrayNode();
        row.add(seconds).add(open).add(high).add(low).add(close).add("100.0").add("1.0").add(count);
        return row;
    }

    private static JsonNode ohlcAnswer(String symbol, ArrayNode rows, long last) {
        ObjectNode result = JSON.createObjectNode();
        result.set(symbol, rows);
        result.put("last", last);
        ObjectNode answer = JSON.createObjectNode();
        answer.set("error", JSON.createArrayNode());
        answer.set("result", result);
        return answer;
    }

    @Test
    void theHistoryIsTheOhlcOfThePairFromTheMinuteThirtyMinutesBeforeTheRange() throws Exception {
        api.ohlc.handler = call -> ohlc("rest_ohlc_btc_1m.json");
        now.set(SAMPLED);
        long from = 1_791_029_400_000L; // 12:10:00
        long to = 1_791_029_700_000L;   // 12:15:00

        List<PricePoint> points = source.history(btc, from, to);

        assertThat(api.ohlc.requests).hasSize(1);
        FakeApis.Kraken.OhlcCall call = api.ohlc.requests.get(0);
        assertThat(call.pair()).as("the symbol of the pair").isEqualTo("BTC/USD");
        assertThat(call.intervalMinutes()).isEqualTo(1);
        assertThat(call.sinceSeconds()).as("the rows are those AFTER since: one second before the minute asked for")
                .isEqualTo((from - 1_800_000L) / 1000L - 1);
        assertThat(points).isNotEmpty().isSortedAccordingTo((a, b) -> Long.compare(a.atMs(), b.atMs()));
        assertThat(points).allMatch(p -> p.atMs() >= from && p.atMs() <= to);
        // the 12:14 candle [1791029640, o 84672.9, h 84673.0, l 84672.9, c 84673.0]: closed up, low first, then high, then the close
        assertThat(points).contains(new PricePoint(1_791_029_660_000L, new BigDecimal("84672.9")),
                new PricePoint(1_791_029_680_000L, new BigDecimal("84673.0")),
                new PricePoint(1_791_029_699_999L, new BigDecimal("84673.0")));
    }

    @Test
    void theCandleOfTheMinuteInProgressIsNeverUsedWhateverTheClockSays() throws Exception {
        api.ohlc.handler = call -> ohlc("rest_ohlc_btc_1m.json"); // result.last is 12:16:00; its last row is 12:17:00
        now.set(SAMPLED + 3_600_000L); // an hour later by the clock: the 12:17 candle would be closed by it
        long from = 1_791_029_700_000L; // 12:15:00

        PriceSource.History history = source.historyOf(btc, from, from + 600_000L);

        assertThat(history.points()).noneMatch(p -> p.atMs() >= 1_791_029_820_000L);
        assertThat(history.bars()).noneMatch(b -> b.startMs() >= 1_791_029_820_000L);
        assertThat(history.bars()).as("the committed ones").anyMatch(b -> b.startMs() == 1_791_029_760_000L);
    }

    @Test
    void aMinuteWithoutTradesIsAFillerAndNoPriceAndNoBar() throws Exception {
        api.ohlc.handler = call -> ohlc("rest_ohlc_doge_1m.json"); // the 12:11 candle has count 0
        now.set(SAMPLED);
        long from = 1_791_029_400_000L; // 12:10:00
        long to = 1_791_029_700_000L;   // 12:15:00

        PriceSource.History history = source.historyOf(doge, from, to);

        assertThat(history.points()).as("no point of the 12:11 filler (11:11:00 .. 12:11:59.999)")
                .noneMatch(p -> p.atMs() >= 1_791_029_460_000L && p.atMs() <= 1_791_029_519_999L);
        assertThat(history.bars()).noneMatch(b -> b.startMs() == 1_791_029_460_000L);
        assertThat(history.bars()).anyMatch(b -> b.startMs() == 1_791_029_400_000L);
        assertThat(history.bars()).anyMatch(b -> b.startMs() == 1_791_029_520_000L);
    }

    @Test
    void theBoundaryPriceIsTheCloseOfTheCandleBeforeIt() throws Exception {
        api.ohlc.handler = call -> ohlc("rest_ohlc_btc_1m.json");
        now.set(SAMPLED);
        long boundary = 1_791_029_700_000L; // 12:15:00; the 12:14 candle closed at 84673.0

        PriceSource.History history = source.historyOf(btc, 1_791_029_400_000L, 1_791_029_760_000L, boundary);

        assertThat(history.points()).contains(new PricePoint(boundary - 1, new BigDecimal("84673.0")));
        assertThat(history.points()).as("one price at the boundary: the close a millisecond before it")
                .noneMatch(p -> p.atMs() == boundary);
    }

    @Test
    void theOhlcIsOneCallWhateverItAnswersBecauseKrakenHasNoOlderPageToGive() throws Exception {
        // measured 2026-10-03: since a day back answered 721 rows, the first of them 12.0 h old — the latest 720 committed
        // minutes and the one in progress; with since = 1 the same. Asking again from the newest row cannot bring more.
        KrakenOhlcTape tape = KrakenOhlcTape.flat("BTC/USD", 84_000.0);
        api.ohlc.handler = call -> tape.answer(call.sinceSeconds(), now.get());
        now.set(SAMPLED);

        PriceSource.History history = source.historyOf(btc, SAMPLED - 24 * 3_600_000L, SAMPLED);

        assertThat(api.ohlc.requests).as("one request, not a page after page").hasSize(1);
        assertThat(api.ohlc.requests.get(0).sinceSeconds()).as("the minute 24 h and 30 min back, less a second")
                .isEqualTo((SAMPLED - 24 * 3_600_000L - 1_800_000L) / 1000L / 60L * 60L - 1);
        assertThat(history.points()).isNotEmpty();
        assertThat(history.bars()).as("the 720 committed minutes, the one in progress left out").hasSize(KrakenOhlcTape.KEPT);
    }

    @Test
    void anAnswerThatBeginsLaterThanAskedSaysFromWhenItIsComplete() throws Exception {
        KrakenOhlcTape tape = KrakenOhlcTape.flat("BTC/USD", 84_000.0);
        api.ohlc.handler = call -> tape.answer(call.sinceSeconds(), now.get());
        now.set(SAMPLED);
        long firstRowMs = SAMPLED / 60_000L * 60_000L - KrakenOhlcTape.KEPT * 60_000L; // 12.0 h before the minute in progress

        PriceSource.History day = source.historyOf(btc, SAMPLED - 24 * 3_600_000L, SAMPLED);
        PriceSource.History hours = source.historyOf(btc, SAMPLED - 3 * 3_600_000L, SAMPLED);

        assertThat(day.coveredFromMs()).as("what lies before the first row is no data").isEqualTo(firstRowMs);
        assertThat(day.reaches(firstRowMs)).isTrue();
        assertThat(day.reaches(firstRowMs - 1)).isFalse();
        assertThat(hours.coveredFromMs()).as("it reaches back as far as it was asked to").isEqualTo(PriceSource.History.NO_LIMIT);
        assertThat(hours.reaches(0L)).isTrue();
    }

    @Test
    void theCandlesOfATruncatedAnswerAreAllThereIsAndTheMinuteInProgressIsNotOne() throws Exception {
        KrakenOhlcTape tape = KrakenOhlcTape.flat("BTC/USD", 84_000.0).quiet(SAMPLED / 60_000L * 60_000L - 5 * 60_000L);
        api.ohlc.handler = call -> tape.answer(call.sinceSeconds(), now.get());
        now.set(SAMPLED);

        KrakenPriceSource.Candles candles = source.candles("BTC/USD", SAMPLED - 24 * 3_600_000L);

        assertThat(candles.list()).hasSize(KrakenPriceSource.MAX_ROWS); // the 720 committed minutes; the 721st is in progress
        assertThat(KrakenOhlcTape.KEPT).as("the tape keeps what the source says Kraken keeps").isEqualTo(KrakenPriceSource.MAX_ROWS);
        assertThat(candles.list()).isSortedAccordingTo((a, b) -> Long.compare(a.t(), b.t()));
        assertThat(candles.list().stream().filter(c -> !c.hasTrades())).as("the filler of the quiet minute is there, as a filler")
                .hasSize(1);
        assertThat(candles.coveredFromMs()).isEqualTo(candles.list().get(0).t());
    }

    @Test
    void anOhlcAnswerKeyedByThePairsOwnKeyIsReadByItsNames() throws Exception {
        ObjectNode answer = ohlc("rest_ohlc_btc_1m.json").deepCopy();
        ObjectNode result = (ObjectNode) answer.get("result");
        result.set("XXBTZUSD", result.remove("BTC/USD")); // the answer under the key of the pair, not under the symbol
        api.ohlc.handler = call -> answer;
        now.set(SAMPLED);

        PriceSource.History history = source.historyOf(btc, 1_791_029_400_000L, 1_791_029_700_000L);

        assertThat(history.points()).isNotEmpty();
        assertThat(history.bars()).isNotEmpty();
    }

    @Test
    void rowsThatAreNotWholeAreLeftOut() {
        JsonNode rows = Fixtures.parse("[[1791029640,\"84672.9\",\"84673.0\",\"84672.9\",\"84673.0\",\"84672.9\",\"0.03\",8],"
                + "[1791029700,\"x\",\"84682.9\",\"84672.9\",\"84682.9\",\"84673.2\",\"3.7\",50],"
                + "[1791029760,\"84682.9\",\"84683.0\"],\"nope\",[\"t\",1,2,3,4,5,6,7],"
                + "[1791029820,\"84683.0\",\"84702.8\",\"84682.9\",\"84702.8\",\"84688.6\",\"8.9\",\"many\"]]");

        List<CandleHistory.Candle> candles = KrakenPriceSource.candles(rows, Long.MAX_VALUE);

        assertThat(candles).hasSize(1);
        assertThat(candles.get(0).t()).isEqualTo(1_791_029_640_000L);
        assertThat(candles.get(0).closeAt()).isEqualTo(1_791_029_699_999L);
        assertThat(candles.get(0).trades()).isEqualTo(8L);
        assertThat(KrakenPriceSource.candles((JsonNode) null, 0)).isEmpty();
        assertThat(KrakenPriceSource.candles(Fixtures.parse("{}"), 0)).isEmpty();
    }

    @Test
    void aHistoryRequestThatFailsOrIsRefusedFailsTheCall() {
        api.ohlc.handler = call -> {
            throw new IllegalStateException("HTTP 500");
        };
        assertThatThrownBy(() -> source.history(btc, T0 - 600_000L, T0)).isInstanceOf(IllegalStateException.class);

        api.ohlc.handler = call -> Fixtures.parse("{\"error\":[\"EQuery:Unknown asset pair\"]}");
        assertThatThrownBy(() -> source.history(btc, T0 - 600_000L, T0)).isInstanceOf(IOException.class)
                .hasMessageContaining("Unknown asset pair");
    }

    // ---------------------------------------------------------------------- status

    @Test
    void theStatusOfTheStreamSaysIdleConnectedSilentOrRestFallback() throws Exception {
        assertThat(source.status(feed, T0).state()).as("nobody has asked").isEqualTo("idle");
        assertThat(source.status(feed, T0).mode()).isEqualTo("stream");
        assertThat(source.status(feed, T0).venue()).isEqualTo("kraken");
        assertThat(source.status(feed, T0).feed()).isEqualTo("kraken");

        source.poll(feed, List.of(btc, doge)); // asks for the stream, and reads REST for both
        assertThat(source.status(feed, now.get()).state()).as("wanted, not connected yet").isEqualTo("rest-fallback");
        assertThat(source.status(feed, now.get()).restReads()).isEqualTo(2);

        connectTheStream();
        deliver("ws_heartbeat.json");
        assertThat(source.status(feed, now.get()).state()).isEqualTo("connected");
        assertThat(source.status(feed, now.get()).lastMessageAtMs()).isEqualTo(T0);
        deliver("ws_ticker_snapshot_btc.json");
        deliver("ws_ticker_snapshot_doge.json");
        source.poll(feed, List.of(btc, doge));
        assertThat(source.status(feed, now.get()).restReads()).as("both from the stream").isZero();

        assertThat(source.status(feed, T0 + 6_000).state()).as("open, but nothing heard for longer than the limit")
                .isEqualTo("silent");

        transport.last().serverCloses(1006, "");
        PipelineTestSupport.stepStream(source, T0 + 100);
        assertThat(source.status(feed, T0 + 100).state()).isEqualTo("rest-fallback");

        assertThat(source.status(feed, T0 + 10 * 60_000L).state()).as("nobody polls any more").isEqualTo("idle");
    }
}

