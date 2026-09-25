package crowdvsmarket.engine.round;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;

/**
 * A player's pending stake on a round, before the round starts (Р-13: one position per player per
 * round, modifiable/cancellable — with a refund — until start). {@code stake} is PLAY, pegged 1:1
 * to USD margin (see {@link PlayAmount}).
 */
public record Bet(String playerId, String roundId, Direction direction, Leverage leverage, PlayAmount stake) {
}
