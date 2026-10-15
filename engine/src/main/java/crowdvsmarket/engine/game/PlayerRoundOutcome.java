package crowdvsmarket.engine.game;

import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.round.Direction;
import java.math.BigDecimal;

/**
 * One player's resolved outcome for one round, as reported by {@link GameEngine#runRound}. {@code fees} are the fees the bet
 * was charged (economy stage A); {@code null} for a legacy bet, which was settled by the original rules.
 */
public record PlayerRoundOutcome(
        String playerId,
        Direction direction,
        int leverage,
        PlayAmount stake,
        boolean liquidated,
        BigDecimal payout,
        Fees fees) {

    public PlayerRoundOutcome(String playerId, Direction direction, int leverage, PlayAmount stake, boolean liquidated,
            BigDecimal payout) {
        this(playerId, direction, leverage, stake, liquidated, payout, null);
    }
}
