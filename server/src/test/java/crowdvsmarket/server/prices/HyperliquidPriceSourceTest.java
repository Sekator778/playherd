package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hyperliquid parsing against the answers of 2026-10-01 (trimmed samples, fixtures/hyperliquid/):
 * prices by coin name never by index, delisted assets skipped, the xyz dex, the 24-hour change, the
 * candle rows as history, and the request shapes.
 */
class HyperliquidPriceSourceTest {

    /** 2026-10-01T19:45:00Z, a couple of minutes after the TSLA candles of the fixtures. */
    private static final long NOW = 1_790_883_900_000L;

    private final JsonNode main = Fixtures.json("hyperliquid/meta_and_asset_ctxs_main.json");
    private final JsonNode xyz = Fixtures.json("hyperliquid/meta_and_asset_ctxs_xyz.json");

    private static Market coin(String coin) {
        return TestMarkets.perp(coin.replace(':', '_'), coin, Category.CRYPTO, 20, 0, 1);
    }

    @Test
    void readsMarkPxByCoinNameNotByPosition() {
        // in the fixture ETH comes first and BTC third; the live answer has BTC at 0
        assertThat(main.get(0).get("universe").get(0).get("name").asText()).isEqualTo("ETH");
        assertThat(main.get(0).get("universe").get(2).get("name").asText()).isEqualTo("BTC");

        Map<String, PriceSource.Quote> quotes = HyperliquidPriceSource.parse(main,
                List.of(coin("BTC"), coin("ETH"), coin("SOL"), coin("DOGE"), coin("XRP"), coin("HYPE"), coin("PUMP")));

        assertThat(quotes.get("BTC").raw()).isEqualByComparingTo("84726.0");
        assertThat(quotes.get("ETH").raw()).isEqualByComparingTo("2697.0");
        assertThat(quotes.get("SOL").raw()).isEqualByComparingTo("117.84");
        assertThat(quotes.get("DOGE").raw()).isEqualByComparingTo("0.094677");
        assertThat(quotes.get("XRP").raw()).isEqualByComparingTo("1.4971");
        assertThat(quotes.get("HYPE").raw()).isEqualByComparingTo("87.138");
        assertThat(quotes.get("PUMP").raw()).isEqualByComparingTo("0.005737");
    }

    @Test
    void change24hIsMarkOverPrevDayPriceMinusOne() {
        Map<String, PriceSource.Quote> quotes = HyperliquidPriceSource.parse(main, List.of(coin("BTC"), coin("DOGE")));

        // BTC 84726.0 vs prevDayPx 83739.0
        assertThat(quotes.get("BTC").change24h()).isEqualTo(0.01178662, org.assertj.core.data.Offset.offset(1e-8));
        // DOGE 0.094677 vs 0.094
        assertThat(quotes.get("DOGE").change24h()).isEqualTo(0.00720213, org.assertj.core.data.Offset.offset(1e-8));
    }

    @Test
    void aDelistedAssetIsSkippedEvenWhenItIsListed() {
        // MATIC and MKR are isDelisted in the answer; their markPx is frozen
        Map<String, PriceSource.Quote> quotes = HyperliquidPriceSource.parse(main,
                List.of(coin("BTC"), coin("MATIC"), coin("MKR")));

        assertThat(quotes).containsOnlyKeys("BTC");
    }

    @Test
    void aCoinThatIsNotInTheAnswerIsSimplyMissing() {
        Map<String, PriceSource.Quote> quotes = HyperliquidPriceSource.parse(main, List.of(coin("BTC"), coin("NOPE")));

        assertThat(quotes).containsOnlyKeys("BTC");
    }

    @Test
    void readsTheXyzDexByItsPrefixedNames() {
        Market nvda = TestMarkets.perp("xyz_NVDA", "xyz:NVDA", Category.STOCKS, 20, 3, 5);
        Market tsla = TestMarkets.perp("xyz_TSLA", "xyz:TSLA", Category.STOCKS, 20, 3, 6);
        Market uranium = TestMarkets.perp("xyz_URANIUM", "xyz:URANIUM", Category.COMMODITIES, 10, 3, 7);

        Map<String, PriceSource.Quote> quotes = HyperliquidPriceSource.parse(xyz, List.of(nvda, tsla, uranium));

        assertThat(quotes).containsOnlyKeys("xyz_NVDA", "xyz_TSLA"); // URANIUM is delisted
        assertThat(quotes.get("xyz_NVDA").raw()).isEqualByComparingTo("231.7");
        assertThat(quotes.get("xyz_TSLA").raw()).isEqualByComparingTo("357.74");
    }

    @Test
    void aMarkPxThatIsGarbageOrZeroCostsThatMarketItsTickOnly() {
        JsonNode broken = Fixtures.parse("[{\"universe\":[{\"name\":\"BTC\"},{\"name\":\"ETH\"},{\"name\":\"SOL\"}]},"
                + "[{\"markPx\":\"oops\"},{\"markPx\":\"0\"},{\"markPx\":\"117.84\"}]]");

        Map<String, PriceSource.Quote> quotes = HyperliquidPriceSource.parse(broken,
                List.of(coin("BTC"), coin("ETH"), coin("SOL")));

        assertThat(quotes).containsOnlyKeys("SOL");
    }

    @Test
    void anAnswerThatIsNotMetaAndCtxsFailsTheReadLoudly() {
        // R4: HTTP 200 with an error object or a changed wrapper used to be an empty, "successful" poll
        List<Market> markets = List.of(coin("BTC"));
        for (String bad : List.of("{}", "[]", "[{\"universe\":[]}]", "{\"error\":\"rate limited\"}", "\"oops\"", "null",
                "[{\"universe\":{}},[]]", "[{\"universe\":[{\"name\":\"BTC\"}]},{}]",
                "[{\"universe\":[{\"name\":\"BTC\"},{\"name\":\"ETH\"}]},[{\"markPx\":\"1\"}]]")) {
            assertThatThrownBy(() -> HyperliquidPriceSource.parse(Fixtures.parse(bad), markets)).as(bad)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("metaAndAssetCtxs");
        }
        assertThatThrownBy(() -> HyperliquidPriceSource.parse(null, markets)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMalformedAnswerFailsThePollSoTheFeedServiceCanReportIt() {
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        HyperliquidPriceSource source = new HyperliquidPriceSource(() -> api, PricesConfig.defaults(), () -> NOW);
        PriceSource.Feed mainFeed = source.feeds().get(0);

        api.info.handler = body -> Fixtures.parse("{\"status\":\"err\",\"response\":\"Too many requests\"}");
        assertThatThrownBy(() -> source.poll(mainFeed, List.of(coin("BTC")))).isInstanceOf(IllegalStateException.class);

        api.info.handler = body -> main; // and a good answer afterwards polls normally
        assertThat(source.poll(mainFeed, List.of(coin("BTC")))).containsOnlyKeys("BTC");
    }

    @Test
    void aMalformedAnswerStoresNoTickAndTheFeedServiceSurvivesIt() {
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> Fixtures.parse("{\"status\":\"err\"}");
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(NOW);
        TickStore store = Fixtures.tickStore(now::get);
        PriceFeedService feeds = PipelineTestSupport.feedService(List.of(PipelineTestSupport.hyperliquid(api, now::get)),
                store, new crowdvsmarket.server.markets.catalog.FakeMarketRegistry(), now::get);

        PipelineTestSupport.poll(feeds, "hyperliquid"); // must not throw: it is a throttled warning of the feed service

        assertThat(store.last("BTC")).isEmpty();
        assertThat(api.info.requests).hasSize(1);
    }

    @Test
    void aConfiguredCoinMissingFromAValidAnswerGetsAThrottledWarningOfItsOwn() throws Exception {
        RecordingLogger log = new RecordingLogger();
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(NOW);
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> main;
        HyperliquidPriceSource source = new HyperliquidPriceSource(() -> api, PricesConfig.defaults(),
                new HyperliquidLiveState(), now::get, new ThrottledLog(log, now::get, 60_000L));
        PriceSource.Feed mainFeed = source.feeds().get(0);
        List<Market> markets = List.of(coin("BTC"), coin("RENAMED"));

        Map<String, PriceSource.Quote> first = source.poll(mainFeed, markets);
        now.addAndGet(3_000);
        source.poll(mainFeed, markets); // 3 s later: the same warning is not repeated
        now.addAndGet(3_000);
        source.poll(mainFeed, markets);

        assertThat(first).containsOnlyKeys("BTC"); // the other market only misses its tick
        assertThat(log.warnings()).hasSize(1);
        assertThat(log.warnings().get(0)).contains("RENAMED").contains("does not list");
        assertThat(log.warnings().get(0)).doesNotContain("BTC");

        now.addAndGet(60_000); // a minute later it is said again, with the count of the ones skipped
        source.poll(mainFeed, markets);
        assertThat(log.warnings()).hasSize(2);
        assertThat(log.warnings().get(1)).contains("2 similar");

        // once the coin is back the recovery is logged
        api.info.handler = body -> Fixtures.parse(main.toString().replace("\"name\":\"SOL\"", "\"name\":\"RENAMED\""));
        source.poll(mainFeed, markets);
        assertThat(log.infos()).anyMatch(m -> m.contains("recovered") && m.contains("RENAMED"));
    }

    @Test
    void aDelistedAssetIsReportedToTheLiveStateAndALiveOneClearsTheReport() throws Exception {
        HyperliquidLiveState state = new HyperliquidLiveState();
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(NOW);
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> main;
        HyperliquidPriceSource source = new HyperliquidPriceSource(() -> api, PricesConfig.defaults(), state, now::get);
        PriceSource.Feed mainFeed = source.feeds().get(0);
        List<Market> markets = List.of(coin("BTC"), coin("MATIC"), coin("MKR"), coin("NOPE"));

        source.poll(mainFeed, markets);
        now.addAndGet(3_000);
        source.poll(mainFeed, markets);

        // MATIC and MKR are isDelisted in the answer: reported, with the time of the FIRST such answer
        assertThat(state.delistedSince("MATIC")).hasValue(NOW);
        assertThat(state.delistedSince("MKR")).hasValue(NOW);
        assertThat(state.delistedSince("BTC")).isEmpty();
        assertThat(state.delistedSince("NOPE")).isEmpty(); // not in the universe at all: missing, not delisted

        // an answer that lists MATIC as a live asset stops the clock
        api.info.handler = body -> Fixtures.parse(main.toString().replace("\"isDelisted\":true", "\"isDelisted\":false"));
        source.poll(mainFeed, markets);
        assertThat(state.delistedSince("MATIC")).isEmpty();
    }

    @Test
    void pollsTheDexOfItsFeedAndNothingElse() throws Exception {
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> body.has("dex") ? xyz : main;
        HyperliquidPriceSource source = new HyperliquidPriceSource(() -> api, PricesConfig.defaults(), () -> NOW);
        PriceSource.Feed mainFeed = source.feeds().get(0);
        PriceSource.Feed xyzFeed = source.feeds().get(1);

        Map<String, PriceSource.Quote> fromMain = source.poll(mainFeed, List.of(coin("BTC")));
        Map<String, PriceSource.Quote> fromXyz = source.poll(xyzFeed, List.of(coin("xyz:NVDA")));

        assertThat(fromMain).containsOnlyKeys("BTC");
        assertThat(fromXyz).containsOnlyKeys("xyz_NVDA");
        assertThat(api.info.requests).hasSize(2);
        assertThat(api.info.requests.get(0).toString()).isEqualTo("{\"type\":\"metaAndAssetCtxs\"}");
        assertThat(api.info.requests.get(1).toString()).isEqualTo("{\"type\":\"metaAndAssetCtxs\",\"dex\":\"xyz\"}");
    }

    @Test
    void feedsCarryTheArchitecturesLimitsAndHandleTheirOwnDexOnly() {
        HyperliquidPriceSource source = new HyperliquidPriceSource(() -> {
            throw new AssertionError("no request expected");
        }, PricesConfig.defaults(), () -> NOW);
        PriceSource.Feed mainFeed = source.feeds().get(0);
        PriceSource.Feed xyzFeed = source.feeds().get(1);

        assertThat(mainFeed.id()).isEqualTo("hyperliquid");
        assertThat(mainFeed.staleMs()).isEqualTo(15_000);
        assertThat(mainFeed.maxGapMs()).isEqualTo(30_000);
        assertThat(xyzFeed.id()).isEqualTo("hyperliquid:xyz");
        assertThat(xyzFeed.staleMs()).isEqualTo(20_000);
        assertThat(xyzFeed.maxGapMs()).isEqualTo(30_000);

        Market nvda = TestMarkets.perp("xyz_NVDA", "xyz:NVDA", Category.STOCKS, 20, 3, 5);
        assertThat(mainFeed.handles().test(TestMarkets.btc())).isTrue();
        assertThat(mainFeed.handles().test(nvda)).isFalse();
        assertThat(xyzFeed.handles().test(nvda)).isTrue();
        assertThat(xyzFeed.handles().test(TestMarkets.btc())).isFalse();
        assertThat(mainFeed.handles().test(TestMarkets.odds("PM1", "123"))).isFalse(); // not a hyperliquid market
        assertThat(HyperliquidPriceSource.dexOf("xyz:NVDA")).isEqualTo("xyz");
        assertThat(HyperliquidPriceSource.dexOf("BTC")).isEmpty();
    }

    @Test
    void theCandleSnapshotRequestHasTheDocumentedShape() {
        assertThat(HyperliquidPriceSource.candleSnapshotRequest("xyz:TSLA", 1_790_883_480_000L, 1_790_883_840_000L).toString())
                .isEqualTo("{\"type\":\"candleSnapshot\",\"req\":{\"coin\":\"xyz:TSLA\",\"interval\":\"1m\","
                        + "\"startTime\":1790883480000,\"endTime\":1790883840000}}");
    }

    // -------------------------------------------------------------------------------------- history

    @Test
    void aCandleGivesItsTwoExtremesInsideTheMinuteAndItsCloseAtBigTAndOnlyTheFirstRowItsOpen() {
        JsonNode rows = Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json"); // t = 17:59, 18:00, 18:01
        long t1759 = 1_790_877_540_000L;

        List<PricePoint> points = HyperliquidPriceSource.candlePoints(rows, 0, Long.MAX_VALUE);

        assertThat(points).hasSize(10); // 4 for the first row, 3 for each of the two that have a predecessor
        // row 0: o 84780, h 84889, l 84779, c 84871 -> closed up: o, then low, then high, then c
        assertThat(points.subList(0, 4)).extracting(PricePoint::atMs)
                .containsExactly(t1759, t1759 + 20_000, t1759 + 40_000, t1759 + 59_999);
        assertThat(points.subList(0, 4)).extracting(p -> p.raw().intValue())
                .containsExactly(84780, 84779, 84889, 84871);
        // row 1 (18:00) closed down: o 84871, h 84883, l 84743, c 84776 -> high, low, c; its open IS the 17:59 close
        assertThat(points.subList(4, 7)).extracting(p -> p.raw().intValue())
                .containsExactly(84883, 84743, 84776);
        // row 2 (18:01) closed up: o 84777, h 84792, l 84736, c 84792 -> low, high, c
        assertThat(points.subList(7, 10)).extracting(p -> p.raw().intValue())
                .containsExactly(84736, 84792, 84792);
        // no two points further apart than the 30 s maximum gap: every 20 s, and 20.001 s at the seams
        for (int i = 1; i < points.size(); i++) {
            assertThat(points.get(i).atMs() - points.get(i - 1).atMs()).isLessThanOrEqualTo(30_000);
        }
    }

    @Test
    void anOpenIsTheFirstTradeAfterTItIsNotThePriceAtTSoItNeverReplacesThePreviousCandlesClose() {
        // R3: the price at t is the last trade at or before t. 18:00's open 84871 is the first trade of the
        // minute; the price at 18:00:00 is the 17:59 candle's close, one millisecond earlier. The BTC sample
        // has them equal, so a pair that differs is made up: the next minute opens 5 higher than this one closed.
        JsonNode rows = Fixtures.parse("[{\"t\":60000,\"T\":119999,\"o\":\"100\",\"h\":\"101\",\"l\":\"99\",\"c\":\"100\"},"
                + "{\"t\":120000,\"T\":179999,\"o\":\"105\",\"h\":\"106\",\"l\":\"104\",\"c\":\"105\"}]");

        List<PricePoint> points = HyperliquidPriceSource.candlePoints(rows, 0, Long.MAX_VALUE);

        // the last point at or before the boundary 120000 is the 100 that closed the first minute
        PricePoint atBoundary = points.stream().filter(p -> p.atMs() <= 120_000).reduce((a, b) -> b).orElseThrow();
        assertThat(atBoundary.atMs()).isEqualTo(119_999);
        assertThat(atBoundary.raw()).isEqualByComparingTo("100");
        assertThat(points).extracting(PricePoint::atMs).doesNotContain(120_000L);
    }

    @Test
    void theCandleThatStartsAtTheEndOfTheRangeContributesNothing() {
        JsonNode rows = Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json"); // 17:59, 18:00, 18:01
        long t1801 = 1_790_877_660_000L;

        // the round ends at 18:01:00; an answer that still has the candle starting there is not used from
        List<PricePoint> points = HyperliquidPriceSource.candlePoints(rows, 0, t1801);

        assertThat(points.get(points.size() - 1).atMs()).isEqualTo(t1801 - 1);
        assertThat(points.get(points.size() - 1).raw()).isEqualByComparingTo("84776"); // the 18:00 candle's close
        assertThat(points).hasSize(7);

        // even when it is the only row there is: its open is a trade at or after the end
        List<PricePoint> alone = HyperliquidPriceSource.candlePoints(Fixtures.parse(
                "[{\"t\":" + t1801 + ",\"T\":" + (t1801 + 59_999) + ",\"o\":\"84777\",\"h\":\"84792\",\"l\":\"84736\","
                        + "\"c\":\"84792\"}]"), 0, t1801);
        assertThat(alone).isEmpty();
    }

    @Test
    void rowsAreReadByTheirOwnTimeNotByPositionAndOnlyPointsInsideTheRangeAreKept() {
        JsonNode rows = Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json"); // 17:59, 18:00, 18:01
        long t1800 = 1_790_877_600_000L;

        // the window of the old bug: [at - 60 s, at + 60 s]; response.get(0) was the 17:59 candle
        List<PricePoint> points = HyperliquidPriceSource.candlePoints(rows, t1800, t1800 + 59_999);

        assertThat(points).extracting(PricePoint::atMs).containsExactly(t1800 + 20_000, t1800 + 40_000, t1800 + 59_999);
        // the 18:00 candle's own numbers (it closed down: high, low, close), not the 17:59 one's 84889 / 84779 / 84871
        assertThat(points).extracting(p -> p.raw().intValue()).containsExactly(84883, 84743, 84776);
    }

    @Test
    void rowsInAnyOrderGiveTheSameAsRowsInOrder() {
        JsonNode rows = Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json");
        com.fasterxml.jackson.databind.node.ArrayNode reversed = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .arrayNode();
        for (int i = rows.size() - 1; i >= 0; i--) {
            reversed.add(rows.get(i));
        }

        assertThat(HyperliquidPriceSource.candlePoints(reversed, 0, Long.MAX_VALUE))
                .isEqualTo(HyperliquidPriceSource.candlePoints(rows, 0, Long.MAX_VALUE));
    }

    @Test
    void aFillerCandleHoldsNoPriceAndGarbageRowsDoNoHarm() {
        String filler = "{\"t\":60000,\"T\":119999,\"o\":\"5.0\",\"h\":\"5.0\",\"l\":\"5.0\",\"c\":\"5.0\","
                + "\"v\":\"0.0\",\"n\":0}";
        String garbage = "{\"t\":\"oops\"},{\"t\":120000,\"o\":\"x\",\"h\":\"1\",\"l\":\"1\",\"c\":\"1\"}";

        // Hyperliquid fills a minute without trades with o = h = l = c = the previous close (§4.3): no trade, no price
        assertThat(HyperliquidPriceSource.candlePoints(Fixtures.parse("[" + filler + "," + garbage + "]"), 0,
                Long.MAX_VALUE)).isEmpty();
        // the same row with trades, and a row that does not say how many (older answers) count as candles
        String traded = filler.replace("\"n\":0", "\"n\":3");
        String unnumbered = filler.replace(",\"n\":0", "");
        assertThat(HyperliquidPriceSource.candlePoints(Fixtures.parse("[" + traded + "," + garbage + "]"), 0,
                Long.MAX_VALUE)).hasSize(4).allSatisfy(p -> assertThat(p.raw()).isEqualByComparingTo("5.0"));
        assertThat(HyperliquidPriceSource.candlePoints(Fixtures.parse("[" + unnumbered + "]"), 0, Long.MAX_VALUE))
                .hasSize(4);
        assertThat(HyperliquidPriceSource.candlePoints(null, 0, 1)).isEmpty();
        assertThat(HyperliquidPriceSource.candlePoints(Fixtures.parse("{}"), 0, 1)).isEmpty();
    }

    // ------------------------------------------- trade prices: what history is, and what it is never

    private static final long T_1800 = 1_790_877_600_000L; // 18:00:00 UTC, the second row of the BTC fixture
    private static final long LAST_CLOSE_AT = 1_790_877_719_999L; // the last fixture row (18:01) closes here, at 84792

    @Test
    void historyIsDeclaredTradePricesAndWeighsWhatTheSeederPlansWith() {
        HyperliquidPriceSource source = new HyperliquidPriceSource(() -> {
            throw new AssertionError("no request expected");
        }, PricesConfig.defaults(), () -> NOW);

        assertThat(source.historyKind()).isEqualTo(PriceSource.HistoryKind.TRADE_PRICES);
        // 20 plus 1 per 60 rows, rounded up; the request looks back 30 minutes more than the span
        assertThat(source.historyWeight(900_000L)).isEqualTo(21); // the stand: 15 + 30 = 45 rows
        assertThat(source.historyWeight(86_400_000L)).isEqualTo(45); // production: 1440 + 30 = 1470 rows -> 25
        assertThat(source.historyWeight(0)).isEqualTo(21); // the 30 minutes alone: 30 rows
    }

    @Test
    void theSourceNeverCarriesACandlesCloseForwardAcrossAStretchWithNoCandle() {
        JsonNode rows = Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json");
        long to = T_1800 + 600_000L; // 18:10:00: eight minutes without a candle after the last row

        // it used to put the 84792 of 18:01 every 10 s into this stretch, a trade price between the marks of a
        // feed that was down: a flat mark settled as DOWN on it
        PriceSource.History answer = HyperliquidPriceSource.historyAnswer(rows, T_1800 + 300_000L, to, to + 600_000L);

        assertThat(answer.points()).isEmpty();
        assertThat(HyperliquidPriceSource.historyAnswer(rows, T_1800, to, to + 600_000L).points()).hasSize(6) // the candles' own
                .allSatisfy(p -> assertThat(p.atMs()).isLessThanOrEqualTo(LAST_CLOSE_AT));
    }

    @Test
    void theLastTradeBeforeTheRangeIsTheCloseOfTheNewestCandleWithTradesBeforeIt() {
        JsonNode rows = Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json"); // 17:59, 18:00, 18:01
        long from = T_1800 + 300_000L;

        PriceSource.History quiet = HyperliquidPriceSource.historyAnswer(rows, from, from + 60_000L, from + 120_000L);

        assertThat(quiet.lastTrade()).hasValueSatisfying(p -> {
            assertThat(p.atMs()).isEqualTo(LAST_CLOSE_AT); // at the time it closed
            assertThat(p.raw()).isEqualByComparingTo("84792");
        });
        // a candle that closes inside the range is not "before" it: it is a point of the answer
        assertThat(HyperliquidPriceSource.historyAnswer(rows, T_1800 + 100_000L, T_1800 + 200_000L, NOW + 600_000L)
                .lastTrade()).hasValueSatisfying(p -> assertThat(p.atMs()).isEqualTo(T_1800 + 59_999L));
        assertThat(HyperliquidPriceSource.historyAnswer(Fixtures.parse("[]"), from, from + 1_000L, from + 120_000L)
                .lastTrade()).isEmpty();
    }

    @Test
    void aFillerIsNotALastTradeAndNeitherIsACandleStillOpen() {
        // 18:00 traded (n=5) at 100; 18:01 and 18:02 are fillers at 100; 18:03 is still open (T is after now) at 101
        JsonNode rows = Fixtures.parse("[{\"t\":" + T_1800 + ",\"T\":" + (T_1800 + 59_999) + ",\"o\":\"100\",\"h\":\"100\","
                + "\"l\":\"100\",\"c\":\"100\",\"n\":5},"
                + "{\"t\":" + (T_1800 + 60_000) + ",\"T\":" + (T_1800 + 119_999) + ",\"o\":\"100\",\"h\":\"100\",\"l\":\"100\","
                + "\"c\":\"100\",\"n\":0},"
                + "{\"t\":" + (T_1800 + 120_000) + ",\"T\":" + (T_1800 + 179_999) + ",\"o\":\"100\",\"h\":\"100\",\"l\":\"100\","
                + "\"c\":\"100\",\"n\":0},"
                + "{\"t\":" + (T_1800 + 180_000) + ",\"T\":" + (T_1800 + 239_999) + ",\"o\":\"101\",\"h\":\"101\",\"l\":\"101\","
                + "\"c\":\"101\",\"n\":2}]");
        long now = T_1800 + 200_000L; // 18:03:20: the last candle closes at 18:03:59.999

        PriceSource.History answer = HyperliquidPriceSource.historyAnswer(rows, T_1800 + 250_000L, T_1800 + 600_000L, now);

        assertThat(answer.points()).isEmpty();
        assertThat(answer.lastTrade()).hasValueSatisfying(p -> {
            assertThat(p.atMs()).isEqualTo(T_1800 + 59_999L); // 18:00, not a filler's 100 at 18:02, not the open 101
            assertThat(p.raw()).isEqualByComparingTo("100");
        });
        assertThat(HyperliquidPriceSource.historyAnswer(rows, T_1800, T_1800 + 600_000L, now).points())
                .extracting(PricePoint::atMs).doesNotContain(T_1800 + 239_999L, T_1800 + 180_000L + 20_000L);
    }

    // -------------------------------------------- the price at a boundary: the candle before it

    /** One candle per minute from t = 60 000: minute k closes at 100 + k, its low is 90 + k, its high 110 + k. */
    private static JsonNode minuteCandles(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int k = 1; k <= count; k++) {
            long t = k * 60_000L;
            sb.append(k > 1 ? "," : "").append("{\"t\":").append(t).append(",\"T\":").append(t + 59_999)
                    .append(",\"o\":\"").append(100 + k - 1).append("\",\"h\":\"").append(110 + k).append("\",\"l\":\"")
                    .append(90 + k).append("\",\"c\":\"").append(100 + k).append("\",\"n\":4}");
        }
        return Fixtures.parse(sb.append("]").toString());
    }

    @Test
    void aBoundaryInsideAMinuteGetsTheCloseOfTheCandleBeforeItNotTheLowOrHighOfTheOneAroundIt() {
        JsonNode rows = minuteCandles(6); // 18:01..18:06 as minutes 1..6
        long now = 10 * 60_000L;

        // the boundaries of a round that is not aligned: +17 s, +30 s, +50 s into minute 3 (t = 180 000)
        for (long offset : new long[] {17_000L, 30_000L, 50_000L}) {
            long boundary = 180_000L + offset;
            PriceSource.History answer = HyperliquidPriceSource.historyAnswer(rows, boundary - 60_000L, boundary + 60_000L,
                    now, boundary);

            PricePoint at = answer.points().stream().filter(p -> p.atMs() == boundary).findFirst().orElseThrow();
            // minute 2 closed at 102: that is the last trade before minute 3 began. Minute 3's own shape points
            // (low 93 at +20 s, high 113 at +40 s) are wicks at invented times, never the price at the boundary.
            assertThat(at.raw()).as("price at +" + offset).isEqualByComparingTo("102");
        }
    }

    @Test
    void aMinuteAlignedBoundaryIsPricedByTheCloseOneMillisecondBeforeItWithItsOwnTime() {
        JsonNode rows = minuteCandles(6);

        PriceSource.History answer = HyperliquidPriceSource.historyAnswer(rows, 120_000L, 300_000L, 10 * 60_000L,
                180_000L, 300_000L);

        // minute 2 (t = 120 000) closed at 179 999 at 102, minute 4 at 299 999 at 104: those points ARE the prices at the
        // boundaries, one millisecond old, and nothing is made up at the boundaries themselves
        assertThat(answer.points()).extracting(PricePoint::atMs).contains(179_999L, 299_999L)
                .doesNotContain(180_000L, 300_000L);
        assertThat(answer.points().stream().filter(p -> p.atMs() == 179_999L).findFirst().orElseThrow().raw())
                .isEqualByComparingTo("102");
        assertThat(answer.points().stream().filter(p -> p.atMs() == 299_999L).findFirst().orElseThrow().raw())
                .isEqualByComparingTo("104");
    }

    @Test
    void noBoundaryPointWhenTheLastCloseIsOlderThanOneCandleOrTheBoundaryIsOutsideTheRange() {
        // minute 1 and minute 2 traded, then nothing: the boundary at 5 minutes has no trade within a minute before it
        JsonNode rows = minuteCandles(2);
        long now = 10 * 60_000L;

        assertThat(HyperliquidPriceSource.historyAnswer(rows, 200_000L, 400_000L, now, 300_000L).points()).isEmpty();
        // inside the range, the candle closed at 179 999: 1 s later is a price, 61.001 s later is too old for one
        assertThat(HyperliquidPriceSource.historyAnswer(rows, 100_000L, 400_000L, now, 180_999L).points())
                .extracting(PricePoint::atMs).contains(180_999L);
        assertThat(HyperliquidPriceSource.historyAnswer(rows, 100_000L, 400_000L, now, 241_000L).points())
                .extracting(PricePoint::atMs).doesNotContain(241_000L);
        // a boundary outside [from, to] is not asked for
        assertThat(HyperliquidPriceSource.historyAnswer(minuteCandles(6), 200_000L, 250_000L, now, 120_000L, 300_000L)
                .points()).extracting(PricePoint::atMs).doesNotContain(120_000L, 300_000L);
    }

    @Test
    void historyOfPassesTheBoundariesToTheAnswerAndTheRequestStillStopsBeforeTheEnd() throws Exception {
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> minuteCandles(6);
        HyperliquidPriceSource source = new HyperliquidPriceSource(() -> api, PricesConfig.defaults(), () -> 10 * 60_000L);
        Market btc = TestMarkets.btc();

        PriceSource.History answer = source.historyOf(btc, 150_000L, 330_000L, 210_000L);

        assertThat(answer.points().stream().filter(p -> p.atMs() == 210_000L).findFirst().orElseThrow().raw())
                .isEqualByComparingTo("102");
        assertThat(api.info.requests.get(0).get("req").get("endTime").asLong()).isEqualTo(330_000L - 1);
    }

    // ------------------------------------------------------ the bars a bridge over a gap is made of

    @Test
    void theAnswerCarriesEveryClosedCandleWithTradesAsABarNotOnlyTheOnesInsideTheRange() {
        JsonNode rows = minuteCandles(6); // minutes 1..6, each n = 4
        long now = 10 * 60_000L;

        // the range is minute 4 only: the bars are all six minutes of the answer, the left neighbour's candle among them
        PriceSource.History answer = HyperliquidPriceSource.historyAnswer(rows, 4 * 60_000L, 5 * 60_000L, now);

        assertThat(answer.bars()).extracting(PriceSource.Bar::startMs).containsExactly(60_000L, 120_000L, 180_000L,
                240_000L, 300_000L, 360_000L);
        PriceSource.Bar third = answer.bars().get(2); // minute 3: o 102, h 113, l 93, c 103
        assertThat(third.endMs()).isEqualTo(239_999L);
        assertThat(third.open()).isEqualByComparingTo("102");
        assertThat(third.high()).isEqualByComparingTo("113");
        assertThat(third.low()).isEqualByComparingTo("93");
        assertThat(third.close()).isEqualByComparingTo("103");
        // a source that answers a plain history() is no bar source
        assertThat(PriceSource.History.of(List.of()).bars()).isEmpty();
        assertThat(new PriceSource.History(List.of(), java.util.Optional.empty()).bars()).isEmpty();
    }

    @Test
    void aFillerACandleStillOpenAndAGarbageRowAreNoBars() {
        String filler = "{\"t\":60000,\"T\":119999,\"o\":\"5\",\"h\":\"5\",\"l\":\"5\",\"c\":\"5\",\"n\":0}";
        String traded = "{\"t\":120000,\"T\":179999,\"o\":\"5\",\"h\":\"6\",\"l\":\"4\",\"c\":\"5\",\"n\":2}";
        String open = "{\"t\":180000,\"T\":239999,\"o\":\"5\",\"h\":\"6\",\"l\":\"4\",\"c\":\"5\",\"n\":9}";
        String garbage = "{\"t\":240000,\"o\":\"x\"}";

        PriceSource.History answer = HyperliquidPriceSource.historyAnswer(
                Fixtures.parse("[" + open + "," + traded + "," + garbage + "," + filler + "]"), 0, 400_000L, 200_000L);

        assertThat(answer.bars()).extracting(PriceSource.Bar::startMs).containsExactly(120_000L); // oldest first, only it
    }

    @Test
    void aCandleThatSaysItEndsBeforeItStartsIsStillAMinuteLong() {
        PriceSource.History answer = HyperliquidPriceSource.historyAnswer(Fixtures.parse(
                "[{\"t\":120000,\"T\":5,\"o\":\"5\",\"h\":\"6\",\"l\":\"4\",\"c\":\"5\",\"n\":2}]"), 0, 400_000L,
                400_000L);

        assertThat(answer.bars()).hasSize(1);
        assertThat(answer.bars().get(0).widthMs()).isEqualTo(60_000L);
    }

    @Test
    void theCandlePointsAreTheShapePointsOfItsBarSoASeedAndABridgePlaceThemAlike() {
        JsonNode rows = minuteCandles(3);
        long now = 10 * 60_000L;

        PriceSource.History answer = HyperliquidPriceSource.historyAnswer(rows, 120_000L, 179_999L, now);

        // minute 2 (t = 120 000) in the range: the points of the answer are exactly the bar's
        PriceSource.Bar bar = answer.bars().get(1);
        assertThat(bar.startMs()).isEqualTo(120_000L);
        assertThat(answer.points()).isEqualTo(bar.shapePoints());
    }

    // ---------------------------------------------------------- the candle in progress is not seeded

    @Test
    void aCandleThatIsStillOpenGivesNoPointsItsHighIsNotAFreshPrice() {
        // minute 3 is in progress at now = 3 min 30 s: T = 239 999 is still in the future
        JsonNode rows = minuteCandles(3);
        long now = 180_000L + 30_000L;

        List<PricePoint> points = HyperliquidPriceSource.historyAnswer(rows, 0, now, now).points();

        assertThat(points).extracting(PricePoint::atMs).allSatisfy(at -> assertThat(at).isLessThan(180_000L + 59_999L));
        assertThat(points).extracting(p -> p.raw().intValue()).doesNotContain(113, 93, 103); // minute 3: h, l, c
        assertThat(points.get(points.size() - 1).atMs()).isEqualTo(179_999L); // the last closed candle's close
    }

    @Test
    void historyAsksCandleSnapshotForTheMarketsCoin() throws Exception {
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> Fixtures.json("hyperliquid/candle_snapshot_xyz_tsla_1m.json");
        HyperliquidPriceSource source = new HyperliquidPriceSource(() -> api, PricesConfig.defaults(), () -> NOW);
        Market tsla = TestMarkets.perp("xyz_TSLA", "xyz:TSLA", Category.STOCKS, 20, 3, 6);

        List<PricePoint> points = source.history(tsla, 1_790_883_480_000L, 1_790_883_839_999L);

        assertThat(points).hasSize(19); // six candles: the first with its open, the five that follow without
        assertThat(api.info.requests).hasSize(1);
        JsonNode req = api.info.requests.get(0);
        assertThat(req.get("type").asText()).isEqualTo("candleSnapshot");
        assertThat(req.get("req").get("coin").asText()).isEqualTo("xyz:TSLA");
        assertThat(req.get("req").get("interval").asText()).isEqualTo("1m");
        assertThat(req.get("req").get("startTime").asLong()).isEqualTo(1_790_883_480_000L - 30 * 60_000L); // looks back 30 minutes
        assertThat(req.get("req").get("endTime").asLong()).isEqualTo(1_790_883_839_999L - 1); // the candle at the end is not asked for
        assertThat(points.get(0).raw()).isEqualByComparingTo(new BigDecimal("357.63"));
    }
}
