package crowdvsmarket.server.markets.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.prices.FakeApis;
import crowdvsmarket.server.prices.Fixtures;
import crowdvsmarket.server.prices.HistorySeeder;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The spot catalog against the Coinbase tickers and the Hyperliquid spot meta of 2026-10-03 (fixtures/): which markets it
 * lists from the five Coinbase USD products and the Hyperliquid spot token with which parameters, the
 * <b>replacement</b> of the market of the same symbol that the new one takes over (closed first, in plain words, and
 * only then the new one listed), that BTC can be closed only that way, that a run after a restart replaces nothing twice,
 * and that a source that cannot be reached leaves everything as it was and is asked again.
 */
class SpotCatalogTest {

    private static final long NOW = Fixtures.NOW;
    private static final String REASON_COINBASE = "Moved to a new price source: Coinbase";
    private static final String REASON_HYPERLIQUID = "Moved to a new price source: Hyperliquid spot";

    private final JsonNode spotMeta = Fixtures.json("hyperliquid/spot_meta_and_asset_ctxs.json");

    private FakeMarketRegistry registry;
    private FakeApis.Coinbase coinbase;
    private FakeApis.Hyperliquid hyperliquid;
    private FakeApis.Kraken kraken;
    private final Map<String, JsonNode> tickers = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        registry = new FakeMarketRegistry();
        coinbase = new FakeApis.Coinbase();
        hyperliquid = new FakeApis.Hyperliquid();
        kraken = new FakeApis.Kraken();
        kraken.assetPairs.handler = this::krakenAssetPairs;
        tickers.put("BTC-USD", Fixtures.json("coinbase/rest_ticker_btc.json")); // bid 84631.94, ask 84631.95
        tickers.put("ETH-USD", ticker("2684.34", "2684.35"));
        tickers.put("SOL-USD", ticker("119.42", "119.43"));
        tickers.put("XRP-USD", ticker("1.48692", "1.48693"));
        tickers.put("DOGE-USD", Fixtures.json("coinbase/rest_ticker_doge.json")); // bid 0.09267, ask 0.09268
        tickers.put("HYPE-USD", ticker("88.06", "88.07"));
        coinbase.ticker.handler = product -> {
            JsonNode answer = tickers.get(product);
            if (answer == null) {
                throw new WebApplicationException(Response.status(404).build());
            }
            return answer;
        };
        hyperliquid.info.handler = body -> spotMeta;
    }

    private static JsonNode ticker(String bid, String ask) {
        return Fixtures.parse("{\"bid\":\"" + bid + "\",\"ask\":\"" + ask + "\",\"price\":\"" + ask + "\"}");
    }

    /**
     * Kraken's {@code AssetPairs} for one pair, from the fixture of 2026-10-03 (BTC, ETH, SOL, XRP, DOGE against USD): the pair
     * when it is in it, else what Kraken answers for a pair it does not have — an error with an HTTP 200.
     */
    private JsonNode krakenAssetPairs(String pair) {
        JsonNode fixture = Fixtures.json("kraken/rest_asset_pairs_symbols.json").get("result");
        if (!fixture.has(pair)) {
            return Fixtures.parse("{\"error\":[\"EQuery:Unknown asset pair\"]}");
        }
        return Fixtures.parse("{\"error\":[],\"result\":{\"" + pair + "\":" + fixture.get(pair) + "}}");
    }

    private SpotCatalog catalog(CatalogConfig config) {
        return new SpotCatalog(registry, config, () -> coinbase, () -> hyperliquid, () -> kraken, () -> NOW);
    }

    private SpotCatalog catalog() {
        return catalog(CatalogConfig.defaults());
    }

    private Market market(String id) {
        return registry.byId(id).orElseThrow(() -> new AssertionError("not listed: " + id));
    }

    /** What the stand's database held before the move: the Hyperliquid perpetuals of the six coins, BTC from V7. */
    private void withTheOldPerpetuals() {
        registry.add(crowdvsmarket.server.prices.TestMarkets.perp("ETH", "ETH", Category.CRYPTO, 25, 0, 1));
        registry.add(crowdvsmarket.server.prices.TestMarkets.perp("SOL", "SOL", Category.CRYPTO, 20, 1, 2));
        registry.add(crowdvsmarket.server.prices.TestMarkets.perp("XRP", "XRP", Category.CRYPTO, 20, 3, 3));
        registry.add(crowdvsmarket.server.prices.TestMarkets.perp("DOGE", "DOGE", Category.CRYPTO, 10, 5, 4));
        registry.add(crowdvsmarket.server.prices.TestMarkets.perp("HYPE", "HYPE", Category.CRYPTO, 10, 2, 5));
    }

    // ------------------------------------------------------------------------ what is listed

    @Test
    void listsOneMarketPerCoinbaseProductAndPerHyperliquidSpotToken() {
        int pending = catalog().sync(NOW);

        assertThat(pending).isZero();
        assertThat(registry.log).contains("listed spot_BTC", "listed spot_ETH", "listed spot_SOL", "listed spot_XRP",
                "listed spot_DOGE", "listed spot_HYPE");
        assertThat(registry.log.stream().filter(l -> l.startsWith("listed "))).hasSize(6);
    }

    @Test
    void aListedMarketCarriesTheParametersOfItsVenue() {
        catalog().sync(NOW);

        Market btc = market("spot_BTC");
        assertThat(btc.kind()).as("PERP: the USD price of an asset, whatever the venue").isEqualTo(MarketKind.PERP);
        assertThat(btc.category()).isEqualTo(Category.CRYPTO);
        assertThat(btc.symbol()).isEqualTo("BTC");
        assertThat(btc.title()).isEqualTo("Bitcoin");
        assertThat(btc.subtitle()).isNull();
        assertThat(btc.source()).isEqualTo("coinbase");
        assertThat(btc.sourceRef()).isEqualTo("BTC-USD");
        assertThat(btc.maxLeverage()).isEqualTo(40);
        assertThat(btc.lotExp()).as("mid 84631.945 is above the lot band: clamped to 0").isZero();
        assertThat(btc.listedAtMs()).isEqualTo(NOW);
        assertThat(btc.endsAtMs()).isNull();
        assertThat(btc.isActive()).isTrue();
        assertThat(Fixtures.parse(btc.sourceMeta()).get("product").asText()).isEqualTo("BTC-USD");
        assertThat(Fixtures.parse(btc.sourceMeta()).get("quote").asText()).isEqualTo("USD");

        assertThat(market("spot_ETH").maxLeverage()).isEqualTo(25);
        assertThat(market("spot_ETH").lotExp()).isZero(); // 2684.345
        assertThat(market("spot_SOL").maxLeverage()).isEqualTo(20);
        assertThat(market("spot_SOL").lotExp()).isEqualTo(1); // 119.425 -> lot price 1194.25
        assertThat(market("spot_XRP").maxLeverage()).isEqualTo(20);
        assertThat(market("spot_XRP").lotExp()).isEqualTo(3); // 1.486925 -> lot price 1486.925
        assertThat(market("spot_DOGE").maxLeverage()).isEqualTo(10);
        assertThat(market("spot_DOGE").lotExp()).isEqualTo(5); // 0.092675 -> 9267.5
        assertThat(market("spot_DOGE").title()).isEqualTo("Dogecoin");
    }

    @Test
    void theHyperliquidSpotTokenIsPricedByItsPairNameFromTheSpotMeta() {
        catalog().sync(NOW);

        Market hype = market("spot_HYPE");
        assertThat(hype.source()).isEqualTo("hyperliquid");
        assertThat(hype.sourceRef()).as("the pair of the tokens HYPE and USDC, found in the meta").isEqualTo("@107");
        assertThat(hype.symbol()).isEqualTo("HYPE");
        assertThat(hype.title()).isEqualTo("Hyperliquid");
        assertThat(hype.maxLeverage()).isEqualTo(10);
        assertThat(hype.lotExp()).as("midPx 87.8665").isEqualTo(2);
        assertThat(Fixtures.parse(hype.sourceMeta()).get("spot").asBoolean()).isTrue();
        assertThat(Fixtures.parse(hype.sourceMeta()).get("pair").asText()).isEqualTo("HYPE/USDC");
    }

    @Test
    void thePairIsLookedUpByTheTokenNamesAndNeverHardCoded() {
        // the same venue, another numbering: HYPE is token 777 here and its pair with USDC is @42 — the market follows
        hyperliquid.info.handler = body -> Fixtures.parse("[{\"tokens\":[{\"name\":\"USDC\",\"index\":0},"
                + "{\"name\":\"HYPE\",\"index\":777}],\"universe\":[{\"tokens\":[777,0],\"name\":\"@42\",\"index\":42}]},"
                + "[{\"coin\":\"@7\",\"midPx\":\"1.0\"},{\"coin\":\"@42\",\"midPx\":\"55.5\"}]]");

        catalog().sync(NOW);

        assertThat(market("spot_HYPE").sourceRef()).isEqualTo("@42");
        assertThat(market("spot_HYPE").lotExp()).isEqualTo(2); // 55.5
    }

    @Test
    void theMidPxIsFoundByTheCoinNameNotByPosition() {
        // ctxs lists @42 first: reading position 42 (or the pair's index) would price HYPE with the other pair's mid
        hyperliquid.info.handler = body -> Fixtures.parse("[{\"tokens\":[{\"name\":\"USDC\",\"index\":0},"
                + "{\"name\":\"HYPE\",\"index\":150}],\"universe\":[{\"tokens\":[150,0],\"name\":\"@42\",\"index\":42}]},"
                + "[{\"coin\":\"@42\",\"midPx\":\"0.04123\"},{\"coin\":\"@1\",\"midPx\":\"12.0\"}]]");

        catalog().sync(NOW);

        assertThat(market("spot_HYPE").lotExp()).as("0.04123 -> lot price 4123").isEqualTo(5);
    }

    @Test
    void aTokenWithNoSinglePairAgainstUsdcIsSkippedAndNeverAskedForAgain() {
        // two pairs of HYPE and USDC: which is the venue's own is not for the catalog to guess
        hyperliquid.info.handler = body -> Fixtures.parse("[{\"tokens\":[{\"name\":\"USDC\",\"index\":0},"
                + "{\"name\":\"HYPE\",\"index\":150}],\"universe\":[{\"tokens\":[150,0],\"name\":\"@1\",\"index\":1},"
                + "{\"tokens\":[150,0],\"name\":\"@2\",\"index\":2}]},[{\"coin\":\"@1\",\"midPx\":\"1\"},"
                + "{\"coin\":\"@2\",\"midPx\":\"2\"}]]");
        SpotCatalog catalog = catalog(config(List.of(), List.of("HYPE", "NOPE")));

        assertThat(catalog.sync(NOW)).isZero();

        assertThat(registry.log).isEmpty();
        assertThat(catalog.knownBad()).containsExactlyInAnyOrder("HYPE", "NOPE");
        hyperliquid.info.requests.clear();
        assertThat(catalog.sync(NOW + 60_000)).isZero();
        assertThat(hyperliquid.info.requests).as("known bad: not asked for again").isEmpty();
    }

    @Test
    void oneReadOfTheSpotMetaServesEveryTokenOfAPass() {
        catalog(config(List.of(), List.of("HYPE", "PURR"))).sync(NOW);

        assertThat(hyperliquid.requestsOfType("spotMetaAndAssetCtxs")).hasSize(1);
    }

    @Test
    void theLeverageIsTheConfiguredCapOfTheSymbolNeverAboveFortyAndTenForASymbolWithNone() {
        CatalogConfig defaults = CatalogConfig.defaults();
        CatalogConfig custom = new CatalogConfig(true, false, List.of(), List.of(), List.of(), List.of(), true, 3, 3, 10,
                true, defaults.gameCooldownMs(), List.of("BTC-USD", "ETH-USD"), List.of(), Map.of("BTC", 80));
        catalog(custom).sync(NOW);

        assertThat(market("spot_BTC").maxLeverage()).as("min(40, 80)").isEqualTo(40);
        assertThat(market("spot_ETH").maxLeverage()).as("not in the table").isEqualTo(10);
    }

    // ------------------------------------------------------------------------ the backup exchange (Р-76)

    private CatalogConfig configWith(String backup, java.util.Set<String> disabledVenues) {
        CatalogConfig d = CatalogConfig.defaults();
        return new CatalogConfig(true, false, List.of(), List.of(), List.of(), List.of(), true, 3, 3, 10, true,
                d.gameCooldownMs(), d.coinbase(), d.hyperliquidSpot(), d.spotMaxLeverage(),
                CatalogConfig.parseBackups(backup), disabledVenues);
    }

    @Test
    void aListedMarketRecordsItsBackupExchangeInItsSourceMeta() {
        catalog().sync(NOW);

        JsonNode btc = Fixtures.parse(market("spot_BTC").sourceMeta());
        assertThat(btc.get("product").asText()).as("what was there before stays").isEqualTo("BTC-USD");
        assertThat(btc.get("backup").get("source").asText()).isEqualTo("kraken");
        assertThat(btc.get("backup").get("ref").asText()).as("Kraken's WebSocket v2 name of the pair").isEqualTo("BTC/USD");
        for (String coin : List.of("ETH", "SOL", "XRP", "DOGE")) {
            JsonNode backup = Fixtures.parse(market("spot_" + coin).sourceMeta()).get("backup");
            assertThat(backup.get("source").asText()).as(coin).isEqualTo("kraken");
            assertThat(backup.get("ref").asText()).as(coin).isEqualTo(coin + "/USD");
        }
        JsonNode hype = Fixtures.parse(market("spot_HYPE").sourceMeta());
        assertThat(hype.get("pair").asText()).isEqualTo("HYPE/USDC");
        assertThat(hype.get("backup").get("source").asText()).as("Coinbase behind Hyperliquid's own spot market")
                .isEqualTo("coinbase");
        assertThat(hype.get("backup").get("ref").asText()).isEqualTo("HYPE-USD");
    }

    @Test
    void theBackupsAreWhatTheMarketsFindThroughInstruments() {
        catalog().sync(NOW);

        assertThat(crowdvsmarket.server.prices.Instruments.backupOf(market("spot_BTC")))
                .contains(new crowdvsmarket.server.prices.Instruments.Backup("kraken", "BTC/USD"));
        assertThat(crowdvsmarket.server.prices.Instruments.backupOf(market("spot_HYPE")))
                .contains(new crowdvsmarket.server.prices.Instruments.Backup("coinbase", "HYPE-USD"));
        assertThat(crowdvsmarket.server.prices.Instruments.shadowOf(market("spot_BTC")).orElseThrow().id())
                .isEqualTo("spot_BTC~kraken");
    }

    @Test
    void aSymbolWithNoBackupOrNamedNoneHasNoneAndNothingElseOfTheListingChanges() {
        catalog(configWith("BTC:none,ETH:kraken", java.util.Set.of())).sync(NOW);

        assertThat(Fixtures.parse(market("spot_BTC").sourceMeta()).has("backup")).as("none").isFalse();
        assertThat(Fixtures.parse(market("spot_SOL").sourceMeta()).has("backup")).as("not in the setting").isFalse();
        assertThat(Fixtures.parse(market("spot_HYPE").sourceMeta()).has("backup")).isFalse();
        assertThat(Fixtures.parse(market("spot_ETH").sourceMeta()).get("backup").get("source").asText()).isEqualTo("kraken");
        assertThat(crowdvsmarket.server.prices.Instruments.hasBackup(market("spot_BTC"))).isFalse();
        assertThat(market("spot_BTC").source()).isEqualTo("coinbase");
        assertThat(market("spot_BTC").maxLeverage()).isEqualTo(40);
    }

    @Test
    void aBackupOnTheVenueThatPricesTheMarketIsNoBackup() {
        catalog(configWith("BTC:coinbase,HYPE:coinbase", java.util.Set.of())).sync(NOW);

        assertThat(Fixtures.parse(market("spot_BTC").sourceMeta()).has("backup")).as("Coinbase behind Coinbase").isFalse();
        assertThat(Fixtures.parse(market("spot_HYPE").sourceMeta()).has("backup")).as("Coinbase behind Hyperliquid is one").isTrue();
    }

    @Test
    void theBackupIsFixedAtListingAndAChangeOfTheSettingIsForTheMarketsListedAfterIt() {
        catalog().sync(NOW);
        String before = market("spot_BTC").sourceMeta();

        catalog(configWith("BTC:none", java.util.Set.of())).sync(NOW + 60_000L);

        assertThat(market("spot_BTC").sourceMeta()).as("listed already: never again").isEqualTo(before);
        assertThat(registry.log.stream().filter(l -> l.startsWith("listed spot_BTC"))).hasSize(1);
    }

    @Test
    void aKrakenBackupPairIsLookedUpInAssetPairsOnceBeforeItIsRecordedAndCoinbaseIsNotAskedForABackup() {
        catalog().sync(NOW);

        assertThat(kraken.assetPairs.requests).as("one lookup per pair, by the name that goes into the listing")
                .containsExactlyInAnyOrder("BTC/USD", "ETH/USD", "SOL/USD", "XRP/USD", "DOGE/USD");
        assertThat(coinbase.ticker.requests).as("only the markets' own products: HYPE-USD is nobody's product here")
                .containsExactlyInAnyOrder("BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD", "DOGE-USD");
        assertThat(hyperliquid.requestsOfType("spotMetaAndAssetCtxs")).hasSize(1);
        assertThat(kraken.ticker.requests).as("nothing but AssetPairs").isEmpty();
        assertThat(kraken.ohlc.requests).isEmpty();
    }

    @Test
    void aPairKrakenDoesNotKnowIsListedWithoutABackupAndTheOthersKeepTheirs() {
        kraken.assetPairs.handler = pair -> pair.equals("DOGE/USD")
                ? Fixtures.parse("{\"error\":[\"EQuery:Unknown asset pair\"]}") : krakenAssetPairs(pair);

        int pending = catalog().sync(NOW);

        assertThat(pending).isZero();
        assertThat(registry.byId("spot_DOGE")).as("the market itself is listed: only its backup is missing").isPresent();
        assertThat(Fixtures.parse(market("spot_DOGE").sourceMeta()).has("backup")).isFalse();
        assertThat(Fixtures.parse(market("spot_DOGE").sourceMeta()).get("product").asText()).isEqualTo("DOGE-USD");
        assertThat(crowdvsmarket.server.prices.Instruments.hasBackup(market("spot_DOGE"))).isFalse();
        assertThat(crowdvsmarket.server.prices.Instruments.shadowOf(market("spot_DOGE"))).isEmpty();
        for (String coin : List.of("BTC", "ETH", "SOL", "XRP")) {
            assertThat(crowdvsmarket.server.prices.Instruments.backupOf(market("spot_" + coin)))
                    .contains(new crowdvsmarket.server.prices.Instruments.Backup("kraken", coin + "/USD"));
        }
        assertThat(Fixtures.parse(market("spot_HYPE").sourceMeta()).get("backup").get("source").asText()).isEqualTo("coinbase");
    }

    @Test
    void aKrakenThatCannotBeAskedOrSaysNothingAboutThePairKeepsTheBackupAndKeepsNoMarketFromTheLobby() {
        // HTTP 503 for BTC, a rate limit for ETH, an answer with neither a pair nor an error for SOL: no word about the pair
        kraken.assetPairs.handler = pair -> switch (pair) {
            case "BTC/USD" -> throw new WebApplicationException(Response.status(503).build());
            case "ETH/USD" -> Fixtures.parse("{\"error\":[\"EGeneral:Too many requests\"]}");
            case "SOL/USD" -> Fixtures.parse("{\"error\":[],\"result\":{}}");
            default -> krakenAssetPairs(pair);
        };

        catalog().sync(NOW);

        for (String coin : List.of("BTC", "ETH", "SOL", "XRP", "DOGE")) {
            assertThat(crowdvsmarket.server.prices.Instruments.backupOf(market("spot_" + coin))).as(coin)
                    .contains(new crowdvsmarket.server.prices.Instruments.Backup("kraken", coin + "/USD"));
        }
    }

    @Test
    void aDefiniteAnswerIsKeptSoARepeatedListingDoesNotAskTwice() {
        kraken.assetPairs.handler = pair -> pair.equals("DOGE/USD")
                ? Fixtures.parse("{\"error\":[\"EQuery:Unknown asset pair\"]}") : krakenAssetPairs(pair);
        registry = new FakeMarketRegistry() { // the first listing fails, as a database that hiccups: the next pass lists it
            private boolean failed;

            @Override
            public Market list(crowdvsmarket.server.markets.NewMarket spec, long nowMs) {
                if (!failed) {
                    failed = true;
                    throw new IllegalStateException("database unavailable");
                }
                return super.list(spec, nowMs);
            }
        };
        SpotCatalog catalog = catalog();

        assertThat(catalog.sync(NOW)).as("BTC is not listed yet").isEqualTo(1);
        assertThat(catalog.sync(NOW + 60_000L)).isZero();

        assertThat(kraken.assetPairs.requests.stream().filter("BTC/USD"::equals)).as("asked once, kept").hasSize(1);
        assertThat(kraken.assetPairs.requests.stream().filter("DOGE/USD"::equals)).as("the unknown one too").hasSize(1);
        assertThat(Fixtures.parse(market("spot_DOGE").sourceMeta()).has("backup")).isFalse();
        assertThat(crowdvsmarket.server.prices.Instruments.hasBackup(market("spot_BTC"))).isTrue();
    }

    @Test
    void aSwitchedOffKrakenIsNotAskedAboutAPairAndItsBackupIsStillRecorded() {
        catalog(configWith(CatalogConfig.DEFAULT_SPOT_BACKUP, java.util.Set.of("kraken"))).sync(NOW);

        assertThat(kraken.assetPairs.requests).as("not even the catalog asks a venue that is off").isEmpty();
        assertThat(crowdvsmarket.server.prices.Instruments.hasBackup(market("spot_BTC"))).isTrue();
    }

    @Test
    void aMarketWithNoKrakenBackupAsksKrakenNothing() {
        catalog(configWith("BTC:none,ETH:coinbase", java.util.Set.of())).sync(NOW);

        assertThat(kraken.assetPairs.requests).isEmpty();
    }

    // ------------------------------------------------------------------------ a venue that is switched off

    @Test
    void aSwitchedOffCoinbaseIsNotAskedAndItsMarketsAreNotListedYet() {
        int pending = catalog(configWith(CatalogConfig.DEFAULT_SPOT_BACKUP, java.util.Set.of("coinbase"))).sync(NOW);

        assertThat(coinbase.ticker.requests).as("not even the catalog asks it").isEmpty();
        assertThat(registry.byId("spot_BTC")).isEmpty();
        assertThat(registry.byId("spot_DOGE")).isEmpty();
        assertThat(registry.byId("spot_HYPE")).as("Hyperliquid is on").isPresent();
        assertThat(pending).as("the five products wait for the venue to be switched on").isEqualTo(5);
    }

    @Test
    void aSwitchedOffHyperliquidIsNotAskedAndItsMarketIsNotListedYet() {
        int pending = catalog(configWith(CatalogConfig.DEFAULT_SPOT_BACKUP, java.util.Set.of("hyperliquid"))).sync(NOW);

        assertThat(hyperliquid.info.requests).as("neither the meta nor anything else").isEmpty();
        assertThat(registry.byId("spot_HYPE")).isEmpty();
        assertThat(registry.byId("spot_BTC")).isPresent();
        assertThat(pending).isEqualTo(1);
    }

    @Test
    void aVenueThatIsSwitchedOnLaterGetsItsMarketsListedThen() {
        catalog(configWith(CatalogConfig.DEFAULT_SPOT_BACKUP, java.util.Set.of("coinbase"))).sync(NOW);

        catalog().sync(NOW + 60_000L);

        assertThat(registry.byId("spot_BTC")).isPresent();
        assertThat(registry.byId("spot_DOGE")).isPresent();
    }

    @Test
    void aSwitchedOffBackupVenueDoesNotKeepAMarketFromBeingListed() {
        catalog(configWith(CatalogConfig.DEFAULT_SPOT_BACKUP, java.util.Set.of("kraken"))).sync(NOW);

        assertThat(registry.byId("spot_BTC")).as("the backup is recorded at listing and not asked").isPresent();
        assertThat(Fixtures.parse(market("spot_BTC").sourceMeta()).get("backup").get("source").asText()).isEqualTo("kraken");
    }

    // ------------------------------------------------------------------------ replacement

    @Test
    void theMarketOfTheSameSymbolIsClosedInPlainWordsBeforeTheNewOneIsListed() {
        withTheOldPerpetuals();

        catalog().sync(NOW);

        List<String> log = registry.log;
        assertThat(registry.closeReasons).containsOnlyKeys("BTC", "ETH", "SOL", "XRP", "DOGE", "HYPE");
        assertThat(registry.closeReasons.get("BTC")).isEqualTo(REASON_COINBASE);
        assertThat(registry.closeReasons.get("ETH")).isEqualTo(REASON_COINBASE);
        assertThat(registry.closeReasons.get("HYPE")).as("shown to the players as it stands").isEqualTo(REASON_HYPERLIQUID);
        for (String symbol : List.of("BTC", "ETH", "SOL", "XRP", "DOGE", "HYPE")) {
            assertThat(log.indexOf("listed spot_" + symbol)).as("spot_%s is listed", symbol).isNotNegative();
            assertThat(log.stream().filter(l -> l.startsWith("closed " + symbol + ":")).findFirst().map(log::indexOf)
                    .orElseThrow()).as("%s is closed before spot_%s is listed", symbol, symbol)
                    .isLessThan(log.indexOf("listed spot_" + symbol));
        }
        assertThat(market("BTC").isActive()).as("the row stays, closed").isFalse();
        assertThat(market("spot_BTC").isActive()).isTrue();
        assertThat(registry.active().stream().map(Market::symbol)).as("two active markets never share a symbol")
                .doesNotHaveDuplicates();
    }

    @Test
    void theReasonsAreTheConstantsTheCatalogShows() {
        assertThat(SpotCatalog.REASON_COINBASE).isEqualTo(REASON_COINBASE);
        assertThat(SpotCatalog.REASON_HYPERLIQUID_SPOT).isEqualTo(REASON_HYPERLIQUID);
    }

    @Test
    void bitcoinCanBeClosedOnlyByAReplacementNeverByTheOrdinaryClose() {
        assertThatThrownBy(() -> registry.close("BTC", "delisted", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(market("BTC").isActive()).isTrue();

        catalog().sync(NOW); // the catalog goes through closeReplaced: had it called close(), BTC would still be active

        assertThat(market("BTC").isActive()).isFalse();
        assertThat(market("BTC").closeReason()).isEqualTo(REASON_COINBASE);
        assertThat(market("spot_BTC").isActive()).isTrue();
        assertThat(registry.byId("BTC")).as("never deleted: its rounds and records stay readable").isPresent();
    }

    @Test
    void aCloseThatFailsListsNothingAndIsTriedAgain() {
        FakeMarketRegistry failing = new FakeMarketRegistry() {
            @Override
            public void closeReplaced(String marketId, String reason, long nowMs) {
                throw new IllegalStateException("the database is down");
            }
        };
        registry = failing;
        SpotCatalog catalog = catalog(config(List.of("BTC-USD"), List.of()));

        assertThat(catalog.sync(NOW)).isEqualTo(1);

        assertThat(failing.log).as("no new market while the old one is open").isEmpty();
        assertThat(market("BTC").isActive()).isTrue();
    }

    @Test
    void aMarketThatIsStillActiveAfterItsCloseIsNotReplacedYet() {
        FakeMarketRegistry ignoring = new FakeMarketRegistry() {
            @Override
            public void closeReplaced(String marketId, String reason, long nowMs) {
                // returns, and the market stays open: another process failed to close it
            }
        };
        registry = ignoring;

        assertThat(catalog(config(List.of("BTC-USD"), List.of())).sync(NOW)).isEqualTo(1);

        assertThat(ignoring.log).isEmpty();
    }

    @Test
    void aMarketOfAnotherCategoryOrSymbolIsLeftAlone() {
        registry.add(crowdvsmarket.server.prices.TestMarkets.perp("xyz_GOLD", "xyz:GOLD", Category.COMMODITIES, 20, 0, 9));
        registry.add(crowdvsmarket.server.prices.TestMarkets.perp("PEPE", "PEPE", Category.CRYPTO, 10, 8, 10));

        catalog().sync(NOW);

        assertThat(market("xyz_GOLD").isActive()).isTrue();
        assertThat(market("PEPE").isActive()).isTrue();
    }

    // ------------------------------------------------------------------------ idempotence

    @Test
    void aRunAfterARestartReplacesNothingTwiceAndCostsNoRequest() {
        withTheOldPerpetuals();
        catalog().sync(NOW);
        List<String> afterTheFirstRun = List.copyOf(registry.log);
        coinbase.ticker.requests.clear();
        hyperliquid.info.requests.clear();

        // a new process: a new catalog over the registry the database restored
        int pending = catalog().sync(NOW + 3_600_000L);

        assertThat(pending).isZero();
        assertThat(registry.log).isEqualTo(afterTheFirstRun);
        assertThat(coinbase.ticker.requests).isEmpty();
        assertThat(hyperliquid.info.requests).isEmpty();
    }

    @Test
    void aClosedSpotMarketIsNeverListedAgain() {
        catalog().sync(NOW);
        registry.close("spot_ETH", "The source market ended", NOW + 1);
        coinbase.ticker.requests.clear();

        catalog().sync(NOW + 60_000);

        assertThat(market("spot_ETH").isActive()).isFalse();
        assertThat(registry.log.stream().filter(l -> l.equals("listed spot_ETH"))).hasSize(1);
        assertThat(coinbase.ticker.requests).isEmpty();
    }

    // ------------------------------------------------------------------------ a source that cannot be reached

    @Test
    void anUnreachableCoinbaseLeavesTheOldMarketsAsTheyAreAndTheNextRunReplacesThem() {
        withTheOldPerpetuals();
        coinbase.ticker.handler = product -> {
            throw new IllegalStateException("connection refused");
        };
        SpotCatalog catalog = catalog();

        int pending = catalog.sync(NOW);

        assertThat(pending).as("the five Coinbase products wait").isEqualTo(5);
        assertThat(registry.log.stream().filter(l -> l.contains("BTC") || l.contains("ETH") || l.contains("DOGE")))
                .as("nothing closed and nothing listed for Coinbase's markets").isEmpty();
        assertThat(market("BTC").isActive()).isTrue();
        assertThat(market("ETH").isActive()).isTrue();
        assertThat(market("spot_HYPE").isActive()).as("Hyperliquid answered: HYPE moved").isTrue();
        assertThat(market("HYPE").isActive()).isFalse();

        coinbase.ticker.handler = product -> tickers.get(product);
        assertThat(catalog.sync(NOW + 60_000)).isZero();

        assertThat(market("BTC").isActive()).isFalse();
        assertThat(market("spot_BTC").listedAtMs()).isEqualTo(NOW + 60_000);
        assertThat(market("spot_DOGE").isActive()).isTrue();
    }

    @Test
    void anUnreachableHyperliquidLeavesHypeAsItIsAndNoSecondRequestIsMadeForTheNextToken() {
        withTheOldPerpetuals();
        hyperliquid.info.handler = body -> {
            throw new IllegalStateException("connection refused");
        };
        SpotCatalog catalog = catalog(config(List.of(), List.of("HYPE", "PURR")));

        assertThat(catalog.sync(NOW)).isEqualTo(2);

        assertThat(hyperliquid.info.requests).as("one request, then both tokens wait").hasSize(1);
        assertThat(market("HYPE").isActive()).isTrue();
        assertThat(registry.byId("spot_HYPE")).isEmpty();

        hyperliquid.info.handler = body -> spotMeta;
        assertThat(catalog.sync(NOW + 60_000)).isZero();
        assertThat(market("HYPE").isActive()).isFalse();
        assertThat(market("spot_HYPE").isActive()).isTrue();
        assertThat(market("spot_PURR").sourceRef()).as("a pair is named as the meta names it").isEqualTo("PURR/USDC");
    }

    @Test
    void anAnswerThatHasNoUsablePriceYetIsRetriedNotMarkedBad() {
        tickers.put("ETH-USD", Fixtures.parse("{\"bid\":\"0\",\"ask\":\"2684.35\"}"));
        SpotCatalog catalog = catalog(config(List.of("ETH-USD"), List.of()));

        assertThat(catalog.sync(NOW)).isEqualTo(1);
        assertThat(catalog.knownBad()).isEmpty();
        assertThat(registry.byId("spot_ETH")).isEmpty();

        tickers.put("ETH-USD", ticker("2684.34", "2684.35"));
        assertThat(catalog.sync(NOW + 60_000)).isZero();
        assertThat(market("spot_ETH").isActive()).isTrue();
    }

    @Test
    void aSpotAnswerThatIsNotMetaAndCtxsWaitsForTheNextRun() {
        hyperliquid.info.handler = body -> Fixtures.parse("{\"error\":\"rate limited\"}");

        assertThat(catalog(config(List.of(), List.of("HYPE"))).sync(NOW)).isEqualTo(1);

        assertThat(registry.log).isEmpty();
    }

    @Test
    void aPairWithoutAMidPxYetWaitsForTheNextRun() {
        hyperliquid.info.handler = body -> Fixtures.parse("[{\"tokens\":[{\"name\":\"USDC\",\"index\":0},"
                + "{\"name\":\"HYPE\",\"index\":150}],\"universe\":[{\"tokens\":[150,0],\"name\":\"@107\",\"index\":107}]},"
                + "[{\"coin\":\"@107\",\"midPx\":null}]]");

        assertThat(catalog(config(List.of(), List.of("HYPE"))).sync(NOW)).isEqualTo(1);

        assertThat(registry.log).isEmpty();
    }

    // ------------------------------------------------------------------------ names that cannot be markets

    @Test
    void aProductCoinbaseDoesNotListIsKnownBadAndNeverAskedForAgain() {
        SpotCatalog catalog = catalog(config(List.of("NOPE-USD", "BTC-USD"), List.of()));

        assertThat(catalog.sync(NOW)).isZero();

        assertThat(catalog.knownBad()).containsExactly("NOPE-USD");
        assertThat(market("spot_BTC").isActive()).isTrue();
        coinbase.ticker.requests.clear();
        assertThat(catalog.sync(NOW + 60_000)).isZero();
        assertThat(coinbase.ticker.requests).as("no request for a product that cannot be listed").isEmpty();
    }

    @Test
    void anHttpErrorOtherThanNotFoundIsATransientFailure() {
        coinbase.ticker.handler = product -> {
            throw new WebApplicationException(Response.status(503).build());
        };
        SpotCatalog catalog = catalog(config(List.of("BTC-USD"), List.of()));

        assertThat(catalog.sync(NOW)).isEqualTo(1);

        assertThat(catalog.knownBad()).isEmpty();
    }

    @Test
    void aNameThatIsNotAUsdProductOrNotALegalIdIsSkippedWithoutAnyRequest() {
        SpotCatalog catalog = catalog(config(List.of("BTC-EUR", "BTC", "toolonghere-USD", "WAY_TOO_LONG_AN_ASSET-USD"),
                List.of("BAD-NAME", "TOO_LONG_TOKEN", "")));

        assertThat(catalog.sync(NOW)).isZero();

        assertThat(registry.log).isEmpty();
        assertThat(coinbase.ticker.requests).isEmpty();
        assertThat(hyperliquid.info.requests).isEmpty();
    }

    @Test
    void aCoinInBothListsIsListedOnceAndTheSecondOfTheSameIdFindsItListed() {
        SpotCatalog catalog = catalog(config(List.of("HYPE-USD"), List.of("HYPE")));

        assertThat(catalog.sync(NOW)).isZero();

        assertThat(registry.log.stream().filter(l -> l.equals("listed spot_HYPE"))).hasSize(1);
        assertThat(market("spot_HYPE").source()).as("the first list wins: Coinbase").isEqualTo("coinbase");
    }

    // ------------------------------------------------------------------------ the rest

    @Test
    void aListedMarketIsHandedToTheSeederAndAMarketNotListedIsNot() {
        HistorySeeder seeder = mock(HistorySeeder.class);
        SpotCatalog catalog = new SpotCatalog(registry, config(List.of("BTC-USD", "NOPE-USD"), List.of()), () -> coinbase,
                () -> hyperliquid, () -> kraken, seeder, () -> NOW);

        catalog.sync(NOW);

        verify(seeder).seed(market("spot_BTC"));
        verifyNoMoreInteractions(seeder); // NOPE-USD was never listed: nothing to seed
    }

    @Test
    void theScheduledRunDoesNothingOutsideLiveMode() {
        CatalogConfig replay = new CatalogConfig(false, false, List.of(), List.of(), List.of(), List.of(), true, 3, 3, 10,
                true);

        catalog(replay).run();

        assertThat(coinbase.ticker.requests).isEmpty();
        assertThat(hyperliquid.info.requests).isEmpty();
        assertThat(registry.log).isEmpty();
    }

    @Test
    void theScheduledRunListsInLiveMode() {
        catalog(config(List.of("ETH-USD"), List.of())).run();

        assertThat(registry.log).contains("listed spot_ETH");
    }

    private static CatalogConfig config(List<String> coinbase, List<String> hyperliquidSpot) {
        CatalogConfig d = CatalogConfig.defaults();
        return new CatalogConfig(true, false, List.of(), List.of(), List.of(), List.of(), true, 3, 3, 10, true,
                d.gameCooldownMs(), coinbase, hyperliquidSpot, d.spotMaxLeverage());
    }
}
