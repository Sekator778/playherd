package crowdvsmarket.server.markets.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.prices.FakeApis;
import crowdvsmarket.server.prices.Fixtures;
import crowdvsmarket.server.prices.HistorySeeder;
import crowdvsmarket.server.prices.HyperliquidLiveState;
import crowdvsmarket.server.prices.PipelineTestSupport;
import crowdvsmarket.server.prices.TickStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Hyperliquid catalog against the meta answers of 2026-10-01 (fixtures/hyperliquid/): what it
 * lists with which parameters, what it skips and never asks for again, and what it retries.
 */
class HyperliquidCatalogTest {

    private static final long NOW = Fixtures.NOW;

    private final JsonNode main = Fixtures.json("hyperliquid/meta_and_asset_ctxs_main.json");
    private final JsonNode xyz = Fixtures.json("hyperliquid/meta_and_asset_ctxs_xyz.json");

    private FakeMarketRegistry registry;
    private FakeApis.Hyperliquid api;

    @BeforeEach
    void setUp() {
        registry = new FakeMarketRegistry();
        api = new FakeApis.Hyperliquid();
        api.info.handler = body -> body.has("dex") ? xyz : main;
    }

    private HyperliquidCatalog catalog(CatalogConfig config) {
        return new HyperliquidCatalog(registry, config, () -> api, () -> NOW);
    }

    private static CatalogConfig config(List<String> crypto, List<String> stocks, List<String> indices,
                                        List<String> commodities) {
        return new CatalogConfig(true, false, crypto, stocks, indices, commodities, true, 3, 3, 10, true);
    }

    private Market market(String id) {
        return registry.byId(id).orElseThrow(() -> new AssertionError("not listed: " + id));
    }

    @Test
    void listsEveryDefaultNameTheRegistryDoesNotKnow() {
        int pending = catalog(CatalogConfig.defaults()).sync(NOW);

        assertThat(pending).isZero();
        assertThat(registry.log).containsExactlyInAnyOrder("listed ETH", "listed SOL", "listed XRP", "listed DOGE",
                "listed HYPE", "listed xyz_NVDA", "listed xyz_TSLA", "listed xyz_SP500", "listed xyz_XYZ100",
                "listed xyz_GOLD", "listed xyz_BRENTOIL");
        // BTC was in the registry from the start (V7 migration) and is not listed a second time
        assertThat(registry.log).doesNotContain("listed BTC");
        assertThat(market("BTC").oracleIndex()).isZero();
    }

    @Test
    void aListedMarketCarriesTheParametersOfItsAssetAtListingTime() {
        catalog(CatalogConfig.defaults()).sync(NOW);

        Market eth = market("ETH");
        assertThat(eth.kind()).isEqualTo(MarketKind.PERP);
        assertThat(eth.category()).isEqualTo(Category.CRYPTO);
        assertThat(eth.symbol()).isEqualTo("ETH");
        assertThat(eth.title()).isEqualTo("Ethereum");
        assertThat(eth.subtitle()).isNull();
        assertThat(eth.source()).isEqualTo("hyperliquid");
        assertThat(eth.sourceRef()).isEqualTo("ETH");
        assertThat(eth.maxLeverage()).isEqualTo(25); // min(40, 25)
        assertThat(eth.lotExp()).isZero(); // markPx 2697.0
        assertThat(eth.listedAtMs()).isEqualTo(NOW);
        assertThat(eth.endsAtMs()).isNull();
        assertThat(eth.isActive()).isTrue();
        assertThat(Fixtures.parse(eth.sourceMeta()).get("dex").asText()).isEmpty();
        assertThat(Fixtures.parse(eth.sourceMeta()).get("szDecimals").asInt()).isEqualTo(4);
        assertThat(Fixtures.parse(eth.sourceMeta()).get("marginTableId").asInt()).isEqualTo(55);

        assertThat(market("SOL").maxLeverage()).isEqualTo(20);
        assertThat(market("SOL").lotExp()).isEqualTo(1); // 117.84 -> lot price 1178.4
        assertThat(market("XRP").lotExp()).isEqualTo(3); // 1.4971 -> 1497.1
        assertThat(market("DOGE").maxLeverage()).isEqualTo(10);
        assertThat(market("DOGE").lotExp()).isEqualTo(5); // 0.094677 -> 9467.7
        assertThat(market("DOGE").title()).isEqualTo("Dogecoin");
        assertThat(market("HYPE").title()).isEqualTo("Hyperliquid");
        assertThat(market("HYPE").lotExp()).isEqualTo(2); // 87.138
    }

    @Test
    void xyzNamesBecomeIdsWithAnUnderscoreAndSymbolsWithoutThePrefix() {
        catalog(CatalogConfig.defaults()).sync(NOW);

        Market nvda = market("xyz_NVDA");
        assertThat(nvda.category()).isEqualTo(Category.STOCKS);
        assertThat(nvda.symbol()).isEqualTo("NVDA");
        assertThat(nvda.title()).isEqualTo("NVIDIA");
        assertThat(nvda.sourceRef()).isEqualTo("xyz:NVDA");
        assertThat(nvda.maxLeverage()).isEqualTo(20);
        assertThat(nvda.lotExp()).isEqualTo(1); // 231.7 -> 2317.0
        assertThat(Fixtures.parse(nvda.sourceMeta()).get("dex").asText()).isEqualTo("xyz");
        assertThat(Fixtures.parse(nvda.sourceMeta()).get("szDecimals").asInt()).isEqualTo(3);
        assertThat(Fixtures.parse(nvda.sourceMeta()).get("marginTableId").asInt()).isEqualTo(20);

        assertThat(market("xyz_TSLA").title()).isEqualTo("Tesla");
        assertThat(market("xyz_SP500").category()).isEqualTo(Category.INDICES);
        assertThat(market("xyz_SP500").title()).isEqualTo("S&P 500");
        assertThat(market("xyz_XYZ100").title()).isEqualTo("Nasdaq 100");
        assertThat(market("xyz_GOLD").category()).isEqualTo(Category.COMMODITIES);
        assertThat(market("xyz_GOLD").title()).isEqualTo("Gold");
        assertThat(market("xyz_BRENTOIL").title()).isEqualTo("Brent Oil");
    }

    @Test
    void theLeverageIsCappedAtFortyAndALotExponentNeverGoesBelowZero() {
        catalog(CatalogConfig.defaults()).sync(NOW);

        assertThat(market("xyz_SP500").maxLeverage()).isEqualTo(40); // the dex allows 50x
        assertThat(market("xyz_SP500").lotExp()).isZero(); // 7676.3
        assertThat(market("xyz_XYZ100").maxLeverage()).isEqualTo(30);
        assertThat(market("xyz_XYZ100").lotExp()).isZero(); // 30557.0 is above the 1000..10000 band: clamped to 0
    }

    @Test
    void oneRequestPerDexNotPerMarket() {
        catalog(CatalogConfig.defaults()).sync(NOW);

        assertThat(api.info.requests).hasSize(2);
        assertThat(api.info.requests.get(0).toString()).isEqualTo("{\"type\":\"metaAndAssetCtxs\"}");
        assertThat(api.info.requests.get(1).toString()).isEqualTo("{\"type\":\"metaAndAssetCtxs\",\"dex\":\"xyz\"}");
    }

    @Test
    void onceEverythingIsListedARunCostsNoRequest() {
        HyperliquidCatalog catalog = catalog(CatalogConfig.defaults());
        catalog.sync(NOW);
        api.info.requests.clear();

        assertThat(catalog.sync(NOW + 60_000)).isZero();

        assertThat(api.info.requests).isEmpty();
    }

    @Test
    void anUnknownOrDelistedNameIsSkippedAndNeverAskedForAgain() {
        HyperliquidCatalog catalog = catalog(config(List.of("BTC", "NOPE", "MATIC"), List.of("xyz:URANIUM"),
                List.of(), List.of()));

        assertThat(catalog.sync(NOW)).isZero(); // nothing pending: both are known bad

        assertThat(registry.log).isEmpty();
        api.info.requests.clear();
        assertThat(catalog.sync(NOW + 60_000)).isZero();
        assertThat(api.info.requests).isEmpty(); // no request for names that cannot be listed
    }

    @Test
    void aDexTheSourceDoesNotPollIsSkippedWithoutAskingIt() {
        assertThat(catalog(config(List.of("para:AVGO"), List.of(), List.of(), List.of())).sync(NOW)).isZero();

        assertThat(api.info.requests).isEmpty();
        assertThat(registry.log).isEmpty();
    }

    @Test
    void aNameThatCannotBeAMarketIdIsSkipped() {
        assertThat(catalog(config(List.of("BAD-NAME", "WAY_TOO_LONG_" + "X".repeat(30)), List.of(), List.of(), List.of()))
                .sync(NOW)).isZero();

        assertThat(registry.log).isEmpty();
    }

    @Test
    void anUnreachableSourceLeavesTheNamesPendingAndTheNextRunListsThem() {
        HyperliquidCatalog catalog = catalog(config(List.of("BTC", "ETH", "SOL"), List.of("xyz:NVDA"), List.of(), List.of()));
        api.info.handler = body -> {
            throw new IllegalStateException("connection refused");
        };

        assertThat(catalog.sync(NOW)).isEqualTo(3); // ETH and SOL on the main dex, NVDA on xyz
        assertThat(registry.log).isEmpty();

        api.info.handler = body -> body.has("dex") ? xyz : main;
        assertThat(catalog.sync(NOW + 60_000)).isZero();
        assertThat(registry.log).containsExactlyInAnyOrder("listed ETH", "listed SOL", "listed xyz_NVDA");
        assertThat(market("ETH").listedAtMs()).isEqualTo(NOW + 60_000);
    }

    @Test
    void oneDexBeingDownDoesNotStopTheOther() {
        HyperliquidCatalog catalog = catalog(config(List.of("ETH"), List.of("xyz:NVDA"), List.of(), List.of()));
        api.info.handler = body -> {
            if (body.has("dex")) {
                throw new IllegalStateException("xyz is down");
            }
            return main;
        };

        assertThat(catalog.sync(NOW)).isEqualTo(1);
        assertThat(registry.log).containsExactly("listed ETH");
    }

    @Test
    void aMetaAnswerWhoseListsDoNotLineUpListsNothingAndIsTriedAgain() {
        // universe[i] and ctxs[i] are the same asset only while the lists have the same length: here SOL would be
        // priced with the markPx of whatever stands at its position, so the answer is refused like the poll refuses it
        JsonNode skewed = Fixtures.parse("[{\"universe\":[{\"name\":\"ETH\",\"szDecimals\":4,\"maxLeverage\":25},"
                + "{\"name\":\"SOL\",\"szDecimals\":2,\"maxLeverage\":20}]},[{\"markPx\":\"2697.0\"}]]");
        api.info.handler = body -> skewed;
        HyperliquidCatalog catalog = catalog(config(List.of("ETH", "SOL"), List.of(), List.of(), List.of()));

        assertThat(catalog.sync(NOW)).isEqualTo(2); // both wait for the next run
        assertThat(registry.log).isEmpty();

        api.info.handler = body -> main;
        assertThat(catalog.sync(NOW + 60_000)).isZero();
        assertThat(registry.log).containsExactlyInAnyOrder("listed ETH", "listed SOL");
    }

    @Test
    void anAssetWithoutAUsablePriceYetIsRetriedNotMarkedBad() {
        JsonNode noPrice = Fixtures.parse("[{\"universe\":[{\"name\":\"ETH\",\"szDecimals\":4,\"maxLeverage\":25,"
                + "\"marginTableId\":55}]},[{\"markPx\":null}]]");
        api.info.handler = body -> noPrice;
        HyperliquidCatalog catalog = catalog(config(List.of("ETH"), List.of(), List.of(), List.of()));

        assertThat(catalog.sync(NOW)).isEqualTo(1);
        assertThat(registry.log).isEmpty();

        api.info.handler = body -> main;
        assertThat(catalog.sync(NOW + 60_000)).isZero();
        assertThat(registry.log).containsExactly("listed ETH");
    }

    @Test
    void anAssetThatAllowsLessThanTwoTimesLeverageCannotBeAMarket() {
        JsonNode oneX = Fixtures.parse("[{\"universe\":[{\"name\":\"ETH\",\"szDecimals\":4,\"maxLeverage\":1}]},"
                + "[{\"markPx\":\"2697.0\"}]]");
        api.info.handler = body -> oneX;

        assertThat(catalog(config(List.of("ETH"), List.of(), List.of(), List.of())).sync(NOW)).isZero();
        assertThat(registry.log).isEmpty();
    }

    @Test
    void aNameInTwoListsIsListedOnceInTheFirstCategory() {
        catalog(config(List.of("ETH"), List.of("ETH"), List.of(), List.of())).sync(NOW);

        assertThat(registry.log).containsExactly("listed ETH");
        assertThat(market("ETH").category()).isEqualTo(Category.CRYPTO);
    }

    @Test
    void theScheduledRunDoesNothingOutsideLiveMode() {
        CatalogConfig replay = new CatalogConfig(false, false, CatalogConfig.parseNames(CatalogConfig.DEFAULT_CRYPTO),
                List.of(), List.of(), List.of(), true, 3, 3, 10, true);

        catalog(replay).run();

        assertThat(api.info.requests).isEmpty();
        assertThat(registry.log).isEmpty();
    }

    @Test
    void theScheduledRunListsInLiveMode() {
        catalog(config(List.of("ETH"), List.of(), List.of(), List.of())).run();

        assertThat(registry.log).containsExactly("listed ETH");
    }

    // ------------------------------------------------------------- a delisted asset closes its market (G2)

    private HyperliquidCatalog catalog(HyperliquidLiveState state) {
        return new HyperliquidCatalog(registry, CatalogConfig.defaults(), () -> api, state, HistorySeeder.disabled(),
                () -> NOW);
    }

    private Market listedEth() {
        catalog(CatalogConfig.defaults()).sync(NOW);
        return market("ETH");
    }

    @Test
    void anAssetTheDexHasReportedDelistedForTenSecondsClosesItsMarketInPlainWords() {
        HyperliquidLiveState state = new HyperliquidLiveState();
        listedEth();
        HyperliquidCatalog catalog = catalog(state);
        state.reportDelisted("ETH", NOW);

        assertThat(catalog.watch(NOW + 9_999)).isZero(); // one odd answer must not close a market
        assertThat(market("ETH").isActive()).isTrue();
        assertThat(catalog.watch(NOW + 10_000)).isEqualTo(1);

        assertThat(market("ETH").isActive()).isFalse();
        assertThat(registry.closeReasons).containsOnlyKeys("ETH");
        // shown to the players as it stands, and after "market closed: " in the refund of their bets
        assertThat(registry.closeReasons.get("ETH")).isEqualTo("The asset was delisted at the source");
        assertThat(registry.closeReasons.get("ETH")).isEqualTo(HyperliquidCatalog.REASON_DELISTED);
        assertThat(state.delistedSince("ETH")).isEmpty(); // forgotten with the market
        assertThat(api.info.requests).hasSize(2); // listing only: the watch asks nobody
    }

    @Test
    void anAnswerThatListsTheAssetAgainRestartsTheClock() {
        HyperliquidLiveState state = new HyperliquidLiveState();
        listedEth();
        HyperliquidCatalog catalog = catalog(state);
        state.reportDelisted("ETH", NOW);
        state.reportListed("ETH");
        state.reportDelisted("ETH", NOW + 8_000);

        assertThat(catalog.watch(NOW + 12_000)).isZero();
        assertThat(catalog.watch(NOW + 18_000)).isEqualTo(1);
    }

    @Test
    void bitcoinIsNeverClosedAndOnlyHyperliquidMarketsAreWatched() {
        HyperliquidLiveState state = new HyperliquidLiveState();
        registry.add(new Market("PM1", MarketKind.ODDS, Category.POLITICS, "PM", "Will it?", "Yes", null, "polymarket",
                "tok", null, 10, 4, 7, crowdvsmarket.server.markets.MarketStatus.ACTIVE, 0L, null, null, null));
        state.reportDelisted("BTC", NOW - 3_600_000L);
        state.reportDelisted("PM1", NOW - 3_600_000L);

        assertThat(catalog(state).watch(NOW)).isZero();

        assertThat(registry.closeReasons).isEmpty();
        assertThat(market("BTC").isActive()).isTrue();
        assertThat(market("PM1").isActive()).isTrue();
    }

    @Test
    void aRegistryThatCannotCloseIsRetriedAtTheNextWatch() {
        HyperliquidLiveState state = new HyperliquidLiveState();
        int[] failures = {1};
        registry = new FakeMarketRegistry() {
            @Override
            public void close(String marketId, String reason, long nowMs) {
                if (failures[0]-- > 0) {
                    throw new IllegalStateException("database is down");
                }
                super.close(marketId, reason, nowMs);
            }
        };
        listedEth();
        HyperliquidCatalog catalog = catalog(state);
        state.reportDelisted("ETH", NOW - 60_000L);

        assertThat(catalog.watch(NOW)).isZero();
        assertThat(market("ETH").isActive()).isTrue();
        assertThat(state.delistedSince("ETH")).isPresent(); // still reported: the next watch tries again

        assertThat(catalog.watch(NOW + 5_000)).isEqualTo(1);
        assertThat(market("ETH").isActive()).isFalse();
    }

    @Test
    void aPollThatSeesTheAssetDelistedAndTheWatchTogetherCloseTheMarket() throws Exception {
        // MATIC is isDelisted in the main-dex sample (markPx frozen); its market was listed before that happened
        HyperliquidLiveState state = new HyperliquidLiveState();
        Market matic = registry.add(new Market("MATIC", MarketKind.PERP, Category.CRYPTO, "MATIC", "Polygon", null, null,
                "hyperliquid", "MATIC", null, 20, 3, 8, crowdvsmarket.server.markets.MarketStatus.ACTIVE, 0L, null, null,
                null));
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(NOW);
        crowdvsmarket.server.prices.HyperliquidPriceSource source = crowdvsmarket.server.prices.PipelineTestSupport
                .hyperliquid(api, clock::get, state);
        crowdvsmarket.server.prices.PriceSource.Feed feed = source.feeds().get(0);
        HyperliquidCatalog catalog = new HyperliquidCatalog(registry, CatalogConfig.defaults(), () -> api, state,
                HistorySeeder.disabled(), clock::get);

        for (int i = 0; i < 4; i++) { // the polls of 0, 3, 6 and 9 s, each answered with MATIC delisted
            assertThat(source.poll(feed, List.of(matic))).isEmpty(); // a delisted asset has no price to take
            clock.addAndGet(3_000);
        }

        assertThat(state.delistedSince("MATIC")).hasValue(NOW); // since the first answer that said so
        assertThat(catalog.watch(NOW + 9_000)).isZero();
        assertThat(catalog.watch(clock.get())).isEqualTo(1); // 12 s
        assertThat(market("MATIC").isActive()).isFalse();
        assertThat(registry.closeReasons).containsEntry("MATIC", HyperliquidCatalog.REASON_DELISTED);
    }

    @Test
    void theWatchJobDoesNothingOutsideLiveMode() {
        HyperliquidLiveState state = new HyperliquidLiveState();
        listedEth();
        state.reportDelisted("ETH", NOW - 60_000L);
        CatalogConfig replay = new CatalogConfig(false, false, CatalogConfig.parseNames(CatalogConfig.DEFAULT_CRYPTO),
                List.of(), List.of(), List.of(), true, 3, 3, 10, true);

        new HyperliquidCatalog(registry, replay, () -> api, state, HistorySeeder.disabled(), () -> NOW).watchJob();
        assertThat(market("ETH").isActive()).isTrue();

        new HyperliquidCatalog(registry, CatalogConfig.defaults(), () -> api, state, HistorySeeder.disabled(),
                () -> NOW).watchJob();
        assertThat(market("ETH").isActive()).isFalse();
    }

    // ---------------------------------------------------------------- the first minutes of a new market (G1)

    @Test
    void everyMarketItListsIsHandedToTheSeederForItsRecentHistory() {
        FakeApis.Hyperliquid history = new FakeApis.Hyperliquid();
        history.info.handler = body -> candlesPerMinute(body.get("req").get("startTime").asLong(),
                body.get("req").get("endTime").asLong());
        TickStore store = Fixtures.tickStore(() -> NOW);
        HistorySeeder seeder = PipelineTestSupport.seeder(
                List.of(PipelineTestSupport.hyperliquid(history, () -> NOW)), store, () -> NOW);
        HyperliquidCatalog catalog = new HyperliquidCatalog(registry, config(List.of("BTC", "ETH", "SOL"),
                List.of("xyz:NVDA"), List.of(), List.of()), () -> api, new HyperliquidLiveState(), seeder, () -> NOW);

        catalog.sync(NOW);

        // listed: ETH, SOL, xyz_NVDA (BTC was there); each of the three, and only those, asked for its candles
        assertThat(history.requestsOfType("candleSnapshot")).extracting(r -> r.get("req").get("coin").asText())
                .containsExactlyInAnyOrder("ETH", "SOL", "xyz:NVDA");
        for (String id : List.of("ETH", "SOL", "xyz_NVDA")) {
            assertThat(store.size(id)).as(id).isPositive();
            assertThat(store.last(id).orElseThrow().atMs()).as(id).isLessThanOrEqualTo(NOW);
        }
        assertThat(store.size("BTC")).isZero();
    }

    @Test
    void aListingTheRegistryRefusesIsNotSeeded() {
        FakeApis.Hyperliquid history = new FakeApis.Hyperliquid();
        TickStore store = Fixtures.tickStore(() -> NOW);
        HistorySeeder seeder = PipelineTestSupport.seeder(
                List.of(PipelineTestSupport.hyperliquid(history, () -> NOW)), store, () -> NOW);
        registry = new FakeMarketRegistry() {
            @Override
            public Market list(crowdvsmarket.server.markets.NewMarket spec, long nowMs) {
                throw new IllegalStateException("constraint violated");
            }
        };

        new HyperliquidCatalog(registry, config(List.of("ETH"), List.of(), List.of(), List.of()), () -> api,
                new HyperliquidLiveState(), seeder, () -> NOW).sync(NOW);

        assertThat(history.info.requests).isEmpty();
    }

    /** One candle per minute from the one containing {@code startMs} to the one containing {@code endMs}. */
    private static JsonNode candlesPerMinute(long startMs, long endMs) {
        com.fasterxml.jackson.databind.node.ArrayNode rows = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .arrayNode();
        for (long t = Math.floorDiv(startMs, 60_000L) * 60_000L; t <= endMs; t += 60_000L) {
            rows.addObject().put("t", t).put("T", t + 59_999).put("o", "100").put("h", "101").put("l", "99")
                    .put("c", "100").put("n", 5);
        }
        return rows;
    }
}
