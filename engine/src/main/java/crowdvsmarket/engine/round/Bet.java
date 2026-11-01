package crowdvsmarket.engine.round;

import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;

/**
 * A player's pending stake on a round, before the round starts (D-13: one position per player per
 * round, modifiable/cancellable — with a refund — until start). {@code stake} is PLAY, pegged 1:1
 * to USD margin (see {@link PlayAmount}).
 *
 * <p>{@code fees} are the herd and network fees charged with the stake (economy stage A, plan A4). {@code null}
 * marks a <b>legacy</b> bet: one placed before the economy change (or replayed from an event written before it) —
 * it was charged no fees and keeps being settled by the original rules (a liquidation pays 0, the market's single cap
 * and {@code l = 1/(2 cap)}, no payout cap), so replaying an old event log gives the old balances. Every bet placed by the
 * server now has fees (possibly zero) and follows the new rules. {@code liquidation} (economy stage B) are the cap and kappa the bet was
 * placed with, used to settle it whatever the market's parameters are by then; {@code null} when the event did not store them.
 */
public record Bet(String playerId, String roundId, Direction direction, Leverage leverage, PlayAmount stake, Fees fees,
                  LiquidationParams liquidation, BetMode mode) {

    /** The leverage a {@link BetMode#FIXED} bet carries: it has none, the record needs a valid value, and the crowd account weighs it as the lowest. */
    public static final Leverage FIXED_LEVERAGE = new Leverage(Leverage.MIN);

    public Bet {
        java.util.Objects.requireNonNull(mode, "mode");
        if (mode == BetMode.FIXED && (fees == null || liquidation != null || leverage.value() != Leverage.MIN)) {
            throw new IllegalArgumentException("a fixed-payout bet has fees, no liquidation parameters and no leverage");
        }
    }

    /** A percentage-mode bet (the original one) with fees and stored liquidation parameters. */
    public Bet(String playerId, String roundId, Direction direction, Leverage leverage, PlayAmount stake, Fees fees,
               LiquidationParams liquidation) {
        this(playerId, roundId, direction, leverage, stake, fees, liquidation, BetMode.PERCENTAGE);
    }

    /** A fixed-payout bet (D-106): no leverage, no liquidation; {@code fees} are charged on top of the stake. */
    public static Bet fixed(String playerId, String roundId, Direction direction, PlayAmount stake, Fees fees) {
        return new Bet(playerId, roundId, direction, FIXED_LEVERAGE, stake, fees, null, BetMode.FIXED);
    }

    public boolean isFixed() {
        return mode == BetMode.FIXED;
    }

    /** A legacy bet (no fees, original rules). */
    public Bet(String playerId, String roundId, Direction direction, Leverage leverage, PlayAmount stake) {
        this(playerId, roundId, direction, leverage, stake, null, null);
    }

    /** A bet with fees whose liquidation parameters were not stored (placed by stage A before they were): the market's current ones apply. */
    public Bet(String playerId, String roundId, Direction direction, Leverage leverage, PlayAmount stake, Fees fees) {
        this(playerId, roundId, direction, leverage, stake, fees, null);
    }

    public boolean isLegacy() {
        return fees == null;
    }

    /** The spec this bet is opened and simulated with: the market's as it was before the economy change for a legacy bet,
     *  the one of {@code interval}'s round length otherwise. */
    public crowdvsmarket.engine.market.MarketSpec specFor(
            crowdvsmarket.engine.market.MarketSpec market, crowdvsmarket.engine.market.Interval interval) {
        return specFor(isLegacy(), liquidation, market, interval);
    }

    /** {@link #specFor(crowdvsmarket.engine.market.MarketSpec, crowdvsmarket.engine.market.Interval)} for a bet known by its parts. */
    public static crowdvsmarket.engine.market.MarketSpec specFor(boolean legacy, LiquidationParams liquidation,
            crowdvsmarket.engine.market.MarketSpec market, crowdvsmarket.engine.market.Interval interval) {
        if (legacy) {
            return market;
        }
        return liquidation == null ? market.forInterval(interval) : market.forCap(liquidation.cap(), liquidation.kappa());
    }
}
