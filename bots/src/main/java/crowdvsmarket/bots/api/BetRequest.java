package crowdvsmarket.bots.api;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.round.BetMode;
import crowdvsmarket.engine.round.Direction;
import java.util.Objects;

/**
 * A bet a bot asks to place: one bet per bot per round of a market.
 *
 * @param mode {@link BetMode#FIXED} for a win-or-lose bet (D-106: no leverage, the adapter sends the mode to the game); {@code null}
 *             leaves the field out, which the game reads as the percentage mode
 */
public record BetRequest(String botId, String marketId, String roundId, Direction direction, PlayAmount stake, int leverage,
                         BetMode mode) {

    public BetRequest {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(marketId, "marketId");
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(stake, "stake");
    }

    /** A bet without a mode: the percentage mode. */
    public BetRequest(String botId, String marketId, String roundId, Direction direction, PlayAmount stake, int leverage) {
        this(botId, marketId, roundId, direction, stake, leverage, null);
    }

    public boolean isFixed() {
        return mode == BetMode.FIXED;
    }
}
