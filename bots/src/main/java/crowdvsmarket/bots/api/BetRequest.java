package crowdvsmarket.bots.api;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.round.Direction;
import java.util.Objects;

/** A bet a bot asks to place: one bet per bot per round of a market. */
public record BetRequest(String botId, String marketId, String roundId, Direction direction, PlayAmount stake, int leverage) {

    public BetRequest {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(marketId, "marketId");
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(stake, "stake");
    }
}
