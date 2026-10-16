package crowdvsmarket.bots.brain;

import crowdvsmarket.bots.api.BetRequest;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.round.Direction;

/**
 * What the brain wants to do: a bet, within the game's rules at the time of the decision.
 *
 * @param confidence the signal's confidence (0 for a coin or a fallback)
 * @param fallback   the strategy was silent and the persona chose a fallback signal
 */
public record BetIntent(String botId, String marketId, String roundId, Direction direction, PlayAmount stake, int leverage,
                        double confidence, boolean fallback) {

    public BetRequest toRequest() {
        return new BetRequest(botId, marketId, roundId, direction, stake, leverage);
    }
}
