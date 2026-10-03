package crowdvsmarket.server.markets;

import crowdvsmarket.server.dto.ApiException;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * Fallback {@link MarketRegistry} used only while no other implementation is on the classpath
 * ({@link DefaultBean}): BTC plus whatever {@link #list} adds, in memory, nothing persisted. The
 * database-backed registry replaces it.
 */
@DefaultBean
@ApplicationScoped
public class InMemoryMarketRegistry implements MarketRegistry {

    private static final Logger LOG = Logger.getLogger(InMemoryMarketRegistry.class);

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
            try {
                l.onListed(listed);
            } catch (RuntimeException e) {
                LOG.errorf(e, "the market listener failed for the listing of %s — the market stays listed",
                        listed.id());
            }
        }
        return listed;
    }

    @Override
    public void close(String marketId, String reason, long nowMs) {
        if (Market.BTC_ID.equals(marketId)) {
            throw new IllegalArgumentException("BTC cannot be closed, except when another market takes over its symbol "
                    + "(closeReplaced)");
        }
        closeMarket(marketId, reason, nowMs);
    }

    @Override
    public void closeReplaced(String marketId, String reason, long nowMs) {
        closeMarket(marketId, reason, nowMs);
    }

    private void closeMarket(String marketId, String reason, long nowMs) {
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
            try {
                l.onClosed(closed, reason);
            } catch (RuntimeException e) {
                LOG.errorf(e, "the market listener failed for the closing of %s — the market stays closed",
                        closed.id());
            }
        }
    }

    @Override
    public void setListener(MarketListener listener) {
        this.listener = listener;
    }
}
