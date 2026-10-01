package crowdvsmarket.server.prices;

import crowdvsmarket.server.markets.MarketRegistry;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Reaches the package-private constructors and poll entry points of the pipeline from tests in
 * other packages (the smoke test of the whole chain lives next to the catalogs).
 */
public final class PipelineTestSupport {

    private PipelineTestSupport() {
    }

    public static HyperliquidPriceSource hyperliquid(HyperliquidInfoApi api, LongSupplier clock) {
        return new HyperliquidPriceSource(() -> api, PricesConfig.defaults(), clock);
    }

    /** The same, reporting delisted assets to {@code state} (which the Hyperliquid catalog reads). */
    public static HyperliquidPriceSource hyperliquid(HyperliquidInfoApi api, LongSupplier clock,
                                                     HyperliquidLiveState state) {
        return new HyperliquidPriceSource(() -> api, PricesConfig.defaults(), state, clock);
    }

    public static PolymarketPriceSource polymarket(PolymarketClobApi clob, PolymarketDataApi data,
                                                   PolymarketLiveState state, LongSupplier clock) {
        return new PolymarketPriceSource(() -> clob, () -> data, state, PricesConfig.defaults(), clock);
    }

    public static PriceFeedService feedService(List<PriceSource> sources, TickStore store, MarketRegistry registry,
                                               LongSupplier clock) {
        return new PriceFeedService(new PriceSources(sources), store, registry, PricesConfig.defaults(), clock);
    }

    /** One poll of one feed, as the scheduler's job does it. */
    public static void poll(PriceFeedService service, String feedId) {
        service.pollFeed(feedId);
    }

    public static LiveMarketPrices livePrices(TickStore store, List<PriceSource> sources, LongSupplier clock) {
        return new LiveMarketPrices(store, new PriceSources(sources), PricesConfig.defaults(), clock);
    }

    /** The synthetic walk (herd.prices.source=synthetic) over {@code store}. */
    public static SyntheticPriceSource synthetic(TickStore store, LongSupplier clock) {
        return new SyntheticPriceSource(store, PricesConfig.defaults().withMode(true, PricesConfig.SourceKind.SYNTHETIC),
                clock);
    }

    /** A history seeder over the given sources whose seeds run on the calling thread, one after the other. */
    public static HistorySeeder seeder(List<PriceSource> sources, TickStore store, LongSupplier clock) {
        return new HistorySeeder(new PriceSources(sources), store, PricesConfig.defaults(), clock, Runnable::run);
    }
}
