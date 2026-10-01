package crowdvsmarket.server.markets.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.prices.Fixtures;
import crowdvsmarket.server.prices.HistorySeeder;
import crowdvsmarket.server.prices.PipelineTestSupport;
import crowdvsmarket.server.prices.SyntheticPriceSource;
import crowdvsmarket.server.prices.TickStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The offline catalog: the built-in parameter table, the synthetic ODDS markets, no HTTP, idempotent. */
class SyntheticCatalogTest {

    private static final long NOW = Fixtures.NOW;

    private FakeMarketRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new FakeMarketRegistry();
    }

    private SyntheticCatalog catalog(CatalogConfig config) {
        return new SyntheticCatalog(registry, config, () -> NOW);
    }

    private static CatalogConfig syntheticConfig(int politics, int sports) {
        return new CatalogConfig(false, true, CatalogConfig.parseNames(CatalogConfig.DEFAULT_CRYPTO),
                CatalogConfig.parseNames(CatalogConfig.DEFAULT_STOCKS),
                CatalogConfig.parseNames(CatalogConfig.DEFAULT_INDICES),
                CatalogConfig.parseNames(CatalogConfig.DEFAULT_COMMODITIES), true, politics, sports, 10, true);
    }

    private Market market(String id) {
        return registry.byId(id).orElseThrow(() -> new AssertionError("not listed: " + id));
    }

    @Test
    void listsTheConfiguredPerpsWithTheBuiltInParameters() {
        int added = catalog(syntheticConfig(0, 0)).sync(NOW);

        assertThat(added).isEqualTo(11); // BTC is in the registry already
        assertThat(registry.log).doesNotContain("listed BTC");
        // id, leverage cap, lot exponent of the start price (Т-3)
        record Expected(String id, int leverage, int lotExp, String start, String category) {
        }
        for (Expected e : List.of(
                new Expected("ETH", 25, 0, "2700", Category.CRYPTO),
                new Expected("SOL", 20, 1, "150", Category.CRYPTO),
                new Expected("XRP", 20, 3, "2.5", Category.CRYPTO),
                new Expected("DOGE", 10, 5, "0.095", Category.CRYPTO),
                new Expected("HYPE", 10, 2, "40", Category.CRYPTO),
                new Expected("xyz_NVDA", 20, 1, "180", Category.STOCKS),
                new Expected("xyz_TSLA", 20, 1, "360", Category.STOCKS),
                new Expected("xyz_SP500", 40, 0, "7600", Category.INDICES),
                new Expected("xyz_XYZ100", 30, 0, "28000", Category.INDICES),
                new Expected("xyz_GOLD", 25, 0, "4000", Category.COMMODITIES),
                new Expected("xyz_BRENTOIL", 20, 2, "70", Category.COMMODITIES))) {
            Market m = market(e.id());
            assertThat(m.maxLeverage()).as(e.id() + " leverage").isEqualTo(e.leverage());
            assertThat(m.lotExp()).as(e.id() + " lot exponent").isEqualTo(e.lotExp());
            assertThat(m.category()).as(e.id()).isEqualTo(e.category());
            assertThat(m.kind()).isEqualTo(MarketKind.PERP);
            assertThat(m.source()).isEqualTo("synthetic");
            assertThat(Fixtures.parse(m.sourceMeta()).get("start").asText()).as(e.id() + " start").isEqualTo(e.start());
            assertThat(m.isActive()).isTrue();
            assertThat(m.listedAtMs()).isEqualTo(NOW);
        }
        assertThat(market("xyz_NVDA").symbol()).isEqualTo("NVDA");
        assertThat(market("xyz_NVDA").title()).isEqualTo("NVIDIA");
        assertThat(market("xyz_NVDA").sourceRef()).isEqualTo("xyz:NVDA");
        assertThat(market("ETH").title()).isEqualTo("Ethereum");
    }

    @Test
    void anUnknownNameGetsTenTimesLeverageAtOneHundred() {
        CatalogConfig config = new CatalogConfig(false, true, List.of("BTC", "NOSUCH"), List.of("xyz:WHATEVER"), List.of(),
                List.of(), true, 0, 0, 10, true);

        catalog(config).sync(NOW);

        assertThat(market("NOSUCH").maxLeverage()).isEqualTo(10);
        assertThat(market("NOSUCH").lotExp()).isEqualTo(1); // 100 -> lot price 1000
        assertThat(Fixtures.parse(market("NOSUCH").sourceMeta()).get("start").asText()).isEqualTo("100");
        assertThat(market("xyz_WHATEVER").symbol()).isEqualTo("WHATEVER");
        assertThat(market("xyz_WHATEVER").title()).isEqualTo("WHATEVER"); // not in the name table: its ticker
    }

    @Test
    void listsTheSyntheticOddsMarketsPoliticsFirstThenSports() {
        catalog(syntheticConfig(3, 3)).sync(NOW);

        for (int n = 1; n <= 6; n++) {
            Market m = market("PMSYN" + n);
            assertThat(m.kind()).isEqualTo(MarketKind.ODDS);
            assertThat(m.category()).isEqualTo(n <= 3 ? Category.POLITICS : Category.SPORTS);
            assertThat(m.lotExp()).isEqualTo(Market.ODDS_LOT_EXP);
            assertThat(m.maxLeverage()).isEqualTo(10);
            assertThat(m.source()).isEqualTo("synthetic");
            assertThat(m.sourceRef()).isEqualTo("PMSYN" + n);
            assertThat(m.symbol()).hasSizeLessThanOrEqualTo(8);
            assertThat(m.endsAtMs()).isNull();
            double start = Fixtures.parse(m.sourceMeta()).get("start").asDouble();
            assertThat(start).isBetween(0.30, 0.70);
        }
        assertThat(market("PMSYN1").symbol()).isEqualTo("SYNP1");
        assertThat(market("PMSYN4").symbol()).isEqualTo("SYNS1");
        assertThat(registry.log.stream().filter(l -> l.startsWith("listed PMSYN"))).hasSize(6);
    }

    @Test
    void theNumberOfOddsMarketsFollowsTheCounts() {
        catalog(syntheticConfig(1, 2)).sync(NOW);

        assertThat(registry.byId("PMSYN1")).isPresent();
        assertThat(market("PMSYN1").category()).isEqualTo(Category.POLITICS);
        assertThat(market("PMSYN2").category()).isEqualTo(Category.SPORTS);
        assertThat(market("PMSYN3").category()).isEqualTo(Category.SPORTS);
        assertThat(registry.byId("PMSYN4")).isEmpty();
    }

    @Test
    void theOddsStartsAreTheSameEveryTimeForTheSameId() {
        catalog(syntheticConfig(3, 3)).sync(NOW);
        String first = market("PMSYN2").sourceMeta();

        setUp();
        catalog(syntheticConfig(3, 3)).sync(NOW + 1);

        assertThat(market("PMSYN2").sourceMeta()).isEqualTo(first);
    }

    @Test
    void listingTwiceAddsNothing() {
        SyntheticCatalog catalog = catalog(syntheticConfig(3, 3));
        assertThat(catalog.sync(NOW)).isEqualTo(17);

        assertThat(catalog.sync(NOW + 60_000)).isZero();
        assertThat(registry.all()).hasSize(18); // BTC + 11 perps + 6 odds
    }

    @Test
    void theScheduledRunListsOnlyInSyntheticMode() {
        catalog(CatalogConfig.defaults()).run(); // real sources: not its job
        assertThat(registry.log).isEmpty();

        catalog(syntheticConfig(3, 3)).run();
        assertThat(registry.log).isNotEmpty();
    }

    @Test
    void everyMarketItListsIsSeededWithTheLastFifteenMinutesOfItsWalk() {
        TickStore store = Fixtures.tickStore(() -> NOW);
        SyntheticPriceSource walk = PipelineTestSupport.synthetic(store, () -> NOW);
        HistorySeeder seeder = PipelineTestSupport.seeder(List.of(walk), store, () -> NOW);

        new SyntheticCatalog(registry, syntheticConfig(1, 1), seeder, () -> NOW).sync(NOW);

        assertThat(registry.active()).hasSize(14); // BTC + 11 perps + 2 odds
        for (Market market : registry.active()) {
            if (market.id().equals("BTC")) {
                assertThat(store.size("BTC")).isZero(); // BTC is not listed by a catalog: the start-up seeds it
                continue;
            }
            assertThat(store.size(market.id())).as(market.id()).isEqualTo(901); // one point a second, both ends
            assertThat(store.feedOf(market.id())).as(market.id()).contains("synthetic");
        }
    }
}
