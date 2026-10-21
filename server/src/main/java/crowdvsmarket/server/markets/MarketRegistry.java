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
     *  market is unknown or already closed. BTC cannot be closed this way — see {@link #closeReplaced}. */
    void close(String marketId, String reason, long nowMs);

    /**
     * {@link #close} for a market that another market takes over: the catalog of the new price source calls it
     * (the reason is what the players read: "Moved to a new price source: Coinbase") and lists the new market only
     * after it has returned and the old one is seen to be closed, so that two active markets never share a symbol. The
     * market's open rounds are cancelled with a refund, as for any closing. It is the <b>only</b> way BTC can be
     * closed: BTC is the pair the game started with — its row is the migration's, with oracle index 0 — and neither
     * delisting nor anything else may close it; here its row stays in the registry, closed, and its rounds and oracle
     * records stay readable.
     */
    void closeReplaced(String marketId, String reason, long nowMs);

    void setListener(MarketListener listener);

    /** F6: where the journal writes of a listing and a closing take their turn ({@link WriterTurn}: a command of the core writer); a default no-op. */
    default void setWriterTurn(WriterTurn turn) {
    }

    /**
     * Re-reads the persisted markets, for an implementation that caches them. {@code
     * EngineRebuildService} calls it at every rebuild, so that it needs to know no implementation
     * by name. A default method (no-op) on purpose: an implementation that keeps nothing in memory,
     * or a test double, stays valid without having to say so.
     */
    default void reload() {
    }

    /**
     * F4: makes the persisted markets agree with the {@code MarketListed} / {@code MarketClosed} events of {@code events}, the
     * journal ({@code EngineStateBuilder} calls it at every rebuild, before the registry is read): a market the table lacks is
     * inserted, one the journal closed is closed. A default no-op like {@link #reload}.
     *
     * @return the number of rows written
     */
    default int projectFromJournal(java.util.List<crowdvsmarket.server.events.GameEvent> events) {
        return 0;
    }
}
