package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.catalog.FakeMarketRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link PriceFeedService}: one poll of one feed, end to end through a scripted source into the tick
 * store and the snapshot table — and the switches that keep it silent outside mode=live.
 */
class PriceFeedServiceTest {

    private static final long T0 = 1_790_883_600_000L;

    /** Hyperliquid-shaped: feed {@code hyperliquid} prices BTC, feed {@code hyperliquid:xyz} the xyz markets. */
    private static final class ScriptedSource implements PriceSource {

        final List<String> polled = new ArrayList<>(); // "<feed>: <market ids>"
        Map<String, Quote> answer = new LinkedHashMap<>();
        IOException failure;

        @Override
        public String name() {
            return "hyperliquid";
        }

        @Override
        public List<Feed> feeds() {
            return List.of(new Feed("hyperliquid", 15_000, 30_000, m -> "BTC".equals(m.sourceRef())),
                    new Feed("hyperliquid:xyz", 20_000, 30_000, m -> m.sourceRef().startsWith("xyz:")));
        }

        @Override
        public Map<String, Quote> poll(Feed feed, List<Market> markets) throws IOException {
            polled.add(feed.id() + ": " + markets.stream().map(Market::id).toList());
            if (failure != null) {
                throw failure;
            }
            return answer;
        }

        @Override
        public List<PricePoint> history(Market market, long fromMs, long toMs) {
            return List.of();
        }
    }

    private final AtomicLong now = new AtomicLong(T0);
    private FakeMarketRegistry registry;
    private FakeSnapshotStore snapshots;
    private TickStore store;
    private ScriptedSource source;
    private PriceFeedService service;

    private PriceFeedService serviceFor(PricesConfig config) {
        return new PriceFeedService(new PriceSources(List.of(source)), store, registry, config, now::get);
    }

    @BeforeEach
    void setUp() {
        registry = new FakeMarketRegistry();
        registry.add(TestMarkets.perp("xyz_NVDA", "xyz:NVDA", Category.STOCKS, 20, 3, 5));
        snapshots = new FakeSnapshotStore();
        store = new TickStore(snapshots, PricesConfig.defaults().retentionMs(), now::get);
        source = new ScriptedSource();
        service = serviceFor(PricesConfig.defaults());
    }

    @Test
    void aPollStoresTheAnswerAsOneSnapshotStampedWhenTheAnswerArrived() {
        source.answer.put("BTC", new PriceSource.Quote(new BigDecimal("84629.0"), 0.0105));

        service.pollFeed("hyperliquid");

        assertThat(source.polled).containsExactly("hyperliquid: [BTC]"); // xyz_NVDA belongs to the other feed
        assertThat(store.last("BTC")).hasValueSatisfying(p -> {
            assertThat(p.atMs()).isEqualTo(T0);
            assertThat(p.raw()).isEqualByComparingTo("84629.0");
        });
        assertThat(store.change24h("BTC")).isEqualTo(0.0105);
        assertThat(snapshots.rows).hasSize(1);
        assertThat(snapshots.rows.get(0).source()).isEqualTo("hyperliquid");
    }

    @Test
    void theXyzFeedPollsOnlyTheXyzMarkets() {
        source.answer.put("xyz_NVDA", new PriceSource.Quote(new BigDecimal("231.7"), null));

        service.pollFeed("hyperliquid:xyz");

        assertThat(source.polled).containsExactly("hyperliquid:xyz: [xyz_NVDA]");
        assertThat(store.feedOf("xyz_NVDA")).contains("hyperliquid:xyz");
        assertThat(snapshots.rows.get(0).source()).isEqualTo("hyperliquid:xyz");
    }

    @Test
    void aFailedPollCostsOneTickAndNeverThrows() {
        source.failure = new IOException("connection reset");

        service.pollFeed("hyperliquid"); // must not throw: the scheduler would log a stack trace every 3 s

        assertThat(store.last("BTC")).isEmpty();
        assertThat(snapshots.rows).isEmpty();

        source.failure = null;
        source.answer.put("BTC", new PriceSource.Quote(new BigDecimal("84630"), null));
        now.addAndGet(3_000);
        service.pollFeed("hyperliquid");
        assertThat(store.last("BTC")).hasValueSatisfying(p -> assertThat(p.atMs()).isEqualTo(T0 + 3_000));
    }

    @Test
    void aMarketTheSourceDidNotAnswerGetsNoTickThisTime() {
        registry.add(TestMarkets.perp("xyz_TSLA", "xyz:TSLA", Category.STOCKS, 20, 3, 6));
        source.answer.put("xyz_NVDA", new PriceSource.Quote(new BigDecimal("231.7"), null));

        service.pollFeed("hyperliquid:xyz");

        assertThat(store.last("xyz_NVDA")).isPresent();
        assertThat(store.last("xyz_TSLA")).isEmpty();
        assertThat(snapshots.rows.get(0).prices()).containsOnlyKeys("xyz_NVDA");
    }

    @Test
    void aPriceThatWouldBeAZeroLotPriceIsDroppedBeforeItIsStored() {
        // BTC is lot exponent 0: 0.001 USD would be 0.00 for an engine candle
        source.answer.put("BTC", new PriceSource.Quote(new BigDecimal("0.001"), null));

        service.pollFeed("hyperliquid");

        assertThat(store.last("BTC")).isEmpty();
        assertThat(snapshots.rows).isEmpty();
    }

    @Test
    void aClosedMarketIsNoLongerPolled() {
        registry.close("xyz_NVDA", "test", T0);

        service.pollFeed("hyperliquid:xyz");

        assertThat(source.polled).isEmpty();
    }

    @Test
    void aFeedWithNoMarketsOrNoSourceIsNotPolled() {
        service.pollFeed("hyperliquid:xyz-unknown");
        registry.close("xyz_NVDA", "test", T0);
        service.pollFeed("hyperliquid:xyz");

        assertThat(source.polled).isEmpty();
    }

    // -------------------------------------------------------------------------- the mode switches

    @Test
    void inReplayModeEveryJobReturnsWithoutPolling() {
        PriceFeedService replay = serviceFor(PricesConfig.defaults().withMode(false, PricesConfig.SourceKind.LIVE));
        source.answer.put("BTC", new PriceSource.Quote(new BigDecimal("84629.0"), null));

        replay.pollHyperliquid();
        replay.pollHyperliquidXyz();
        replay.pollPolymarket();
        replay.pollSynthetic();
        replay.prune();

        assertThat(source.polled).isEmpty();
        assertThat(store.last("BTC")).isEmpty();
    }

    @Test
    void theLiveSourceJobsPollWithSourceLiveAndTheSyntheticJobDoesNot() {
        source.answer.put("BTC", new PriceSource.Quote(new BigDecimal("84629.0"), null));

        service.pollHyperliquid();
        service.pollSynthetic(); // source=live: the synthetic job is off

        assertThat(source.polled).containsExactly("hyperliquid: [BTC]");
    }

    @Test
    void withSourceSyntheticTheRealSourceJobsStayQuiet() {
        PriceFeedService synthetic = serviceFor(PricesConfig.defaults().withMode(true, PricesConfig.SourceKind.SYNTHETIC));

        synthetic.pollHyperliquid();
        synthetic.pollHyperliquidXyz();
        synthetic.pollPolymarket();

        assertThat(source.polled).isEmpty();
    }

    @Test
    void startUpRestoresTheBufferOnlyInLiveMode() {
        store.append("hyperliquid", T0 - 10_000, Map.of("BTC", new BigDecimal("84000")));
        TickStore fresh = new TickStore(snapshots, PricesConfig.defaults().retentionMs(), now::get);

        new PriceFeedService(new PriceSources(List.of(source)), fresh, registry,
                PricesConfig.defaults().withMode(false, PricesConfig.SourceKind.LIVE), now::get).onStart(null);
        assertThat(fresh.last("BTC")).isEmpty();

        new PriceFeedService(new PriceSources(List.of(source)), fresh, registry, PricesConfig.defaults(), now::get)
                .onStart(null);
        assertThat(fresh.last("BTC")).isPresent();
    }

    @Test
    void theJobsDeclareTheArchitecturesPeriodsAsTheirDefaults() {
        assertThat(PriceFeedService.HYPERLIQUID_EVERY).endsWith(":3s}");
        assertThat(PriceFeedService.HYPERLIQUID_XYZ_EVERY).endsWith(":6s}");
        assertThat(PriceFeedService.POLYMARKET_EVERY).endsWith(":3s}");
        assertThat(PriceFeedService.SYNTHETIC_EVERY).endsWith(":1s}");
        assertThat(PriceFeedService.PRUNE_EVERY).isEqualTo("10m");
    }
}
