package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

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
    void badAnswersGiveNoQuotesNotExceptions() {
        List<Market> markets = List.of(coin("BTC"));

        assertThat(HyperliquidPriceSource.parse(null, markets)).isEmpty();
        assertThat(HyperliquidPriceSource.parse(Fixtures.parse("{}"), markets)).isEmpty();
        assertThat(HyperliquidPriceSource.parse(Fixtures.parse("[]"), markets)).isEmpty();
        // a markPx that is garbage or zero costs that market its tick only
        JsonNode broken = Fixtures.parse("[{\"universe\":[{\"name\":\"BTC\"},{\"name\":\"ETH\"}]},"
                + "[{\"markPx\":\"oops\"},{\"markPx\":\"0\"}]]");
        assertThat(HyperliquidPriceSource.parse(broken, List.of(coin("BTC"), coin("ETH")))).isEmpty();
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
    void eachCandleGivesFourPointsOpenAtTExtremesInsideCloseAtBigT() {
        JsonNode rows = Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json"); // t = 17:59, 18:00, 18:01
        long t1759 = 1_790_877_540_000L;

        List<PricePoint> points = HyperliquidPriceSource.candlePoints(rows, 0, Long.MAX_VALUE);

        assertThat(points).hasSize(12);
        // row 0: o 84780, h 84889, l 84779, c 84871 -> closed up: o, then low, then high, then c
        assertThat(points.subList(0, 4)).extracting(PricePoint::atMs)
                .containsExactly(t1759, t1759 + 20_000, t1759 + 40_000, t1759 + 59_999);
        assertThat(points.subList(0, 4)).extracting(p -> p.raw().intValue())
                .containsExactly(84780, 84779, 84889, 84871);
        // row 1 (18:00) closed down: o 84871, h 84883, l 84743, c 84776 -> o, high, low, c
        assertThat(points.subList(4, 8)).extracting(p -> p.raw().intValue())
                .containsExactly(84871, 84883, 84743, 84776);
        // no two points further apart than the 30 s maximum gap: every 20 s, and 19.999 s at the seams
        for (int i = 1; i < points.size(); i++) {
            assertThat(points.get(i).atMs() - points.get(i - 1).atMs()).isLessThanOrEqualTo(30_000);
        }
    }

    @Test
    void rowsAreReadByTheirOwnTimeNotByPositionAndOnlyPointsInsideTheRangeAreKept() {
        JsonNode rows = Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json"); // 17:59, 18:00, 18:01
        long t1800 = 1_790_877_600_000L;

        // the window of the old bug: [at - 60 s, at + 60 s]; response.get(0) was the 17:59 candle
        List<PricePoint> points = HyperliquidPriceSource.candlePoints(rows, t1800, t1800 + 59_999);

        assertThat(points).extracting(PricePoint::atMs).containsExactly(t1800, t1800 + 20_000, t1800 + 40_000,
                t1800 + 59_999);
        assertThat(points.get(0).raw()).isEqualByComparingTo("84871"); // the 18:00 candle's open, not the 17:59 one's 84780
    }

    @Test
    void aFlatFillerCandleAndGarbageRowsDoNoHarm() {
        JsonNode rows = Fixtures.parse("[{\"t\":60000,\"T\":119999,\"o\":\"5.0\",\"h\":\"5.0\",\"l\":\"5.0\",\"c\":\"5.0\","
                + "\"v\":\"0.0\",\"n\":0},{\"t\":\"oops\"},{\"t\":120000,\"o\":\"x\",\"h\":\"1\",\"l\":\"1\",\"c\":\"1\"}]");

        List<PricePoint> points = HyperliquidPriceSource.candlePoints(rows, 0, Long.MAX_VALUE);

        assertThat(points).hasSize(4).allSatisfy(p -> assertThat(p.raw()).isEqualByComparingTo("5.0"));
        assertThat(HyperliquidPriceSource.candlePoints(null, 0, 1)).isEmpty();
        assertThat(HyperliquidPriceSource.candlePoints(Fixtures.parse("{}"), 0, 1)).isEmpty();
    }

    // ---------------------------------------------------------------- a quiet asset's empty tail

    private static final long T_1800 = 1_790_877_600_000L; // 18:00:00 UTC, the second row of the BTC fixture
    private static final long LAST_CLOSE_AT = 1_790_877_719_999L; // the last fixture row (18:01) closes here, at 84792

    @Test
    void anEmptyTailOfAFinalPeriodIsCarriedForwardFromTheLastCloseEveryTenSeconds() {
        JsonNode rows = Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json");
        long to = T_1800 + 600_000L; // 18:10:00: eight minutes without a candle after the last row

        List<PricePoint> points = HyperliquidPriceSource.historyPoints(rows, T_1800, to, to + 300_000L);

        List<PricePoint> carried = points.stream().filter(p -> p.atMs() > LAST_CLOSE_AT).toList();
        assertThat(carried).hasSize(48);
        assertThat(carried.get(0).atMs()).isEqualTo(LAST_CLOSE_AT + 10_000L);
        assertThat(carried.get(carried.size() - 1).atMs()).isEqualTo(to - 1); // aligned boundaries get a point 1 ms before
        assertThat(carried).allSatisfy(p -> assertThat(p.raw()).isEqualByComparingTo("84792"));
        for (int i = 1; i < points.size(); i++) { // no stretch longer than the 20 s of a candle's own points
            assertThat(points.get(i).atMs() - points.get(i - 1).atMs()).isLessThanOrEqualTo(20_000);
        }
    }

    @Test
    void aTailYoungerThanTwoMinutesIsNotCarriedItsCandleMayStillAppear() {
        JsonNode rows = Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json");
        long to = T_1800 + 600_000L;

        assertThat(HyperliquidPriceSource.historyPoints(rows, T_1800, to, to + 119_999L)).hasSize(8); // candle points only
        assertThat(HyperliquidPriceSource.historyPoints(rows, T_1800, to, to + 120_000L).size()).isGreaterThan(8);
    }

    @Test
    void nothingIsCarriedWhenThereIsNothingToCarryOrNothingMissing() {
        long to = T_1800 + 600_000L;
        JsonNode rows = Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json");

        assertThat(HyperliquidPriceSource.historyPoints(Fixtures.parse("[]"), T_1800, to, to + 600_000L)).isEmpty();
        assertThat(HyperliquidPriceSource.historyPoints(null, T_1800, to, to + 600_000L)).isEmpty();
        // the last row reaches the end of the range: no tail
        assertThat(HyperliquidPriceSource.historyPoints(rows, T_1800, LAST_CLOSE_AT, LAST_CLOSE_AT + 600_000L)).hasSize(8);
        assertThat(HyperliquidPriceSource.historyPoints(rows, T_1800, LAST_CLOSE_AT + 5_000L, LAST_CLOSE_AT + 600_000L))
                .hasSize(8); // closes within 10 s of the end: not worth a point
    }

    @Test
    void aRangeAfterTheLastRowIsCarriedFromItsOwnStart() {
        JsonNode rows = Fixtures.json("hyperliquid/candle_snapshot_btc_1m.json");
        long from = T_1800 + 300_000L; // 18:05:00, three minutes after the last candle
        long to = from + 60_000L;

        List<PricePoint> points = HyperliquidPriceSource.historyPoints(rows, from, to, to + 600_000L);

        assertThat(points).extracting(PricePoint::atMs).startsWith(from, from + 10_000L).endsWith(to - 10_000L, to);
        assertThat(points).hasSize(7);
        assertThat(points).allSatisfy(p -> assertThat(p.raw()).isEqualByComparingTo("84792"));
    }

    @Test
    void aRowWithoutAUsableCloseIsNotTheOneCarried() {
        // the newest row by time has no usable close: the newest usable one is carried
        JsonNode rows = Fixtures.parse("[{\"t\":60000,\"T\":119999,\"o\":\"5\",\"h\":\"6\",\"l\":\"4\",\"c\":\"5.5\"},"
                + "{\"t\":120000,\"T\":179999,\"o\":\"5\",\"h\":\"6\",\"l\":\"4\",\"c\":\"oops\"}]");

        List<PricePoint> points = HyperliquidPriceSource.historyPoints(rows, 60_000L, 300_000L, 900_000L);

        assertThat(points.get(points.size() - 1).raw()).isEqualByComparingTo("5.5");
        assertThat(points.get(points.size() - 1).atMs()).isEqualTo(299_999L);
    }

    @Test
    void historyAsksCandleSnapshotForTheMarketsCoin() throws Exception {
        FakeApis.Hyperliquid api = new FakeApis.Hyperliquid();
        api.info.handler = body -> Fixtures.json("hyperliquid/candle_snapshot_xyz_tsla_1m.json");
        HyperliquidPriceSource source = new HyperliquidPriceSource(() -> api, PricesConfig.defaults(), () -> NOW);
        Market tsla = TestMarkets.perp("xyz_TSLA", "xyz:TSLA", Category.STOCKS, 20, 3, 6);

        List<PricePoint> points = source.history(tsla, 1_790_883_480_000L, 1_790_883_839_999L);

        assertThat(points).hasSize(24); // six candles
        assertThat(api.info.requests).hasSize(1);
        JsonNode req = api.info.requests.get(0);
        assertThat(req.get("type").asText()).isEqualTo("candleSnapshot");
        assertThat(req.get("req").get("coin").asText()).isEqualTo("xyz:TSLA");
        assertThat(req.get("req").get("interval").asText()).isEqualTo("1m");
        assertThat(req.get("req").get("startTime").asLong()).isEqualTo(1_790_883_480_000L - 30 * 60_000L); // looks back 30 minutes
        assertThat(req.get("req").get("endTime").asLong()).isEqualTo(1_790_883_839_999L);
        assertThat(points.get(0).raw()).isEqualByComparingTo(new BigDecimal("357.63"));
    }
}
