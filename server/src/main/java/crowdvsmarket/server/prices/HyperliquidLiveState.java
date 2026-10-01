package crowdvsmarket.server.prices;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the Hyperliquid price source tells the Hyperliquid catalog, per market id — the mirror of
 * {@link PolymarketLiveState}. Every successful poll of a dex answers, for free, whether each of its
 * polled assets {@code isDelisted}; the poll reports it here, and the catalog closes the market
 * (a delisted asset's {@code markPx} is frozen, so its market would only go stale, and its rounds
 * would wait for an hour to be refunded). The state is plain memory, read and written without locks.
 *
 * <p>The poll runs on the price feed's thread and the catalog on its own, so the reporting side never
 * calls the market registry — closing a market ends in the game engine's lock.
 */
@ApplicationScoped
@Unremovable
public class HyperliquidLiveState {

    private final ConcurrentHashMap<String, Long> delistedSince = new ConcurrentHashMap<>();

    /** The latest answer lists the asset of this market with {@code isDelisted: true}; the first such time is kept. */
    public void reportDelisted(String marketId, long nowMs) {
        delistedSince.putIfAbsent(marketId, nowMs);
    }

    /** The latest answer lists the asset as live: whatever clock was running for a delisting stops. */
    public void reportListed(String marketId) {
        delistedSince.remove(marketId);
    }

    /** Since when every answer has said the asset is delisted, if the latest one does. */
    public OptionalLong delistedSince(String marketId) {
        Long since = delistedSince.get(marketId);
        return since == null ? OptionalLong.empty() : OptionalLong.of(since);
    }

    /** Forgets a market (it closed). */
    public void forget(String marketId) {
        delistedSince.remove(marketId);
    }
}
