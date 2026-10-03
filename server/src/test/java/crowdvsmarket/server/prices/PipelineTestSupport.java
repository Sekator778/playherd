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

    /** The Coinbase source over scripted REST and WebSocket layers; its stream is stepped by the test ({@code source.supervisor().step(now)}). */
    public static CoinbasePriceSource coinbase(CoinbaseExchangeApi api, WebSocketTransport transport, LongSupplier clock) {
        return new CoinbasePriceSource(() -> api, SpotPricesConfig.defaults(), transport, clock, false);
    }

    /** The Kraken source over scripted REST and WebSocket layers; its stream is stepped by the test. */
    public static KrakenPriceSource kraken(KrakenApi api, WebSocketTransport transport, LongSupplier clock) {
        return new KrakenPriceSource(() -> api, SpotPricesConfig.defaults(), transport, clock, false);
    }

    /** The Hyperliquid spot source over scripted layers; the periodic context read runs on the calling thread. */
    public static HyperliquidSpotPriceSource hyperliquidSpot(HyperliquidInfoApi api, WebSocketTransport transport,
                                                             LongSupplier clock) {
        return new HyperliquidSpotPriceSource(() -> api, SpotPricesConfig.defaults(), transport, clock, Runnable::run,
                false);
    }

    /** Steps the stream of a source by hand, as its thread would (a poll must have asked for it first). */
    public static void stepStream(PriceSource source, long nowMs) {
        if (source instanceof CoinbasePriceSource c) {
            c.supervisor().step(nowMs);
        } else if (source instanceof KrakenPriceSource k) {
            k.supervisor().step(nowMs);
        } else if (source instanceof HyperliquidSpotPriceSource h) {
            h.supervisor().step(nowMs);
        } else {
            throw new IllegalArgumentException(source + " has no stream");
        }
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
