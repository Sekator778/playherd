package crowdvsmarket.server.markets;

import crowdvsmarket.server.dto.ApiException;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fallback {@link MarketRegistry} used only while no other implementation is on the classpath
 * ({@link DefaultBean}): BTC plus whatever {@link #list} adds, in memory, nothing persisted. The
 * database-backed registry replaces it.
 */
@DefaultBean
@ApplicationScoped
public class InMemoryMarketRegistry implements MarketRegistry {

    private final List<Market> markets = new ArrayList<>();
    private volatile MarketListener listener;

    public InMemoryMarketRegistry() {
        markets.add(new Market(Market.BTC_ID, MarketKind.PERP, Category.CRYPTO, "BTC", "Bitcoin", null, null,
                "hyperliquid", "BTC", null, 40, 0, 0, MarketStatus.ACTIVE, 0L, null, null, null));
    }

    @Override
    public synchronized List<Market> all() {
        return List.copyOf(markets);
    }

    @Override
    public synchronized List<Market> active() {
        return markets.stream().filter(Market::isActive).toList();
    }

    @Override
    public synchronized Optional<Market> byId(String id) {
        return markets.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    @Override
    public Market require(String id) {
        return byId(id).orElseThrow(() -> ApiException.notFound("unknown market " + id));
    }

    @Override
    public synchronized List<Category> categories() {
        return Category.ORDER.stream()
                .filter(c -> markets.stream().anyMatch(m -> m.isActive() && m.category().equals(c.id())))
                .toList();
    }

    @Override
    public Market list(NewMarket spec, long nowMs) {
        Market listed;
        synchronized (this) {
            Optional<Market> existing = byId(spec.id());
            if (existing.isPresent()) {
                return existing.get();
            }
            int nextIndex = markets.stream().mapToInt(Market::oracleIndex).max().orElse(-1) + 1;
            listed = new Market(spec.id(), spec.kind(), spec.category(), spec.symbol(), spec.title(), spec.subtitle(),
                    spec.iconUrl(), spec.source(), spec.sourceRef(), spec.sourceMeta(), spec.maxLeverage(),
                    spec.lotExp(), nextIndex, MarketStatus.ACTIVE, nowMs, null, null, spec.endsAtMs());
            markets.add(listed);
        }
        MarketListener l = listener;
        if (l != null) {
            l.onListed(listed);
        }
        return listed;
    }

    @Override
    public void close(String marketId, String reason, long nowMs) {
        Market closed;
        synchronized (this) {
            Optional<Market> existing = byId(marketId);
            if (existing.isEmpty() || !existing.get().isActive()) {
                return;
            }
            closed = existing.get().closed(nowMs, reason);
            markets.replaceAll(m -> m.id().equals(marketId) ? closed : m);
        }
        MarketListener l = listener;
        if (l != null) {
            l.onClosed(closed, reason);
        }
    }

    @Override
    public void setListener(MarketListener listener) {
        this.listener = listener;
    }
}
