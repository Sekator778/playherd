package crowdvsmarket.server.prices;

import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * Fallback {@link MarketPrices} used only while no other implementation is on the classpath
 * ({@link DefaultBean}): knows no price. The live implementation replaces it.
 */
@DefaultBean
@ApplicationScoped
public class NoMarketPrices implements MarketPrices {

    @Override
    public Optional<PriceQuote> latest(String marketId) {
        return Optional.empty();
    }

    @Override
    public List<PricePoint> series(String marketId, long fromMs, long toMs) {
        return List.of();
    }

    @Override
    public RoundPriceData resolve(Market market, RoundWindow window) {
        throw new PriceUnavailableException("no price source configured for " + market.id());
    }

    @Override
    public Optional<RoundPriceData> soFar(Market market, RoundWindow window, long nowMs) {
        return Optional.empty();
    }
}
