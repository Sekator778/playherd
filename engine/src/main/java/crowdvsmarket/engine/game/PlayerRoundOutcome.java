package crowdvsmarket.engine.game;

import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.round.BetMode;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.LiquidationParams;
import java.math.BigDecimal;

/**
 * One player's resolved outcome for one round, as reported by {@link GameEngine#runRound}. {@code fees} are the fees the bet
 * was charged (economy stage A); {@code null} for a legacy bet, which was settled by the original rules. {@code liquidation}: the cap and
 * kappa the bet was settled with ({@code null}: the market's, for a legacy bet or one that stored none). {@code mode}: how the bet was
 * paid; a {@link BetMode#FIXED} one has the lowest leverage as a placeholder, is never liquidated, and its {@code payout} is 2 x stake,
 * 0 or the stake.
 */
public record PlayerRoundOutcome(
        String playerId,
        Direction direction,
        int leverage,
        PlayAmount stake,
        boolean liquidated,
        BigDecimal payout,
        Fees fees,
        LiquidationParams liquidation,
        BetMode mode) {

    /** The outcome of a percentage-mode bet. */
    public PlayerRoundOutcome(String playerId, Direction direction, int leverage, PlayAmount stake, boolean liquidated,
            BigDecimal payout, Fees fees, LiquidationParams liquidation) {
        this(playerId, direction, leverage, stake, liquidated, payout, fees, liquidation, BetMode.PERCENTAGE);
    }

    public PlayerRoundOutcome(String playerId, Direction direction, int leverage, PlayAmount stake, boolean liquidated,
            BigDecimal payout) {
        this(playerId, direction, leverage, stake, liquidated, payout, null, null);
    }

    public PlayerRoundOutcome(String playerId, Direction direction, int leverage, PlayAmount stake, boolean liquidated,
            BigDecimal payout, Fees fees) {
        this(playerId, direction, leverage, stake, liquidated, payout, fees, null);
    }
}
