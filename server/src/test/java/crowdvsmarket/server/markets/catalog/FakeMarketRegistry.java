package crowdvsmarket.server.markets.catalog;

import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketListener;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.markets.MarketStatus;
import crowdvsmarket.server.markets.NewMarket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A small in-memory {@link MarketRegistry} for the catalog and price tests (the database-backed one
 * is written elsewhere): BTC from the start, like the V7 migration; {@code list} is idempotent by id
 * and hands out the next oracle index; every listing and closing is recorded in {@link #log}.
 */
public class FakeMarketRegistry implements MarketRegistry {

    private final Map<String, Market> markets = new LinkedHashMap<>();
    /** {@code "listed ID"} and {@code "closed ID: reason"}, in order. */
    public final List<String> log = new ArrayList<>();
    /** Reasons by market id of the markets {@code close} closed. */
    public final Map<String, String> closeReasons = new LinkedHashMap<>();
    private MarketListener listener;

    public FakeMarketRegistry() {
        markets.put(Market.BTC_ID, new Market(Market.BTC_ID, MarketKind.PERP, Category.CRYPTO, "BTC", "Bitcoin", null,
                null, "hyperliquid", "BTC", null, 40, 0, 0, MarketStatus.ACTIVE, 0L, null, null, null));
    }

    @Override
    public synchronized List<Market> all() {
        return List.copyOf(markets.values());
    }

    @Override
    public synchronized List<Market> active() {
        return markets.values().stream().filter(Market::isActive).toList();
    }

    @Override
    public synchronized Optional<Market> byId(String id) {
        return Optional.ofNullable(markets.get(id));
    }

    @Override
    public Market require(String id) {
        return byId(id).orElseThrow(() -> new IllegalArgumentException("unknown market " + id));
    }

    @Override
    public synchronized List<Category> categories() {
        return Category.ORDER.stream()
                .filter(c -> markets.values().stream().anyMatch(m -> m.isActive() && m.category().equals(c.id())))
                .toList();
    }

    @Override
    public Market list(NewMarket spec, long nowMs) {
        Market listed;
        synchronized (this) {
            Market existing = markets.get(spec.id());
            if (existing != null) {
                return existing;
            }
            int next = markets.values().stream().mapToInt(Market::oracleIndex).max().orElse(-1) + 1;
            listed = new Market(spec.id(), spec.kind(), spec.category(), spec.symbol(), spec.title(), spec.subtitle(),
                    spec.iconUrl(), spec.source(), spec.sourceRef(), spec.sourceMeta(), spec.maxLeverage(),
                    spec.lotExp(), next, MarketStatus.ACTIVE, nowMs, null, null, spec.endsAtMs());
            markets.put(spec.id(), listed);
            log.add("listed " + spec.id());
        }
        if (listener != null) {
            listener.onListed(listed);
        }
        return listed;
    }

    @Override
    public void close(String marketId, String reason, long nowMs) {
        if (Market.BTC_ID.equals(marketId)) {
            throw new IllegalArgumentException("BTC cannot be closed, except by closeReplaced"); // as the real registry
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
            Market existing = markets.get(marketId);
            if (existing == null || !existing.isActive()) {
                return;
            }
            closed = existing.closed(nowMs, reason);
            markets.put(marketId, closed);
            closeReasons.put(marketId, reason);
            log.add("closed " + marketId + ": " + reason);
        }
        if (listener != null) {
            listener.onClosed(closed, reason);
        }
    }

    @Override
    public void setListener(MarketListener listener) {
        this.listener = listener;
    }

    /** Adds a market that is already there, as if an earlier run had listed it. */
    public synchronized Market add(Market market) {
        markets.put(market.id(), market);
        return market;
    }
}
