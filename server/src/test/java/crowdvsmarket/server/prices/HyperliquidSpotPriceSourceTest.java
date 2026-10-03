package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Hyperliquid's spot pairs against the answers and messages of 2026-10-03 (trimmed samples, fixtures/hyperliquid/): a pair
 * is found <b>by the names of its tokens</b> in the spot meta and its price by the {@code coin} field of the contexts
 * (never by position), a poll is answered from the newest {@code allMids} message without a request, a stale or missing
 * value is read once over REST from the same venue, and the history is the {@code candleSnapshot} of the pair — all over a
 * scripted WebSocket and a scripted REST client: nothing here touches the network.
 */
class HyperliquidSpotPriceSourceTest {

    /** 2026-10-03T11:08:45Z: when the sampled stream was subscribed. */
    private static final long T0 = 1_791_025_725_000L;
    /** 2026-10-03T11:25:00Z: after every candle of the fixture has closed. */
    private static final long LATER = 1_791_026_700_000L;

    private final AtomicLong now = new AtomicLong(T0);
    private final FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
    private final FakeWebSocketTransport transport = new FakeWebSocketTransport();
    private final JsonNode spotMeta = Fixtures.json("hyperliquid/spot_meta_and_asset_ctxs.json");
    private final Market hype = TestMarkets.hlSpot("HYPE", "@107", 2, 9);
    private HyperliquidSpotPriceSource source;
    private PriceSource.Feed feed;

    @BeforeEach
    void setUp() {
        source = PipelineTestSupport.hyperliquidSpot(api, transport, now::get);
        feed = source.feeds().get(0);
        api.info.handler = body -> {
            if ("candleSnapshot".equals(body.path("type").asText())) {
                return Fixtures.json("hyperliquid/candle_snapshot_hype_spot_1m.json");
            }
            return spotMeta;
        };
    }

    private void connectTheStream() {
        PipelineTestSupport.stepStream(source, now.get());
        assertThat(transport.attempts).isPositive();
    }

    private void deliver(String fixture) {
        transport.last().deliver(Fixtures.json("hyperliquid/" + fixture).toString());
    }

    // ---------------------------------------------------------------------- the feed

    @Test
    void oneFeedForTheSpotPairsOfHyperliquidAndNoOtherMarket() {
        assertThat(source.name()).as("the venue's name: Hyperliquid's pacing of history requests is shared").isEqualTo("hyperliquid");
        assertThat(source.feeds()).hasSize(1);
        assertThat(feed.id()).isEqualTo("hyperliquid:spot");
        assertThat(feed.staleMs()).isEqualTo(15_000L);
        assertThat(feed.maxGapMs()).isEqualTo(30_000L);
        assertThat(feed.maxBasis()).isEqualTo(0.0015);
        assertThat(feed.handles().test(hype)).isTrue();
        assertThat(feed.handles().test(TestMarkets.hlSpot("PURR", "PURR/USDC", 4, 10))).as("a pair named by its symbols").isTrue();
        assertThat(feed.handles().test(TestMarkets.perp("ETH", "ETH", "crypto", 25, 0, 1))).as("a perpetual").isFalse();
        assertThat(feed.handles().test(TestMarkets.perp("xyz_NVDA", "xyz:NVDA", "stocks", 20, 1, 2))).isFalse();
        assertThat(feed.handles().test(TestMarkets.coinbase("BTC", 0, 3))).as("another venue").isFalse();
        assertThat(source.historyKind()).isEqualTo(PriceSource.HistoryKind.TRADE_PRICES);
        assertThat(source.historyWeight(900_000L)).as("the same call, the same weight as a perpetual's")
                .isEqualTo(HyperliquidPriceSource.candleSnapshotWeight(900_000L)).isEqualTo(21);
    }

    @Test
    void theLimitsAreTheConfiguredOnes() {
        SpotPricesConfig config = SpotPricesConfig.defaults()
                .withHyperliquidSpot(new SpotPricesConfig.Limits(25_000, 50_000, 0.002, 9_000, 20_000));
        PriceSource.Feed f = new HyperliquidSpotPriceSource(() -> api, config, transport, now::get, Runnable::run, false)
                .feeds().get(0);

        assertThat(f.staleMs()).isEqualTo(25_000L);
        assertThat(f.maxGapMs()).isEqualTo(50_000L);
        assertThat(f.maxBasis()).isEqualTo(0.002);
    }

    @Test
    void aSpotReferenceIsAnAtPairOrASymbolPairAndAPerpetualIsNeither() {
        assertThat(HyperliquidPriceSource.isSpotRef("@107")).isTrue();
        assertThat(HyperliquidPriceSource.isSpotRef("PURR/USDC")).isTrue();
        assertThat(HyperliquidPriceSource.isSpotRef("BTC")).isFalse();
        assertThat(HyperliquidPriceSource.isSpotRef("xyz:NVDA")).isFalse();
    }

    // ---------------------------------------------------------------------- the pair of two tokens

    @Test
    void aPairIsFoundByTheNamesOfItsTokensInTheMeta() {
        assertThat(HyperliquidSpotPriceSource.pairName(spotMeta, "HYPE", "USDC")).contains("@107");
        assertThat(HyperliquidSpotPriceSource.pairName(spotMeta, "YAP", "USDC")).contains("@106");
        assertThat(HyperliquidSpotPriceSource.pairName(spotMeta, "PURR", "USDC")).contains("PURR/USDC");
        assertThat(HyperliquidSpotPriceSource.pairName(spotMeta.get(0), "HYPE", "USDC"))
                .as("the meta alone is enough, as spotMeta answers it").contains("@107");
    }

    @Test
    void noPairAndAmbiguousPairsAreNoName() {
        assertThat(HyperliquidSpotPriceSource.pairName(spotMeta, "HYPE", "PURR")).as("no such pair").isEmpty();
        assertThat(HyperliquidSpotPriceSource.pairName(spotMeta, "NOPE", "USDC")).as("no such token").isEmpty();
        assertThat(HyperliquidSpotPriceSource.pairName(spotMeta, "USDC", "HYPE")).as("the base comes first").isEmpty();
        JsonNode twice = Fixtures.parse("[{\"tokens\":[{\"name\":\"USDC\",\"index\":0},{\"name\":\"HYPE\",\"index\":5}],"
                + "\"universe\":[{\"tokens\":[5,0],\"name\":\"@1\"},{\"tokens\":[5,0],\"name\":\"@2\"}]},[]]");
        assertThat(HyperliquidSpotPriceSource.pairName(twice, "HYPE", "USDC")).as("two pairs of the same tokens").isEmpty();
        JsonNode twoTokens = Fixtures.parse("{\"tokens\":[{\"name\":\"USDC\",\"index\":0},{\"name\":\"HYPE\",\"index\":5},"
                + "{\"name\":\"HYPE\",\"index\":6}],\"universe\":[{\"tokens\":[5,0],\"name\":\"@1\"},"
                + "{\"tokens\":[6,0],\"name\":\"@2\"}]}");
        assertThat(HyperliquidSpotPriceSource.pairName(twoTokens, "HYPE", "USDC")).as("two tokens of one name").isEmpty();
        assertThat(HyperliquidSpotPriceSource.pairName(Fixtures.parse("[]"), "HYPE", "USDC")).isEmpty();
        assertThat(HyperliquidSpotPriceSource.pairName(null, "HYPE", "USDC")).isEmpty();
    }

    @Test
    void theContextsAreKeyedByTheCoinFieldAndPositionMeansNothing() {
        Map<String, JsonNode> byCoin = HyperliquidSpotPriceSource.contexts(spotMeta);

        assertThat(byCoin.get("@107").path("midPx").asText()).isEqualTo("87.8665");
        assertThat(byCoin.get("PURR/USDC").path("midPx").asText()).isEqualTo("0.15792");
        assertThat(byCoin).hasSize(108);

        JsonNode shuffled = Fixtures.parse("[{},[{\"coin\":\"@107\",\"midPx\":\"1\"},{\"coin\":\"@1\",\"midPx\":\"2\"},"
                + "{\"midPx\":\"3\"},{\"coin\":\"\",\"midPx\":\"4\"}]]");
        assertThat(HyperliquidSpotPriceSource.contexts(shuffled)).as("entries without a coin are left out")
                .containsOnlyKeys("@107", "@1");
    }

    @Test
    void anAnswerThatIsNotMetaAndContextsIsRefused() {
        for (String garbage : new String[] {"{\"error\":\"x\"}", "[]", "[{}]", "[{},{}]", "\"nope\"", "null"}) {
            assertThatThrownBy(() -> HyperliquidSpotPriceSource.contexts(Fixtures.parse(garbage)))
                    .as(garbage).isInstanceOf(IllegalStateException.class).hasMessageContaining("spotMetaAndAssetCtxs");
        }
        assertThat(HyperliquidSpotPriceSource.spotMetaAndAssetCtxsRequest().toString())
                .isEqualTo("{\"type\":\"spotMetaAndAssetCtxs\"}");
    }

    // ---------------------------------------------------------------------- REST: the same venue

    @Test
    void theFirstPollReadsTheContextsOverRestAndAsksForTheStream() throws Exception {
        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(hype));

        assertThat(quotes).containsOnlyKeys("spot_HYPE");
        assertThat(quotes.get("spot_HYPE").raw()).as("the midPx of the pair").isEqualByComparingTo("87.8665");
        assertThat(quotes.get("spot_HYPE").change24h()).as("(midPx - prevDayPx) / prevDayPx = (87.8665 - 91.336) / 91.336")
                .isCloseTo(-0.037985, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(api.requestsOfType("spotMetaAndAssetCtxs")).hasSize(1);
        connectTheStream();
        assertThat(transport.last().uri).isEqualTo(URI.create("wss://api.hyperliquid.xyz/ws"));
    }

    @Test
    void theRestAnswerIsReadByTheCoinFieldNotByPosition() throws Exception {
        // the pair sits SECOND in ctxs and its own index is 107: neither position gives its price
        api.info.handler = body -> Fixtures.parse("[{},[{\"coin\":\"@1\",\"midPx\":\"12.2365\",\"prevDayPx\":\"12.83\"},"
                + "{\"coin\":\"@107\",\"midPx\":\"87.8665\",\"prevDayPx\":\"91.336\"}]]");

        assertThat(source.poll(feed, List.of(hype)).get("spot_HYPE").raw()).isEqualByComparingTo("87.8665");
    }

    @Test
    void oneRestReadServesEveryPairWithoutAFreshValue() throws Exception {
        Market purr = TestMarkets.hlSpot("PURR", "PURR/USDC", 4, 10);

        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(hype, purr));

        assertThat(quotes).containsOnlyKeys("spot_HYPE", "spot_PURR");
        assertThat(quotes.get("spot_PURR").raw()).isEqualByComparingTo("0.15792");
        assertThat(api.requestsOfType("spotMetaAndAssetCtxs")).as("one answer, 305 kB and weight 20").hasSize(1);
    }

    @Test
    void aPairWithoutAMidPxGetsNoTickAndTheOthersAreUnharmed() throws Exception {
        Market empty = TestMarkets.hlSpot("EMPTY", "@2", 0, 11); // ctxs of @2: midPx null — nothing is quoted there
        Market missing = TestMarkets.hlSpot("GONE", "@9999", 0, 12);

        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(hype, empty, missing));

        assertThat(quotes).containsOnlyKeys("spot_HYPE");
    }

    @Test
    void aPollFailsOnlyWhenNoPairGotAPrice() {
        api.info.handler = body -> {
            throw new IllegalStateException("HTTP 429");
        };

        assertThatThrownBy(() -> source.poll(feed, List.of(hype))).isInstanceOf(IOException.class)
                .hasMessageContaining("no Hyperliquid spot price for any market");
    }

    @Test
    void aRestReadThatFailsCostsOnlyThePairsThatHadNoFreshValue() throws Exception {
        Market purr = TestMarkets.hlSpot("PURR", "PURR/USDC", 4, 10);
        source.poll(feed, List.of(hype, purr));
        connectTheStream();
        deliver("ws_all_mids.json"); // @107 is in it, PURR/USDC too
        api.info.handler = body -> {
            throw new IllegalStateException("HTTP 429");
        };
        now.addAndGet(1_000);
        Market newer = TestMarkets.hlSpot("YAP", "@106", 4, 11); // no value yet, and the read fails

        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(hype, purr, newer));

        assertThat(quotes).containsOnlyKeys("spot_HYPE", "spot_PURR");
    }

    // ---------------------------------------------------------------------- the stream

    @Test
    void aPollIsAnsweredFromTheNewestAllMidsMessageAndTheOtherKeysOfItAreNeverUsed() throws Exception {
        source.poll(feed, List.of(hype));
        connectTheStream();
        assertThat(transport.last().sent).containsExactly(
                "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"allMids\"}}");
        deliver("ws_subscription_response_all_mids.json");
        deliver("ws_all_mids.json"); // "@107": 87.8835 — and "HYPE": 87.8645, the perpetual's mid, which is another market
        api.info.requests.clear();
        now.addAndGet(1_000);

        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(hype));

        assertThat(quotes.get("spot_HYPE").raw()).isEqualByComparingTo("87.8835");
        assertThat(api.info.requests).as("no request at all: the value is 1 s old").isEmpty();
        assertThat(quotes.get("spot_HYPE").change24h()).as("the prevDayPx of the first REST read: (87.8835 - 91.336) / 91.336")
                .isCloseTo(-0.037800, org.assertj.core.data.Offset.offset(1e-5));
    }

    @Test
    void aValueIsFreshForEightSecondsAndThenTheContextsAreReadAgain() throws Exception {
        source.poll(feed, List.of(hype));
        connectTheStream();
        deliver("ws_all_mids.json");
        api.info.requests.clear();

        now.set(T0 + 8_000); // exactly the limit: allMids comes every 5 s
        assertThat(source.poll(feed, List.of(hype)).get("spot_HYPE").raw()).isEqualByComparingTo("87.8835");
        assertThat(api.info.requests).isEmpty();

        now.set(T0 + 8_001);
        Map<String, PriceSource.Quote> past = source.poll(feed, List.of(hype));

        assertThat(api.requestsOfType("spotMetaAndAssetCtxs")).hasSize(1);
        assertThat(past.get("spot_HYPE").raw()).as("the REST midPx").isEqualByComparingTo("87.8665");
    }

    @Test
    void aMessageWithoutTheWantedPairLeavesThePairToRest() throws Exception {
        source.poll(feed, List.of(hype));
        connectTheStream();
        transport.last().deliver("{\"channel\":\"allMids\",\"data\":{\"mids\":{\"BTC\":\"84599.5\",\"@1\":\"12.2\"}}}");
        api.info.requests.clear();
        now.addAndGet(500);

        assertThat(source.poll(feed, List.of(hype)).get("spot_HYPE").raw()).isEqualByComparingTo("87.8665");
        assertThat(api.requestsOfType("spotMetaAndAssetCtxs")).hasSize(1);
    }

    @Test
    void aMidThatIsNoPriceDoesNotReplaceTheGoodOne() throws Exception {
        source.poll(feed, List.of(hype));
        connectTheStream();
        deliver("ws_all_mids.json");
        for (String garbage : new String[] {"\"0\"", "\"-1\"", "\"x\"", "null", "\"\""}) {
            transport.last().deliver("{\"channel\":\"allMids\",\"data\":{\"mids\":{\"@107\":" + garbage + "}}}");
        }
        api.info.requests.clear();
        now.addAndGet(500);

        assertThat(source.poll(feed, List.of(hype)).get("spot_HYPE").raw()).isEqualByComparingTo("87.8835");
        assertThat(api.info.requests).isEmpty();
    }

    @Test
    void messagesThatAreNoAllMidsAreNotPricesAndNeverFailAnything() throws Exception {
        source.poll(feed, List.of(hype));
        connectTheStream();

        deliver("ws_subscription_response_all_mids.json");
        transport.last().deliver("{\"channel\":\"pong\"}");
        transport.last().deliver("{\"channel\":\"error\",\"data\":\"Already subscribed\"}");
        transport.last().deliver("{\"channel\":\"something-new\",\"data\":{}}");
        transport.last().deliver("this is not json");
        transport.last().deliver("{\"channel\":\"allMids\"}");
        api.info.requests.clear();
        now.addAndGet(500);

        assertThat(source.poll(feed, List.of(hype)).get("spot_HYPE").raw()).as("the REST read").isEqualByComparingTo("87.8665");
    }

    @Test
    void thePingGoesOutEveryThirtySecondsOfAConnectionThatIsHeard() throws Exception {
        source.poll(feed, List.of(hype));
        connectTheStream();
        deliver("ws_all_mids.json");

        now.set(T0 + 29_000);
        deliver("ws_all_mids.json");
        PipelineTestSupport.stepStream(source, now.get());
        assertThat(transport.last().sent).as("only the subscription so far").hasSize(1);

        now.set(T0 + 30_000);
        deliver("ws_all_mids.json");
        PipelineTestSupport.stepStream(source, now.get());

        assertThat(transport.last().sent).containsExactly(
                "{\"method\":\"subscribe\",\"subscription\":{\"type\":\"allMids\"}}", "{\"method\":\"ping\"}");
    }

    @Test
    void aSilentStreamIsDroppedAndTheRestReadsTakeOver() throws Exception {
        source.poll(feed, List.of(hype));
        connectTheStream();
        deliver("ws_all_mids.json");
        api.info.requests.clear();

        now.set(T0 + 20_000); // 20 s of nothing: past the 15 s of silence this stream is allowed
        PipelineTestSupport.stepStream(source, now.get());
        Map<String, PriceSource.Quote> quotes = source.poll(feed, List.of(hype));

        assertThat(transport.last().aborted).isTrue();
        assertThat(quotes.get("spot_HYPE").raw()).isEqualByComparingTo("87.8665");
        assertThat(api.requestsOfType("spotMetaAndAssetCtxs")).hasSize(1);
    }

    @Test
    void theReferencePriceIsReadEveryFiveMinutesInTheBackgroundWhileTheStreamIsHealthy() throws Exception {
        source.poll(feed, List.of(hype));
        connectTheStream();
        deliver("ws_all_mids.json");
        api.info.requests.clear();
        // a reference price that moved since the first read, to see that the periodic read is what updates it
        api.info.handler = body -> Fixtures.parse("[{},[{\"coin\":\"@107\",\"midPx\":\"1\",\"prevDayPx\":\"100\"}]]");

        now.set(T0 + 4 * 60_000L);
        deliver("ws_all_mids.json");
        assertThat(source.poll(feed, List.of(hype)).get("spot_HYPE").change24h()).as("still the first read's reference")
                .isCloseTo(-0.037800, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(api.info.requests).as("not due yet: the last read was at T0").isEmpty();

        now.set(T0 + 5 * 60_000L);
        deliver("ws_all_mids.json");
        source.poll(feed, List.of(hype)); // due: read on the executor (here the calling thread)
        assertThat(api.requestsOfType("spotMetaAndAssetCtxs")).hasSize(1);
        now.addAndGet(1_000);
        deliver("ws_all_mids.json");
        PriceSource.Quote after = source.poll(feed, List.of(hype)).get("spot_HYPE");

        assertThat(after.raw()).as("the tick is still the stream's, not the REST read's").isEqualByComparingTo("87.8835");
        assertThat(after.change24h()).as("(87.8835 - 100) / 100 from the new reference").isCloseTo(-0.121165,
                org.assertj.core.data.Offset.offset(1e-5));
        assertThat(api.requestsOfType("spotMetaAndAssetCtxs")).as("the next one is five minutes away").hasSize(1);
    }

    @Test
    void aFailedReferenceReadIsNoFailureOfThePoll() throws Exception {
        source.poll(feed, List.of(hype));
        connectTheStream();
        api.info.handler = body -> {
            throw new IllegalStateException("HTTP 429");
        };
        now.set(T0 + 5 * 60_000L);
        deliver("ws_all_mids.json");

        assertThat(source.poll(feed, List.of(hype)).get("spot_HYPE").raw()).isEqualByComparingTo("87.8835");
    }

    // ---------------------------------------------------------------------- history

    @Test
    void theHistoryIsTheCandleSnapshotOfThePairWithTheBoundaryConventionOfTheVenue() {
        now.set(LATER);
        long from = 1_791_026_100_000L; // 11:15:00
        long to = 1_791_026_400_000L;   // 11:20:00

        List<PricePoint> points = source.history(hype, from, to);

        JsonNode request = api.requestsOfType("candleSnapshot").get(0);
        assertThat(request.path("req").path("coin").asText()).as("the pair's name, not the symbol").isEqualTo("@107");
        assertThat(request.path("req").path("interval").asText()).isEqualTo("1m");
        assertThat(request.path("req").path("startTime").asLong()).as("30 minutes before the range").isEqualTo(from - 1_800_000L);
        assertThat(request.path("req").path("endTime").asLong()).as("the candle that starts at `to` is not wanted").isEqualTo(to - 1);
        assertThat(points).isNotEmpty().isSortedAccordingTo((a, b) -> Long.compare(a.atMs(), b.atMs()));
        assertThat(points).allMatch(p -> p.atMs() >= from && p.atMs() <= to);
        // the 11:17 candle (o 87.878, h 87.919, l 87.871, c 87.872) closed down: high first, then low, then the close
        assertThat(points).contains(new PricePoint(1_791_026_240_000L, new BigDecimal("87.919")),
                new PricePoint(1_791_026_260_000L, new BigDecimal("87.871")),
                new PricePoint(1_791_026_279_999L, new BigDecimal("87.872")));
    }

    @Test
    void theHistoryOfARoundBoundaryIsTheCloseOfTheCandleBeforeIt() {
        now.set(LATER);
        long boundary = 1_791_026_280_000L; // 11:18:00

        PriceSource.History history = source.historyOf(hype, 1_791_026_100_000L, 1_791_026_400_000L, boundary);

        assertThat(history.points()).contains(new PricePoint(boundary - 1, new BigDecimal("87.872")));
        assertThat(history.points()).as("no second price at the boundary: the close a millisecond before it is the price")
                .noneMatch(p -> p.atMs() == boundary);
        assertThat(history.bars()).as("the candles with trades, for a bridge over a gap").hasSize(12);
        assertThat(history.lastTrade()).as("a trade before the range: the 11:14 candle's close")
                .contains(new PricePoint(1_791_026_099_999L, new BigDecimal("87.847")));
    }

    @Test
    void aCandleThatIsStillOpenOrHasNoTradesIsNoPrice() {
        now.set(1_791_026_160_000L + 30_000L); // 11:16:30 — the 11:16 candle is open
        long from = 1_791_026_040_000L;

        PriceSource.History history = source.historyOf(hype, from, 1_791_026_400_000L);

        assertThat(history.points()).as("the candle of the minute in progress says nothing yet")
                .noneMatch(p -> p.atMs() >= 1_791_026_160_000L);
        assertThat(history.bars()).noneMatch(b -> b.startMs() >= 1_791_026_160_000L);
        PriceSource.History fillers = HyperliquidPriceSource.historyAnswer(Fixtures.parse("["
                + "{\"t\":1791026100000,\"T\":1791026159999,\"o\":\"87.8\",\"h\":\"87.8\",\"l\":\"87.8\",\"c\":\"87.8\",\"n\":0}]"),
                1_791_026_040_000L, 1_791_026_400_000L, LATER);
        assertThat(fillers.points()).as("a filler row (n = 0) repeats the previous close: not a price").isEmpty();
        assertThat(fillers.bars()).isEmpty();
    }

    @Test
    void aHistoryRequestThatFailsFailsTheCall() {
        api.info.handler = body -> {
            throw new IllegalStateException("HTTP 500");
        };

        assertThatThrownBy(() -> source.history(hype, T0 - 600_000L, T0)).isInstanceOf(IllegalStateException.class);
    }
}
