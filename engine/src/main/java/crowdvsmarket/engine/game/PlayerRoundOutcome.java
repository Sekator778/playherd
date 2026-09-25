package crowdvsmarket.engine.game;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.round.Direction;
import java.math.BigDecimal;

/** One player's resolved outcome for one round, as reported by {@link GameEngine#runRound}. */
public record PlayerRoundOutcome(
        String playerId,
        Direction direction,
        int leverage,
        PlayAmount stake,
        boolean liquidated,
        BigDecimal payout) {
}
