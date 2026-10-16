package crowdvsmarket.server.prices;

import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import java.util.List;
import java.util.Optional;

/**
 * Live prices of the listed markets (mode=live) — docs/research/markets_architecture.md §3.2.
 * Not used in mode=replay.
 *
 * <p>A market with a backup exchange ({@link Instruments}, Р-76) is priced on two venues, and a round runs entirely on
 * one of them, chosen once when it opens: {@link #openRound} chooses, {@link #decidedVenue} says what was chosen,
 * {@link #resolve} and {@link #soFar} answer from that venue alone, and {@link #latest(Market)} shows the primary's
 * price while it is fresh and the backup's when it is not. For a market without a backup none of this exists. A round
 * whose exchange stays silent is not moved to the other one: it waits, and the scheduler lets it go — cancels it with a refund,
 * so that the series goes on — when {@link #otherVenueLive} says the other exchange is alive (Р-77).
 */
public interface MarketPrices {

    /** The newest live tick of exactly this key's ring: a market's primary venue, or a shadow's ({@code spot_BTC~kraken}). */
    Optional<PriceQuote> latest(String marketId);

    /**
     * What the lobby, the bots and the bet slip show: {@link #latest(String)} for a market with one venue; for a market with a
     * backup, the primary's latest live tick while it is fresh, else the backup's while that is, each quote saying which venue
     * it is from ({@link PriceQuote#venue}); with neither fresh, the newer of the two.
     */
    default Optional<PriceQuote> latest(Market market) {
        return latest(market.id());
    }

    /** Ticks with {@code fromMs <= atMs < toMs}, oldest first. Memory only. */
    List<PricePoint> series(String marketId, long fromMs, long toMs);

    /**
     * The finished 1-minute candles of exactly this key's live ticks (a market's primary venue, like {@link #series}) among the
     * last {@code minutes} minutes before the minute {@code nowMs} is in, oldest first; a minute without ticks has no candle.
     * Memory only, O(minutes), no lock of the engine. The default is none: a price source without a candle read-model.
     */
    default List<PriceBar> bars(String marketId, int minutes, long nowMs) {
        return List.of();
    }

    /**
     * Settlement data of a finished window. May ask the source for history to fill a gap, so it
     * must NEVER be called while holding the engine lock. For a market with a backup it decides the round's venue first
     * when nothing has ({@link #openRound}), then settles from that venue alone; {@link PriceUnavailableException} when
     * no venue can price the round's start or the chosen one cannot price the round.
     */
    RoundPriceData resolve(Market market, RoundWindow window) throws PriceUnavailableException;

    /**
     * Open price and path so far of a window that is still running. Memory only, no network.
     * Empty when the open price is not known yet — and, for a market with a backup, while the round has no venue.
     */
    Optional<RoundPriceData> soFar(Market market, RoundWindow window, long nowMs);

    /**
     * The exchange the round of a market with a backup runs on, once decided — memory only; empty while it is not, and
     * for a market without a backup (it has only its own source).
     */
    default Optional<String> decidedVenue(Market market, RoundWindow window) {
        return Optional.empty();
    }

    /**
     * Opens the round at {@code nowMs}, after its start: chooses the exchange it runs on if that has not been chosen yet —
     * the primary when it has a live tick fresh at the round's start, else the backup when it has; with no live tick on either
     * side, whichever ring can price the start from what it holds (seeded history, a carried tick, a pin — non-live evidence of
     * one rank), the primary's first; else neither yet — and returns it.
     * The scheduler calls it for every running round; it writes the choice once and may block on the database, so never under
     * the engine lock. Empty while no exchange can price the start, and for a market without a backup.
     */
    default Optional<String> openRound(Market market, RoundWindow window, long nowMs) {
        return decidedVenue(market, window);
    }

    /**
     * Whether the market's <i>other</i> exchange is alive — the half of the venue release (Р-77) that is about prices: the
     * round of a market with a backup was decided on one of its two exchanges, and the other one, the primary if the round
     * runs on the backup and the backup if it runs on the primary, has a <i>live</i> tick that is fresh at {@code nowMs} (a
     * poll's answer no older than the feed's staleness limit; history, a carried tick and a pin do not count). The scheduler
     * asks it for a round it cannot settle because its own exchange gives no price, once the round has waited long enough
     * ({@link PricesConfig#venueReleaseAfterMs()}): with the other exchange alive the series can go on without the round, so
     * the round is cancelled with a refund instead of holding the series for the hour of Р-23.
     *
     * <p>{@code false} for a market without a backup, for a round whose exchange is not decided (nothing says which one is the
     * other), for a decision this configuration cannot read, and for an exchange that is switched off. May block on the
     * database (the table of decisions, for a round that is not in memory), so never under the engine lock.
     */
    default boolean otherVenueLive(Market market, RoundWindow window, long nowMs) {
        return false;
    }
}
