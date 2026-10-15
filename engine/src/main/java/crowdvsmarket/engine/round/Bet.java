package crowdvsmarket.engine.round;

import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;

/**
 * A player's pending stake on a round, before the round starts (Р-13: one position per player per
 * round, modifiable/cancellable — with a refund — until start). {@code stake} is PLAY, pegged 1:1
 * to USD margin (see {@link PlayAmount}).
 *
 * <p>{@code fees} are the herd and network fees charged with the stake (economy stage A, plan A4). {@code null}
 * marks a <b>legacy</b> bet: one placed before the economy change (or replayed from an event written before it) —
 * it was charged no fees and keeps being settled by the original rules (a liquidation pays 0, the market's single cap
 * and {@code l = 1/(2 cap)}, no payout cap), so replaying an old event log gives the old balances. Every bet placed by the
 * server now has fees (possibly zero) and follows the new rules.
 */
public record Bet(String playerId, String roundId, Direction direction, Leverage leverage, PlayAmount stake, Fees fees) {

    /** A legacy bet (no fees, original rules). */
    public Bet(String playerId, String roundId, Direction direction, Leverage leverage, PlayAmount stake) {
        this(playerId, roundId, direction, leverage, stake, null);
    }

    public boolean isLegacy() {
        return fees == null;
    }

    /** The spec this bet is opened and simulated with: the market's as it was before the economy change for a legacy bet,
     *  the one of {@code interval}'s round length otherwise. */
    public crowdvsmarket.engine.market.MarketSpec specFor(
            crowdvsmarket.engine.market.MarketSpec market, crowdvsmarket.engine.market.Interval interval) {
        return isLegacy() ? market : market.forInterval(interval);
    }
}
