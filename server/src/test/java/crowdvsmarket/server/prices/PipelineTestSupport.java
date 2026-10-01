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
}
