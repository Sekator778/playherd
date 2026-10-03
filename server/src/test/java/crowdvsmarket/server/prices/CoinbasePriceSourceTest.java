package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import crowdvsmarket.server.markets.Market;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Coinbase source against messages and answers of 2026-10-03 (trimmed samples, fixtures/coinbase/): the mid of a
 * ticker message by product, a poll answered from memory without a request, the REST ticker for the product whose quote
 * is stale or whose stream is down, the subscription that follows the markets, and the 1-minute candles as history
 * (pages, the closed ones only, the minute without trades that has no row, the last trade). A scripted WebSocket and a
 * scripted REST client: nothing here touches the network.
 */
class CoinbasePriceSourceTest {

    /** 2026-10-03T11:08:45Z: when the sampled stream was subscribed. */
    private static final long T0 = 1_791_025_725_000L;
    /** 2026-10-03T11:25:00Z: after every candle of the fixtures has closed. */
    private static final long LATER = 1_791_026_700_000L;

    private final AtomicLong now = new AtomicLong(T0);
    private final FakeApis.Coinbase api = new FakeApis.Coinbase();
    private final FakeWebSocketTransport transport = new FakeWebSocketTransport();
    private final Market btc = TestMarkets.coinbase("BTC", 0, 1);
    private final Market eth = TestMarkets.coinbase("ETH", 0, 2);
    private final Market sol = TestMarkets.coinbase("SOL", 1, 3);
    private final Market xrp = TestMarkets.coinbase("XRP", 3, 4);
    private final Market doge = TestMarkets.coinbase("DOGE", 5, 5);
    private CoinbasePriceSource source;
    private PriceSource.Feed feed;

    @BeforeEach
    void setUp() {
        source = PipelineTestSupport.coinbase(api, transport, now::get);
        feed = source.feeds().get(0);
        api.ticker.handler = product -> Fixtures.json(product.startsWith("DOGE") ? "coinbase/rest_ticker_doge.json"
                : "coinbase/rest_ticker_btc.json");
    }

    private static String ws(String fixture) {
        return Fixtures.json("coinbase/" + fixture).toString();
    }

    private void deliver(String fixture) {
        transport.last().deliver(ws(fixture));
    }

    /** What a poll does for the stream: the supervisor's thread would look at it within a quarter of a second. */
    private void connectTheStream() {
        PipelineTestSupport.stepStream(source, now.get());
        assertThat(transport.attempts).isPositive();
    }

    private static BigDecimal midOf(String fixture) {
        JsonNode m = Fixtures.json("coinbase/" + fixture);
        return new BigDecimal(m.get("best_bid").asText()).add(new BigDecimal(m.get("best_ask").asText()))
                .divide(BigDecimal.valueOf(2));
    }

    // ---------------------------------------------------------------------- the feed

    @Test
    void oneFeedForTheCoinbaseMarketsWithTheLimitsOfTheConfiguration() {
        assertThat(source.name()).isEqualTo("coinbase");
        assertThat(source.feeds()).hasSize(1);
        assertThat(feed.id()).isEqualTo("coinbase");
        assertThat(feed.staleMs()).isEqualTo(15_000L);
        assertThat(feed.maxGapMs()).isEqualTo(30_000L);
        assertThat(feed.maxBasis()).as("this feed's own tolerance: 15 bp").isEqualTo(0.0015);
        assertThat(feed.handles().test(btc)).isTrue();
        assertThat(feed.handles().test(TestMarkets.perp("ETH", "ETH", "crypto", 25, 0, 1))).as("a Hyperliquid perpetual").isFalse();
        assertThat(feed.handles().test(TestMarkets.hlSpot("HYPE", "@107", 2, 9))).isFalse();
        assertThat(source.historyKind()).isEqualTo(PriceSource.HistoryKind.TRADE_PRICES);
        assertThat(source.historyWeight(86_400_000L)).as("the 10 requests a second are not paced by the seeder").isZero();
    }

    @Test
    void theLimitsAreTheConfiguredOnes() {
        SpotPricesConfig config = SpotPricesConfig.defaults()
                .withCoinbase(new SpotPricesConfig.Limits(20_000, 45_000, 0.001, 3_000, 7_000));
        CoinbasePriceSource custom = new CoinbasePriceSource(() -> api, config, transport, now::get, false);

        PriceSource.Feed f = custom.feeds().get(0);

        assertThat(f.staleMs()).isEqualTo(20_000L);
        assertThat(f.maxGapMs()).isEqualTo(45_000L);
        assertThat(f.maxBasis()).isEqualTo(0.001);
    }

    // ---------------------------------------------------------------------- the stream

    @Test
    void aPollIsAnsweredFromTheNewestTickerMessageWithNoRequestAtAll() throws Exception {
        // the stream is not up yet: the first poll reads the REST ticker (best bid 84631.94, ask 84631.95) and asks for the stream
        Map<String, PriceSource.Quote> first = source.poll(feed, List.of(btc));
        assertThat(first.get("spot_BTC").raw()).isEqualByComparingTo("84631.945");
        assertThat(api.ticker.requests).containsExactly("BTC-USD");

        connectTheStream();
        assertThat(transport.last().uri).isEqualTo(URI.create("wss://ws-feed.exchange.coinbase.com"));
        assertThat(transport.last().sent).containsExactly(
                "{\"type\":\"subscribe\",\"product_ids\":[\"BTC-USD\"],\"channels\":[\"ticker\",\"heartbeat\"]}");
        deliver("ws_subscriptions.json");
        deliver("ws_ticker_btc.json"); // best bid 84635.56, best ask 84635.57
        now.addAndGet(1_000);

        Map<String, PriceSource.Quote> second = source.poll(feed, List.of(btc));

        assertThat(second.get("spot_BTC").raw()).as("(best bid + best ask) / 2").isEqualByComparingTo("84635.565");
        assertThat(api.ticker.requests).as("no further request: the quote is 1 s old").hasSize(1);
        // open_24h 86412.21 in the same message
        assertThat(second.get("spot_BTC").change24h()).isCloseTo(-0.02056, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void everyProductIsPricedByItsOwnMessage() throws Exception {
        List<Market> markets = List.of(btc, eth, sol, xrp, doge);
        source.poll(feed, markets); // the stream is wanted from here on
        api.ticker.requests.clear();
        connectTheStream();

        deliver("ws_ticker_btc.json");
        deliver("ws_ticker_eth.json");
        deliver("ws_ticker_sol.json");
        deliver("ws_ticker_xrp.json");
        deliver("ws_ticker_doge.json");
        now.addAndGet(500);
        Map<String, PriceSource.Quote> quotes = source.poll(feed, markets);

        assertThat(quotes).containsOnlyKeys("spot_BTC", "spot_ETH", "spot_SOL", "spot_XRP", "spot_DOGE");
        assertThat(quotes.get("spot_BTC").raw()).isEqualByComparingTo(midOf("ws_ticker_btc.json"));
        assertThat(quotes.get("spot_ETH").raw()).isEqualByComparingTo(midOf("ws_ticker_eth.json"));
        assertThat(quotes.get("spot_SOL").raw()).isEqualByComparingTo(midOf("ws_ticker_sol.json"));
        assertThat(quotes.get("spot_XRP").raw()).isEqualByComparingTo(new BigDecimal("1.48245")); // 1.4824 and 1.4825
        assertThat(quotes.get("spot_DOGE").raw()).isEqualByComparingTo(new BigDecimal("0.09282500")); // 0.09281 and 0.09284
        assertThat(api.ticker.requests).isEmpty();
    }

    @Test
    void aQuoteIsFreshForFiveSecondsAndThenReadOverRestForThatProductOnly() throws Exception {
        List<Market> markets = List.of(btc, doge);
        source.poll(feed, markets);
        connectTheStream();
        deliver("ws_ticker_btc.json");
        deliver("ws_ticker_doge.json");
        api.ticker.requests.clear();

        now.set(T0 + 5_000); // the messages arrived at T0 + 0: exactly the freshness limit
        Map<String, PriceSource.Quote> atTheLimit = source.poll(feed, markets);
        assertThat(atTheLimit).hasSize(2);
        assertThat(api.ticker.requests).isEmpty();

        // BTC trades on; DOGE does not
        now.set(T0 + 5_001);
        transport.last().deliver(ws("ws_ticker_btc.json"));
        Map<String, PriceSource.Quote> pastIt = source.poll(feed, markets);

        assertThat(api.ticker.requests).as("only DOGE-USD is stale").containsExactly("DOGE-USD");
        assertThat(pastIt.get("spot_BTC").raw()).isEqualByComparingTo(midOf("ws_ticker_btc.json"));
        assertThat(pastIt.get("spot_DOGE").raw()).as("the REST ticker's bid 0.09267, ask 0.09268")
                .isEqualByComparingTo("0.092675");
    }

    @Test
    void aStreamThatCannotBeHadMeansOneRestReadPerMarketPerPollAndNoFailure() throws Exception {
        transport.connectFailure = new IOException("connection refused");
        List<Market> markets = List.of(btc, doge);

        Map<String, PriceSource.Quote> first = source.poll(feed, markets);
        connectTheStream(); // the supervisor tries, in vain
        now.addAndGet(2_000);
        Map<String, PriceSource.Quote> second = source.poll(feed, markets);

        assertThat(first).containsOnlyKeys("spot_BTC", "spot_DOGE");
        assertThat(second).containsOnlyKeys("spot_BTC", "spot_DOGE");
        assertThat(api.ticker.requests).containsExactlyInAnyOrder("BTC-USD", "DOGE-USD", "BTC-USD", "DOGE-USD");
        assertThat(transport.attempts).isEqualTo(1);
    }

    @Test
    void aSilentStreamIsTheSameAsADownOne_theRestReadsTakeOver() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();
        deliver("ws_ticker_btc.json");
        api.ticker.requests.clear();

        now.set(T0 + 20_000); // 20 s of silence: the supervisor drops the connection ...
        PipelineTestSupport.stepStream(source, now.get());
        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(btc));

        assertThat(transport.last().aborted).isTrue();
        assertThat(quotes.get("spot_BTC").raw()).as("... and the poll reads the venue's REST ticker")
                .isEqualByComparingTo("84631.945");
        assertThat(api.ticker.requests).containsExactly("BTC-USD");
    }

    @Test
    void theOpen24hOfTheStreamSurvivesAnOutage() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();
        deliver("ws_ticker_btc.json"); // open_24h 86412.21
        now.addAndGet(60_000); // long past the freshness limit: REST

        PriceSource.Quote viaRest = source.poll(feed, List.of(btc)).get("spot_BTC");

        assertThat(viaRest.raw()).isEqualByComparingTo("84631.945");
        assertThat(viaRest.change24h()).as("(84631.945 - 86412.21) / 86412.21")
                .isCloseTo(-0.02060, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void aRestReadThatFailsCostsThatMarketItsTickOnly() throws Exception {
        source.poll(feed, List.of(btc, doge));
        connectTheStream();
        deliver("ws_ticker_btc.json");
        api.ticker.handler = product -> {
            throw new IllegalStateException("HTTP 503");
        };

        now.addAndGet(100);
        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(btc, doge)); // DOGE has no quote and its read fails

        assertThat(quotes).containsOnlyKeys("spot_BTC");
    }

    @Test
    void aPollFailsOnlyWhenNoMarketGotAPrice() {
        api.ticker.handler = product -> {
            throw new IllegalStateException("HTTP 503");
        };

        assertThatThrownBy(() -> source.poll(feed, List.of(btc, doge))).isInstanceOf(IOException.class)
                .hasMessageContaining("no Coinbase price for any market");
    }

    @Test
    void aBookThatIsNoBookIsNotAQuote() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();
        deliver("ws_ticker_btc.json");
        BigDecimal good = midOf("ws_ticker_btc.json");

        // crossed, locked-free but missing a side, zero, text: none replaces the good quote
        JsonNode ticker = Fixtures.json("coinbase/ws_ticker_btc.json");
        for (String[] sides : new String[][] {{"84640.00", "84639.00"}, {"0", "84639"}, {"84639", "-1"}, {"x", "84640"}}) {
            transport.last().deliver(ticker.deepCopy().toString().replace("\"best_bid\":\"84635.56\"", "\"best_bid\":\"" + sides[0] + "\"")
                    .replace("\"best_ask\":\"84635.57\"", "\"best_ask\":\"" + sides[1] + "\""));
        }
        transport.last().deliver("{\"type\":\"ticker\",\"product_id\":\"BTC-USD\",\"price\":\"84640\"}"); // no book at all
        now.addAndGet(1_000);

        assertThat(source.poll(feed, List.of(btc)).get("spot_BTC").raw()).isEqualByComparingTo(good);
        assertThat(CoinbasePriceSource.isBook(new BigDecimal("1"), new BigDecimal("1"))).as("a locked book is a price").isTrue();
        assertThat(CoinbasePriceSource.isBook(new BigDecimal("2"), new BigDecimal("1"))).isFalse();
    }

    @Test
    void aRestTickerWithNoBookGivesNoPriceForThatMarketAndIsNoFailureOfThePoll() throws Exception {
        api.ticker.handler = product -> Fixtures.parse("{\"ask\":\"0\",\"bid\":\"84631.94\",\"price\":\"84631.94\"}");

        assertThat(source.poll(feed, List.of(btc))).as("an answer that is no price costs the market its tick").isEmpty();
        assertThat(CoinbasePriceSource.midOf(Fixtures.json("coinbase/rest_ticker_btc.json"))).isEqualByComparingTo("84631.945");
        assertThat(CoinbasePriceSource.midOf(Fixtures.parse("{}"))).isNull();
    }

    @Test
    void messagesThatAreNoTickerAreNotQuotesAndNeverFailAnything() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();

        deliver("ws_subscriptions.json");
        deliver("ws_heartbeat.json");
        transport.last().deliver("{\"type\":\"error\",\"message\":\"Failed to subscribe\",\"reason\":\"NOPE-USD is not a valid product\"}");
        transport.last().deliver("this is not json");
        transport.last().deliver("{\"type\":\"something-new\"}");
        transport.last().deliver("{\"type\":\"ticker\"}");
        api.ticker.requests.clear();
        now.addAndGet(500);

        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(btc));

        assertThat(quotes.get("spot_BTC").raw()).as("no quote from the stream: the REST read").isEqualByComparingTo("84631.945");
        assertThat(api.ticker.requests).containsExactly("BTC-USD");
    }

    // ---------------------------------------------------------------------- subscriptions follow the markets

    @Test
    void aMarketListedLaterIsSubscribedOnTheOpenConnectionAndEverythingAgainAfterAReconnect() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();
        FakeWebSocketTransport.Link first = transport.last();
        assertThat(first.sent).hasSize(1);

        now.addAndGet(2_000);
        source.poll(feed, List.of(btc, doge)); // DOGE-USD was listed meanwhile

        assertThat(first.sent).hasSize(2);
        assertThat(first.sent.get(1)).isEqualTo(
                "{\"type\":\"subscribe\",\"product_ids\":[\"DOGE-USD\"],\"channels\":[\"ticker\",\"heartbeat\"]}");
        now.addAndGet(2_000);
        source.poll(feed, List.of(btc, doge));
        assertThat(first.sent).as("told once").hasSize(2);

        first.serverCloses(1006, "");
        now.addAndGet(2_000);
        source.poll(feed, List.of(btc, doge));
        PipelineTestSupport.stepStream(source, now.get()); // the loss is seen
        now.addAndGet(1_100);
        source.poll(feed, List.of(btc, doge));
        PipelineTestSupport.stepStream(source, now.get()); // and the next attempt is due

        assertThat(transport.links).hasSize(2);
        assertThat(transport.last().sent).containsExactly(
                "{\"type\":\"subscribe\",\"product_ids\":[\"BTC-USD\",\"DOGE-USD\"],\"channels\":[\"ticker\",\"heartbeat\"]}");
    }

    @Test
    void withNoActiveMarketNothingIsConnectedAndNothingIsAsked() throws Exception {
        assertThat(source.poll(feed, List.of())).isEmpty();
        source.shutdown();

        assertThat(transport.attempts).isZero();
        assertThat(api.ticker.requests).isEmpty();
        assertThat(api.candles.requests).isEmpty();
    }

    @Test
    void theStreamIsClosedWithTheApplication() throws Exception {
        source.poll(feed, List.of(btc));
        connectTheStream();

        source.shutdown();

        assertThat(transport.last().aborted).isTrue();
    }

    // ---------------------------------------------------------------------- history: the 1-minute candles

    /** The rows of a candles answer: numbers where a cell reads as one (as Coinbase sends them), text where it does not. */
    private static JsonNode rows(Object[]... rows) {
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        for (Object[] row : rows) {
            ArrayNode cells = out.addArray();
            for (Object cell : row) {
                if (cell instanceof Long l) {
                    cells.add(l);
                } else {
                    try {
                        cells.add(new BigDecimal(String.valueOf(cell)));
                    } catch (NumberFormatException e) {
                        cells.add(String.valueOf(cell));
                    }
                }
            }
        }
        return out;
    }

    /** A candle row {@code [time, low, high, open, close, volume]} of the minute starting at {@code minuteMs}. */
    private static Object[] row(long minuteMs, String low, String high, String open, String close, String volume) {
        return new Object[] {minuteMs / 1000L, low, high, open, close, volume};
    }

    @Test
    void candlesAreAskedForInPagesOfThreeHundredMinutesWithIsoBoundsAndTheMinuteBeforeTheEnd() throws Exception {
        api.candles.handler = call -> Fixtures.parse("[]");
        long from = Instant.parse("2026-10-03T00:00:00Z").toEpochMilli();
        long to = Instant.parse("2026-10-03T11:00:00Z").toEpochMilli();

        source.history(btc, from, to);

        // 30 minutes of lookback before the range, up to the minute that holds toMs - 1 (10:59): 11 h 29 min = 690 minutes
        assertThat(api.candles.requests).containsExactly(
                new FakeApis.Coinbase.CandlesCall("BTC-USD", 60, "2026-10-02T23:30:00Z", "2026-10-03T04:29:00Z"),
                new FakeApis.Coinbase.CandlesCall("BTC-USD", 60, "2026-10-03T04:30:00Z", "2026-10-03T09:29:00Z"),
                new FakeApis.Coinbase.CandlesCall("BTC-USD", 60, "2026-10-03T09:30:00Z", "2026-10-03T10:59:00Z"));
    }

    @Test
    void anEndInTheMiddleOfAMinuteAsksForThatMinute() throws Exception {
        api.candles.handler = call -> Fixtures.parse("[]");
        long from = Instant.parse("2026-10-03T11:15:00Z").toEpochMilli();

        source.history(btc, from, Instant.parse("2026-10-03T11:20:30Z").toEpochMilli());
        source.history(btc, from, Instant.parse("2026-10-03T11:20:00Z").toEpochMilli());

        assertThat(api.candles.requests).extracting(FakeApis.Coinbase.CandlesCall::end)
                .containsExactly("2026-10-03T11:20:00Z", "2026-10-03T11:19:00Z"); // the one at :00 holds trades after the end
        assertThat(api.candles.requests).extracting(FakeApis.Coinbase.CandlesCall::start)
                .containsOnly("2026-10-03T10:45:00Z");
    }

    @Test
    void theRowsOfTheRealAnswerAreBarsAndPointsOfClosedMinutes() throws Exception {
        api.candles.handler = call -> Fixtures.json("coinbase/rest_candles_btc_1m.json"); // 11:13 … 11:20, newest first
        now.set(LATER);
        long from = Instant.parse("2026-10-03T11:15:00Z").toEpochMilli();
        long to = Instant.parse("2026-10-03T11:20:00Z").toEpochMilli();

        PriceSource.History history = source.historyOf(btc, from, to, from, to);

        assertThat(history.bars()).hasSize(8).isSortedAccordingTo((a, b) -> Long.compare(a.startMs(), b.startMs()));
        PriceSource.Bar last = history.bars().get(7); // 11:20: [low 84625.87, high 84636.68, open 84636.67, close 84625.87]
        assertThat(last.startMs()).isEqualTo(to);
        assertThat(last.endMs()).isEqualTo(to + 59_999L);
        assertThat(last.low()).isEqualByComparingTo("84625.87");
        assertThat(last.high()).isEqualByComparingTo("84636.68");
        assertThat(last.open()).isEqualByComparingTo("84636.67");
        assertThat(last.close()).isEqualByComparingTo("84625.87");
        // the price at the start of the range is the close of the minute before it (11:14: close 84634.06), at the boundary itself
        assertThat(history.points().get(0)).isEqualTo(new PricePoint(from, new BigDecimal("84634.06")));
        // and the price at the end is the close of 11:19 (84636.67), one millisecond before the boundary: nothing is added at it
        assertThat(history.points().get(history.points().size() - 1))
                .isEqualTo(new PricePoint(to - 1, new BigDecimal("84636.67")));
        assertThat(history.points()).allSatisfy(p -> assertThat(p.atMs()).isBetween(from, to));
        assertThat(history.lastTrade()).as("the close of 11:14 is the last trade before the range")
                .contains(new PricePoint(from - 1, new BigDecimal("84634.06")));
        assertThat(api.ticker.requests).as("the candles had it: the ticker is not asked").isEmpty();
    }

    @Test
    void theMinuteInProgressIsNotAClosedCandle() throws Exception {
        long minute = Instant.parse("2026-10-03T11:20:00Z").toEpochMilli();
        api.candles.handler = call -> rows(row(minute - 60_000L, "100", "101", "100.5", "100.8", "3"),
                row(minute, "99", "103", "100.8", "102", "1"));
        now.set(minute + 30_000L); // half-way through 11:20

        PriceSource.History history = source.historyOf(btc, minute - 120_000L, minute + 30_000L);

        assertThat(history.bars()).extracting(PriceSource.Bar::startMs).containsExactly(minute - 60_000L);
        assertThat(history.points()).allSatisfy(p -> assertThat(p.atMs()).isLessThan(minute));
    }

    @Test
    void aMinuteWithoutTradesHasNoRowAndNoBar() throws Exception {
        api.candles.handler = call -> Fixtures.json("coinbase/rest_candles_doge_1m.json"); // 19 rows over 20 minutes
        now.set(LATER);
        long from = Instant.parse("2026-10-03T11:00:00Z").toEpochMilli();
        long to = Instant.parse("2026-10-03T11:20:00Z").toEpochMilli();

        PriceSource.History history = source.historyOf(doge, from, to);

        List<Long> starts = history.bars().stream().map(PriceSource.Bar::startMs).toList();
        assertThat(starts).hasSize(19);
        assertThat(starts).doesNotContain(Instant.parse("2026-10-03T11:09:00Z").toEpochMilli()); // no row: no trades
        assertThat(starts).contains(Instant.parse("2026-10-03T11:08:00Z").toEpochMilli(),
                Instant.parse("2026-10-03T11:10:00Z").toEpochMilli());
    }

    @Test
    void rowsThatAreNotWholeOrDidNotTradeAreLeftOut() {
        JsonNode answer = rows(row(60_000L, "1", "2", "1", "2", "5"), new Object[] {120L, "1", "2", "1", "2", "0"},
                new Object[] {180L}, new Object[] {240L, "x", "2", "1", "2", "1"}, row(300_000L, "1", "2", "1", "2", "7"));

        List<CandleHistory.Candle> candles = CoinbasePriceSource.candles(answer);

        assertThat(candles).extracting(CandleHistory.Candle::t).containsExactly(60_000L, 300_000L);
        assertThat(candles.get(0).closeAt()).isEqualTo(119_999L);
        assertThat(candles.get(0).hasTrades()).isTrue();
        assertThat(CoinbasePriceSource.candles(Fixtures.parse("{\"message\":\"NotFound\"}"))).isEmpty();
        assertThat(CoinbasePriceSource.candles(null)).isEmpty();
    }

    @Test
    void theLastTradeComesFromTheTickerWhenTheCandlesDoNotReachBackToIt() throws Exception {
        long from = Instant.parse("2026-10-03T11:18:00Z").toEpochMilli();
        long to = Instant.parse("2026-10-03T11:20:00Z").toEpochMilli();
        api.candles.handler = call -> Fixtures.parse("[]"); // nothing traded for 30 minutes + the range
        api.ticker.handler = product -> Fixtures.parse("{\"ask\":\"2\",\"bid\":\"1\",\"price\":\"1.5\","
                + "\"time\":\"2026-10-03T10:12:34.567891234Z\"}");
        now.set(LATER);

        PriceSource.History history = source.historyOf(doge, from, to);

        assertThat(history.lastTrade()).contains(new PricePoint(Instant.parse("2026-10-03T10:12:34.567Z").toEpochMilli(),
                new BigDecimal("1.5")));
        assertThat(api.ticker.requests).containsExactly("DOGE-USD");
    }

    @Test
    void aLastTradeInsideTheRangeIsNoEvidenceThatNothingTradedAndATickerThatFailsIsNone() throws Exception {
        long from = Instant.parse("2026-10-03T11:18:00Z").toEpochMilli();
        long to = Instant.parse("2026-10-03T11:20:00Z").toEpochMilli();
        api.candles.handler = call -> Fixtures.parse("[]");
        api.ticker.handler = product -> Fixtures.parse("{\"price\":\"1.5\",\"time\":\"2026-10-03T11:19:00Z\"}");
        now.set(LATER);

        assertThat(source.historyOf(doge, from, to).lastTrade()).isEmpty(); // it traded inside: not before the range

        api.ticker.handler = product -> {
            throw new IllegalStateException("HTTP 503");
        };
        assertThat(source.historyOf(doge, from, to).lastTrade()).isEmpty();

        api.ticker.handler = product -> Fixtures.parse("{\"price\":\"1.5\",\"time\":\"not a time\"}");
        assertThat(source.historyOf(doge, from, to).lastTrade()).isEqualTo(Optional.empty());
    }

    @Test
    void theStatusOfTheStreamSaysIdleConnectedSilentOrRestFallback() throws Exception {
        assertThat(source.status(feed, T0).state()).as("nobody has asked").isEqualTo("idle");
        assertThat(source.status(feed, T0).mode()).isEqualTo("stream");
        assertThat(source.status(feed, T0).venue()).isEqualTo("coinbase");
        assertThat(source.status(feed, T0).feed()).isEqualTo("coinbase");

        source.poll(feed, List.of(btc, doge)); // asks for the stream, and reads REST for both
        assertThat(source.status(feed, now.get()).state()).as("wanted, not connected yet").isEqualTo("rest-fallback");
        assertThat(source.status(feed, now.get()).restReads()).isEqualTo(2);

        connectTheStream();
        deliver("ws_heartbeat.json");
        assertThat(source.status(feed, now.get()).state()).as("a heartbeat is a message").isEqualTo("connected");
        assertThat(source.status(feed, now.get()).lastMessageAtMs()).isEqualTo(T0);
        deliver("ws_ticker_btc.json");
        deliver("ws_ticker_doge.json");
        source.poll(feed, List.of(btc, doge));
        assertThat(source.status(feed, now.get()).restReads()).as("both from the stream").isZero();

        assertThat(source.status(feed, T0 + 16_000).state()).as("open, but nothing heard for longer than the limit")
                .isEqualTo("silent");

        transport.last().serverCloses(1006, "");
        PipelineTestSupport.stepStream(source, T0 + 100);
        assertThat(source.status(feed, T0 + 100).state()).isEqualTo("rest-fallback");

        assertThat(source.status(feed, T0 + 10 * 60_000L).state()).as("nobody polls any more").isEqualTo("idle");
    }

    @Test
    void aFailedCandleReadIsThrownForTheCallerToLog() {
        api.candles.handler = call -> {
            throw new IllegalStateException("HTTP 429");
        };

        assertThatThrownBy(() -> source.history(btc, T0, T0 + 600_000L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theCandlesOfTwoPagesAreMergedOnceByTime() throws Exception {
        long start = Instant.parse("2026-10-03T00:00:00Z").toEpochMilli();
        List<String> asked = new ArrayList<>();
        api.candles.handler = call -> {
            asked.add(call.start());
            // the same minute 05:00 comes back in both pages (an overlap at the seam): it must count once
            return rows(row(start + 5 * 3_600_000L, "1", "2", "1", "2", "5"));
        };
        now.set(LATER);

        PriceSource.History history = source.historyOf(btc, start + 4 * 3_600_000L, start + 11 * 3_600_000L);

        assertThat(asked).hasSizeGreaterThan(1);
        assertThat(history.bars()).hasSize(1);
    }
}
