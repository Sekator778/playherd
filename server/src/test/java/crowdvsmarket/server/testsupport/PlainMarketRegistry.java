package crowdvsmarket.server.testsupport;

import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.InMemoryMarketRegistry;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketListener;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.markets.NewMarket;
import java.util.List;
import java.util.Optional;

/**
 * A {@link MarketRegistry} for plain unit tests (no application): {@link InMemoryMarketRegistry}'s BTC and
 * its listing and closing, with the answer the real registry gives for an unknown market — {@code
 * MARKET_NOT_FOUND} (the in-memory fallback says plain {@code NOT_FOUND}). Deliberately NOT a subclass of
 * the fallback bean and without any scope annotation: a test class that is one more bean of type {@code
 * MarketRegistry} would make every {@code @QuarkusTest} of the module ambiguous.
 */
public final class PlainMarketRegistry implements MarketRegistry {

    private final InMemoryMarketRegistry memory = new InMemoryMarketRegistry();

    @Override
    public List<Market> all() {
        return memory.all();
    }

    @Override
    public List<Market> active() {
        return memory.active();
    }

    @Override
    public Optional<Market> byId(String id) {
        return memory.byId(id);
    }

    @Override
    public Market require(String id) {
        return byId(id).orElseThrow(() -> ApiException.marketNotFound(id));
    }

    @Override
    public List<Category> categories() {
        return memory.categories();
    }

    @Override
    public Market list(NewMarket market, long nowMs) {
        return memory.list(market, nowMs);
    }

    @Override
    public void close(String marketId, String reason, long nowMs) {
        memory.close(marketId, reason, nowMs);
    }

    @Override
    public void closeReplaced(String marketId, String reason, long nowMs) {
        memory.closeReplaced(marketId, reason, nowMs);
    }

    @Override
    public void setListener(MarketListener listener) {
        memory.setListener(listener);
    }
}
