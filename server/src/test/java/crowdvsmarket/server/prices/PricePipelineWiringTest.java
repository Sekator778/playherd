package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import crowdvsmarket.server.markets.catalog.CatalogConfig;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.scheduler.Trigger;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pipeline inside the running application (replay mode, as every test of this module runs):
 * the live implementation replaces the fallback, the {@code herd.*} keys are read with the
 * architecture's defaults, the source switch selects the sources, every job is scheduled with its
 * default period — which also proves that the {@code ${key:default}} period expressions resolve — and
 * outside mode=live none of it polls, lists or stores anything.
 */
@QuarkusTest
class PricePipelineWiringTest {

    @Inject
    MarketPrices marketPrices;
    @Inject
    PricesConfig prices;
    @Inject
    CatalogConfig catalogs;
    @Inject
    PriceSources sources;
    @Inject
    Scheduler scheduler;
    @Inject
    PriceHttpClients clients;
    @Inject
    HistorySeeder seeder;
    @Inject
    HyperliquidLiveState delisting;

    @Test
    void theLiveImplementationReplacesTheFallback() {
        assertThat(marketPrices).isInstanceOf(LiveMarketPrices.class);
    }

    @Test
    void thePriceKeysHaveTheArchitecturesDefaults() {
        assertThat(prices.liveMode()).isFalse(); // herd.mode=replay, the module's default
        assertThat(prices.source()).isEqualTo(PricesConfig.SourceKind.LIVE);
        assertThat(prices.dailyRoundMs()).isEqualTo(900_000L); // the stand's DAILY round
        assertThat(prices.retentionMs()).isEqualTo(2 * 900_000L + 600_000L); // 2 x DAILY + 10 minutes
        assertThat(prices.hyperliquidUrl()).isEqualTo("https://api.hyperliquid.xyz");
        assertThat(prices.polymarketClobUrl()).isEqualTo("https://clob.polymarket.com");
        assertThat(prices.polymarketGammaUrl()).isEqualTo("https://gamma-api.polymarket.com");
        assertThat(prices.polymarketDataUrl()).isEqualTo("https://data-api.polymarket.com");
        assertThat(prices.hyperliquidStaleMs()).isEqualTo(15_000);
        assertThat(prices.hyperliquidXyzStaleMs()).isEqualTo(20_000);
        assertThat(prices.hyperliquidMaxGapMs()).isEqualTo(30_000);
        assertThat(prices.polymarketStaleMs()).isEqualTo(30_000);
        assertThat(prices.polymarketMaxGapMs()).isEqualTo(90_000);
        assertThat(prices.syntheticStaleMs()).isEqualTo(5_000);
        assertThat(prices.syntheticMaxGapMs()).isEqualTo(10_000);
        assertThat(prices.connectTimeoutMs()).isEqualTo(3_000);
        assertThat(prices.readTimeoutMs()).isEqualTo(5_000);
        assertThat(prices.pipelineActive()).isFalse();
        assertThat(prices.liveSources()).isFalse();
        assertThat(prices.syntheticSource()).isFalse();
    }

    @Test
    void theCatalogKeysHaveTheArchitecturesDefaults() {
        assertThat(catalogs.crypto()).containsExactly("BTC", "ETH", "SOL", "XRP", "DOGE", "HYPE");
        assertThat(catalogs.stocks()).containsExactly("xyz:NVDA", "xyz:TSLA");
        assertThat(catalogs.indices()).containsExactly("xyz:SP500", "xyz:XYZ100");
        assertThat(catalogs.commodities()).containsExactly("xyz:GOLD", "xyz:BRENTOIL");
        assertThat(catalogs.polymarketEnabled()).isTrue();
        assertThat(catalogs.politicsCount()).isEqualTo(3);
        assertThat(catalogs.sportsCount()).isEqualTo(3);
        assertThat(catalogs.oddsMaxLeverage()).isEqualTo(10);
        assertThat(catalogs.inPlayFirst()).isTrue();
        assertThat(catalogs.liveSources()).isFalse(); // replay mode: no catalog runs
        assertThat(catalogs.syntheticSource()).isFalse();
    }

    @Test
    void withSourceLiveTheRealSourcesAreActiveAndTheSyntheticOneIsNot() {
        assertThat(sources.active()).extracting(PriceSource::name).containsExactlyInAnyOrder("hyperliquid", "polymarket");
        assertThat(sources.bindings()).extracting(b -> b.feed().id())
                .containsExactlyInAnyOrder("hyperliquid", "hyperliquid:xyz", "polymarket");
    }

    @Test
    void everyJobIsScheduledUnderItsIdentity() {
        for (String job : List.of("price-feed-hyperliquid", "price-feed-hyperliquid-xyz", "price-feed-polymarket",
                "price-feed-synthetic", "price-feed-prune", "catalog-hyperliquid", "catalog-hyperliquid-watch",
                "catalog-polymarket",
                "catalog-polymarket-watch", "catalog-synthetic")) {
            assertThat(scheduler.getScheduledJob(job)).as(job).isNotNull();
        }
    }

    @Test
    void thePollingJobsRunAtTheirDefaultPeriods() {
        Map<String, Long> periodsMs = Map.of("price-feed-synthetic", 1_000L, "price-feed-hyperliquid", 3_000L,
                "price-feed-polymarket", 3_000L, "price-feed-hyperliquid-xyz", 6_000L);
        periodsMs.forEach((job, periodMs) -> {
            Trigger trigger = scheduler.getScheduledJob(job);
            // previous and next are read twice around a firing now and then: look again until they agree
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                assertThat(trigger.getPreviousFireTime()).as(job + " has fired").isNotNull();
                long observed = Duration.between(trigger.getPreviousFireTime(), trigger.getNextFireTime()).toMillis();
                assertThat(observed).as(job).isBetween(periodMs - 250, periodMs + 250);
            });
        });
        Trigger prune = scheduler.getScheduledJob("price-feed-prune");
        long minutesToNext = Duration.between(java.time.Instant.now(), prune.getNextFireTime()).toMinutes();
        assertThat(minutesToNext).isBetween(0L, 10L); // every 10 minutes
    }

    @Test
    void theSeederAndTheDelistingStateAreBeansAndAskNobodyOutsideLiveMode() {
        assertThat(seeder).isNotNull();
        assertThat(delisting).isNotNull();

        seeder.seed(TestMarkets.btc()); // herd.mode=replay: returns at once, queues nothing, builds no client
        assertThat(seeder.seedMissing(List.of(TestMarkets.btc()))).isZero();

        assertThat(clients.builtCount()).isZero();
    }

    @Test
    void outsideLiveModeTheJobsRunButNoRestClientIsEverBuilt() {
        for (String job : List.of("price-feed-hyperliquid", "price-feed-polymarket", "catalog-hyperliquid",
                "catalog-synthetic")) {
            Trigger trigger = scheduler.getScheduledJob(job);
            await().atMost(Duration.ofSeconds(30)).until(() -> trigger.getPreviousFireTime() != null);
        }

        // every job has fired at least once and returned at the switch: no client, hence no HTTP, was built
        assertThat(clients.builtCount()).isZero();
    }
}
