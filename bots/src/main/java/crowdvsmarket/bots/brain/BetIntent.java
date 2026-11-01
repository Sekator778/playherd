package crowdvsmarket.bots.brain;

import crowdvsmarket.bots.api.BetRequest;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.round.BetMode;
import crowdvsmarket.engine.round.Direction;

/**
 * What the brain wants to do: a bet, within the game's rules at the time of the decision.
 *
 * @param confidence the signal's confidence (0 for a coin or a fallback)
 * @param fallback   the strategy was silent and the persona chose a fallback signal
 * @param mode       how the bet is paid (D-106); {@link BetMode#FIXED} only where the market allows it. The leverage of a fixed
 *                   bet is the persona's sizing (it keeps the persona's state moving as before) but is not sent as such
 */
public record BetIntent(String botId, String marketId, String roundId, Direction direction, PlayAmount stake, int leverage,
                        double confidence, boolean fallback, BetMode mode) {

    public BetIntent {
        if (mode == null) {
            mode = BetMode.PERCENTAGE;
        }
    }

    /** An intent in the percentage mode. */
    public BetIntent(String botId, String marketId, String roundId, Direction direction, PlayAmount stake, int leverage,
                     double confidence, boolean fallback) {
        this(botId, marketId, roundId, direction, stake, leverage, confidence, fallback, BetMode.PERCENTAGE);
    }

    /** The request: the mode only for a fixed bet (and then the lowest valid leverage, which the game ignores). */
    public BetRequest toRequest() {
        if (mode == BetMode.FIXED) {
            return new BetRequest(botId, marketId, roundId, direction, stake, Leverage.MIN, BetMode.FIXED);
        }
        return new BetRequest(botId, marketId, roundId, direction, stake, leverage);
    }
}
