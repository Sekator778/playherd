package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.catalog.FakeMarketRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The instruments of Р-76: a market with a backup exchange is two things to the price pipeline, itself at its own venue and
 * a shadow of it at the backup's, keyed {@code spot_BTC~kraken}. The shadow is polled on the backup's feed, kept in its own
 * ring, written to {@code price_snapshots} under its own key, restored from there at start, and seeded with history like
 * any market — and it is nothing else: not in the registry, no id a market can have.
 */
class InstrumentsTest {

    private static final long T0 = 1_791_029_226_000L;

    private final Market btc = TestMarkets.coinbaseWithKraken("BTC", 0, 1);
    private final Market hype = TestMarkets.hlSpotWithCoinbase("HYPE", "@107", 2, 2);

    // ---------------------------------------------------------------------- the shadow

    @Test
    void aMarketThatWasListedWithABackupHasItAsItsSecondInstrument() {
        Optional<Instruments.Backup> backup = Instruments.backupOf(btc);

        assertThat(backup).contains(new Instruments.Backup("kraken", "BTC/USD"));
        assertThat(Instruments.hasBackup(btc)).isTrue();
        assertThat(Instruments.backupOf(hype)).contains(new Instruments.Backup("coinbase", "HYPE-USD"));
        assertThat(Instruments.of(btc)).hasSize(2).first().isEqualTo(btc);
        assertThat(Instruments.of(btc).get(1).id()).isEqualTo("spot_BTC~kraken");
    }

    @Test
    void theShadowIsTheSameMarketAtTheBackupVenue() {
        Market shadow = Instruments.shadowOf(btc).orElseThrow();

        assertThat(shadow.id()).isEqualTo("spot_BTC~kraken");
        assertThat(shadow.source()).isEqualTo("kraken");
        assertThat(shadow.sourceRef()).isEqualTo("BTC/USD");
        assertThat(shadow.lotExp()).as("one price scale whichever venue prices a round").isEqualTo(btc.lotExp());
        assertThat(shadow.priceDecimals()).isEqualTo(btc.priceDecimals());
        assertThat(shadow.symbol()).isEqualTo("BTC");
        assertThat(shadow.category()).isEqualTo(btc.category());
        assertThat(shadow.kind()).isEqualTo(btc.kind());
        assertThat(shadow.maxLeverage()).isEqualTo(btc.maxLeverage());
        assertThat(shadow.oracleIndex()).isEqualTo(btc.oracleIndex());
        assertThat(shadow.status()).isEqualTo(btc.status());
        assertThat(shadow.sourceMeta()).as("no note, so no backup of its own").isNull();
        assertThat(Instruments.backupOf(shadow)).isEmpty();
        assertThat(Instruments.of(shadow)).containsExactly(shadow);

        Market hypeShadow = Instruments.shadowOf(hype).orElseThrow();
        assertThat(hypeShadow.id()).isEqualTo("spot_HYPE~coinbase");
        assertThat(hypeShadow.source()).isEqualTo("coinbase");
        assertThat(hypeShadow.sourceRef()).isEqualTo("HYPE-USD");
    }

    @Test
    void aMarketWithNoBackupHasNoShadowWhateverItsNoteSays() {
        assertThat(Instruments.shadowOf(TestMarkets.btc())).isEmpty();
        assertThat(Instruments.of(TestMarkets.btc())).hasSize(1);
        assertThat(Instruments.shadowOf(TestMarkets.coinbase("ETH", 0, 3))).as("a note with no backup").isEmpty();
        assertThat(Instruments.shadowOf(TestMarkets.odds("PM1", "tok"))).isEmpty();
        for (String meta : new String[] {"{\"backup\":{}}", "{\"backup\":{\"source\":\"kraken\"}}",
                "{\"backup\":{\"ref\":\"BTC/USD\"}}", "{\"backup\":\"kraken\"}", "{\"backup\": nope", "backup"}) {
            Market broken = new Market("spot_X", btc.kind(), btc.category(), "X", "X", null, null, "coinbase", "X-USD",
                    meta, 10, 0, 9, btc.status(), 0L, null, null, null);
            assertThat(Instruments.backupOf(broken)).as(meta).isEmpty();
        }
    }

    @Test
    void theBackupIsReadFromTheNormalisedJsonbToo() {
        // what Postgres hands back: keys in its own order, spaces after colons
        Market stored = new Market("spot_BTC", btc.kind(), btc.category(), "BTC", "Bitcoin", null, null, "coinbase",
                "BTC-USD", "{\"quote\": \"USD\", \"backup\": {\"ref\": \"BTC/USD\", \"source\": \"kraken\"}, "
                + "\"product\": \"BTC-USD\"}", 40, 0, 1, btc.status(), 0L, null, null, null);

        assertThat(Instruments.backupOf(stored)).contains(new Instruments.Backup("kraken", "BTC/USD"));
    }

    @Test
    void aShadowIdIsNeverAMarketIdAndTheMarketOfARingKeyIsFound() {
        assertThatThrownBy(() -> MarketSpec.requireValidId("spot_BTC~kraken")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Instruments.isShadowId("spot_BTC~kraken")).isTrue();
        assertThat(Instruments.isShadowId("spot_BTC")).isFalse();
        assertThat(Instruments.shadowId("spot_BTC", "kraken")).isEqualTo("spot_BTC~kraken");
        assertThat(Instruments.marketIdOf("spot_BTC~kraken")).isEqualTo("spot_BTC");
        assertThat(Instruments.marketIdOf("spot_BTC")).isEqualTo("spot_BTC");
        assertThat(Instruments.marketIdOf("xyz_NVDA")).isEqualTo("xyz_NVDA");
    }

    // ---------------------------------------------------------------------- polled, stored, restored

    /** A source that quotes whatever markets it is asked about, by their own sourceRef, at a price it was told. */
    private static final class Quoting implements PriceSource {

        final String name;
        final Map<String, BigDecimal> prices = new LinkedHashMap<>();
        final List<String> polled = new ArrayList<>();
        List<PricePoint> history = List.of();
        final List<String> historyAsked = new ArrayList<>();

        Quoting(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<Feed> feeds() {
            return List.of(new Feed(name, 15_000, 30_000, m -> name.equals(m.source())));
        }

        @Override
        public Map<String, Quote> poll(Feed feed, List<Market> markets) {
            polled.add(markets.stream().map(Market::id).toList().toString());
            Map<String, Quote> out = new LinkedHashMap<>();
            for (Market market : markets) {
                BigDecimal price = prices.get(market.sourceRef());
                if (price != null) {
                    out.put(market.id(), new Quote(price, null));
                }
            }
            return out;
        }

        @Override
        public List<PricePoint> history(Market market, long fromMs, long toMs) {
            historyAsked.add(market.id());
            return history;
        }
    }

    private final AtomicLong now = new AtomicLong(T0);
    private FakeMarketRegistry registry;
    private FakeSnapshotStore snapshots;
    private TickStore store;
    private Quoting coinbase;
    private Quoting kraken;
    private PriceFeedService service;

    @BeforeEach
    void setUp() {
        registry = new FakeMarketRegistry();
        registry.add(btc);
        registry.add(hype);
        snapshots = new FakeSnapshotStore();
        store = new TickStore(snapshots, PricesConfig.defaults().retentionMs(), now::get);
        coinbase = new Quoting("coinbase");
        kraken = new Quoting("kraken");
        coinbase.prices.put("BTC-USD", new BigDecimal("84700.5"));
        coinbase.prices.put("HYPE-USD", new BigDecimal("87.9"));
        kraken.prices.put("BTC/USD", new BigDecimal("84703.0"));
        service = new PriceFeedService(new PriceSources(List.of(coinbase, kraken)), store, registry,
                PricesConfig.defaults(), now::get);
    }

    @Test
    void theBackupsFeedPollsTheShadowAndTheMarketsOwnFeedPollsTheMarket() {
        service.pollFeed("coinbase");
        service.pollFeed("kraken");

        assertThat(kraken.polled).as("the Kraken feed polls the shadow of spot_BTC, and not the market").containsExactly("[spot_BTC~kraken]");
        assertThat(coinbase.polled).as("Coinbase prices spot_BTC itself and is the backup of HYPE")
                .containsExactly("[spot_BTC, spot_HYPE~coinbase]");
        assertThat(store.last("spot_BTC").orElseThrow().raw()).isEqualByComparingTo("84700.5");
        assertThat(store.last("spot_BTC~kraken").orElseThrow().raw()).isEqualByComparingTo("84703.0");
        assertThat(store.last("spot_HYPE~coinbase").orElseThrow().raw()).isEqualByComparingTo("87.9");
        assertThat(store.last("spot_HYPE")).as("nothing polls Hyperliquid's spot feed here").isEmpty();
        assertThat(store.feedOf("spot_BTC")).contains("coinbase");
        assertThat(store.feedOf("spot_BTC~kraken")).contains("kraken");
    }

    @Test
    void theBackupsTicksAreStoredUnderTheShadowKeyInTheRowOfItsOwnFeed() {
        service.pollFeed("coinbase");
        service.pollFeed("kraken");

        assertThat(snapshots.rows).hasSize(2);
        SnapshotStore.Snapshot krakenRow = snapshots.rows.stream().filter(r -> r.source().equals("kraken")).findFirst().orElseThrow();
        SnapshotStore.Snapshot coinbaseRow = snapshots.rows.stream().filter(r -> r.source().equals("coinbase")).findFirst().orElseThrow();
        assertThat(krakenRow.prices()).containsOnlyKeys("spot_BTC~kraken");
        assertThat(krakenRow.atMs()).isEqualTo(T0);
        assertThat(coinbaseRow.prices()).containsOnlyKeys("spot_BTC", "spot_HYPE~coinbase");
    }

    @Test
    void theShadowsTicksAreRestoredFromThePriceSnapshotsLikeAnyTick() {
        service.pollFeed("coinbase");
        service.pollFeed("kraken");
        now.addAndGet(2_000);
        kraken.prices.put("BTC/USD", new BigDecimal("84705.5"));
        service.pollFeed("kraken");

        TickStore afterARestart = new TickStore(snapshots, PricesConfig.defaults().retentionMs(), now::get);
        int rows = afterARestart.restore();

        assertThat(rows).isEqualTo(3);
        assertThat(afterARestart.series("spot_BTC~kraken", 0, Long.MAX_VALUE)).extracting(p -> p.raw().toPlainString())
                .containsExactly("84703", "84705.5");
        assertThat(afterARestart.last("spot_BTC~kraken").orElseThrow().atMs()).isEqualTo(T0 + 2_000);
        assertThat(afterARestart.last("spot_HYPE~coinbase").orElseThrow().raw()).isEqualByComparingTo("87.9");
        assertThat(afterARestart.feedOf("spot_BTC~kraken")).contains("kraken");
        assertThat(afterARestart.series("spot_BTC", 0, Long.MAX_VALUE)).as("the primary's ring is its own").hasSize(1);
    }

    @Test
    void aClosedMarketsShadowIsNotPolledAndAMarketWithoutABackupHasNoShadowPolled() {
        registry.add(TestMarkets.coinbase("ETH", 0, 5)); // Coinbase only
        registry.closeReasons.clear();
        registry.close("spot_HYPE", "closed", T0);

        service.pollFeed("coinbase");

        assertThat(coinbase.polled).containsExactly("[spot_BTC, spot_ETH]");
        service.pollFeed("kraken");
        assertThat(kraken.polled).containsExactly("[spot_BTC~kraken]");
    }

    @Test
    void aFeedWithNoInstrumentToPollIsNotAsked() {
        registry.close("spot_BTC", "closed", T0);
        registry.close("spot_HYPE", "closed", T0);

        service.pollFeed("kraken");
        service.pollFeed("coinbase");

        assertThat(kraken.polled).isEmpty();
        assertThat(coinbase.polled).isEmpty();
    }

    // ---------------------------------------------------------------------- seeded

    @Test
    void aListedMarketsHistoryIsSeededForBothVenues() {
        coinbase.history = List.of(new PricePoint(T0 - 120_000, new BigDecimal("84690.0")),
                new PricePoint(T0 - 60_000, new BigDecimal("84695.0")));
        kraken.history = List.of(new PricePoint(T0 - 120_000, new BigDecimal("84691.5")));
        HistorySeeder seeder = PipelineTestSupport.seeder(List.of(coinbase, kraken), store, now::get);

        seeder.seed(btc);

        assertThat(coinbase.historyAsked).containsExactly("spot_BTC");
        assertThat(kraken.historyAsked).as("the shadow is seeded like a market").containsExactly("spot_BTC~kraken");
        assertThat(store.size("spot_BTC")).isEqualTo(2);
        assertThat(store.size("spot_BTC~kraken")).isEqualTo(1);
    }

    @Test
    void aMarketWithoutABackupIsSeededOnce() {
        HistorySeeder seeder = PipelineTestSupport.seeder(List.of(coinbase, kraken), store, now::get);

        seeder.seed(TestMarkets.coinbase("ETH", 0, 5));

        assertThat(coinbase.historyAsked).containsExactly("spot_ETH");
        assertThat(kraken.historyAsked).isEmpty();
    }

    @Test
    void theBackupIsSeededByTheFeedPollWhenItsRingLacksAPriceAtTheStartOfARunningWindow() {
        HistorySeeder seeder = PipelineTestSupport.seeder(List.of(coinbase, kraken), store, now::get);
        PriceFeedService withSeeder = new PriceFeedService(new PriceSources(List.of(coinbase, kraken)), store, registry,
                PricesConfig.defaults(), seeder, now::get);

        withSeeder.pollFeed("kraken");

        assertThat(kraken.historyAsked).containsExactly("spot_BTC~kraken");
    }

    // ---------------------------------------------------------------------- the sources and the venues that are off

    @Test
    void aVenueThatIsSwitchedOffHasNoSourceAndItsFeedsAreListedAsDisabled() {
        PriceSources sources = new PriceSources(List.of(kraken), Set.of("coinbase", "hyperliquid"));

        List<PriceSource.FeedStatus> statuses = sources.statuses(T0);

        assertThat(statuses).extracting(PriceSource.FeedStatus::feed).containsExactly("kraken", "coinbase",
                "hyperliquid", "hyperliquid:xyz", "hyperliquid:spot");
        assertThat(statuses.stream().filter(s -> s.state().equals("disabled")).map(PriceSource.FeedStatus::feed))
                .containsExactly("coinbase", "hyperliquid", "hyperliquid:xyz", "hyperliquid:spot");
        assertThat(statuses.get(0).state()).as("a REST-polled stand-in source: polling").isEqualTo("polling");
        assertThat(sources.bindingFor(btc)).as("nothing prices a market of a venue that is off").isEmpty();
        assertThat(sources.bindingFor(Instruments.shadowOf(btc).orElseThrow())).isPresent();
        assertThat(sources.disabledVenues()).containsExactlyInAnyOrder("coinbase", "hyperliquid");
    }

    @Test
    void theVenueOfASourceClassIsKnownWithoutAnInstance() {
        assertThat(PriceSources.venueOf(CoinbasePriceSource.class)).isEqualTo("coinbase");
        assertThat(PriceSources.venueOf(KrakenPriceSource.class)).isEqualTo("kraken");
        assertThat(PriceSources.venueOf(HyperliquidPriceSource.class)).isEqualTo("hyperliquid");
        assertThat(PriceSources.venueOf(HyperliquidSpotPriceSource.class)).isEqualTo("hyperliquid");
        assertThat(PriceSources.venueOf(PolymarketPriceSource.class)).isEqualTo("polymarket");
        assertThat(PriceSources.venueOf(SyntheticPriceSource.class)).isEmpty();
    }
}
