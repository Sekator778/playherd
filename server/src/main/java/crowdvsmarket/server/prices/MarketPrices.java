package crowdvsmarket.server.prices;

import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import java.util.List;
import java.util.Optional;

/**
 * Live prices of the listed markets (mode=live) — docs/research/markets_architecture.md §3.2.
 * Not used in mode=replay.
 */
public interface MarketPrices {

    Optional<PriceQuote> latest(String marketId);

    /** Ticks with {@code fromMs <= atMs < toMs}, oldest first. Memory only. */
    List<PricePoint> series(String marketId, long fromMs, long toMs);

    /**
     * Settlement data of a finished window. May ask the source for history to fill a gap, so it
     * must NEVER be called while holding the engine lock.
     */
    RoundPriceData resolve(Market market, RoundWindow window) throws PriceUnavailableException;

    /**
     * Open price and path so far of a window that is still running. Memory only, no network.
     * Empty when the open price is not known yet.
     */
    Optional<RoundPriceData> soFar(Market market, RoundWindow window, long nowMs);
}
