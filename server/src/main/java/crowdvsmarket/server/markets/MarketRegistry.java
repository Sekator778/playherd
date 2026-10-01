package crowdvsmarket.server.markets;

import java.util.List;
import java.util.Optional;

/**
 * The server's markets, persisted in the {@code markets} table (V7) and cached in memory. BTC is
 * always present. docs/research/markets_architecture.md §3.1.
 */
public interface MarketRegistry {

    /** Every market ever listed, in listing order. */
    List<Market> all();

    List<Market> active();

    Optional<Market> byId(String id);

    /** @throws crowdvsmarket.server.dto.ApiException {@code MARKET_NOT_FOUND} */
    Market require(String id);

    /** {@link Category#ORDER}, only those with at least one active market. */
    List<Category> categories();

    /**
     * Lists a market: assigns the next oracle index, persists it and notifies the listener.
     * Idempotent by id — an id that is already known returns the existing market unchanged.
     */
    Market list(NewMarket market, long nowMs);

    /** Marks an active market closed, persists it and notifies the listener. No-op when the
     *  market is unknown or already closed. */
    void close(String marketId, String reason, long nowMs);

    void setListener(MarketListener listener);
}
