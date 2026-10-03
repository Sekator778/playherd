package crowdvsmarket.server.prices;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * What a test outside this package needs to put the real price pipeline together by hand: the constructors that take a clock, a
 * list of sources or a holder of the round venues are package-private on purpose (production wires them by injection). Nothing
 * here decides anything.
 */
public final class PricingFixtures {

    private PricingFixtures() {
    }

    /** The given sources as the pipeline sees them, whatever the configuration says. */
    public static PriceSources sources(List<PriceSource> active) {
        return new PriceSources(active);
    }

    /** The real {@link LiveMarketPrices} over these rings, sources and round venues, on the clock the test drives. */
    public static LiveMarketPrices livePrices(TickStore store, PriceSources sources, PricesConfig config, RoundVenues venues,
                                              LongSupplier clock) {
        return new LiveMarketPrices(store, sources, config, venues, clock);
    }
}
