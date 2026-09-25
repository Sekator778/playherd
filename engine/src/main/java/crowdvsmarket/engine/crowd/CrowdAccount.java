package crowdvsmarket.engine.crowd;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The public "счёт толпы" (crowd account, Р-5, Р-14, Р-17): one paper-USD position per round,
 * opened on the majority side of that round's player bets and sized as 10% of the account's own
 * running capital — entirely independent of player balances (Р-5: "на балансы игроков не
 * влияет", it is a display/scoreboard account, not something players pay into or out of).
 *
 * <p>Not thread-safe (capital/history mutate per {@link #processRound}), matching the rest of this
 * engine's single-threaded, functional-core-mutable-shell style.
 */
public final class CrowdAccount {

    /** Starting paper capital (Р-17). */
    public static final BigDecimal STARTING_CAPITAL = new BigDecimal("100000.00");
    /** Share of current capital risked as margin each round (Р-17). */
    public static final BigDecimal MARGIN_SHARE_OF_CAPITAL = new BigDecimal("0.10");

    private static final String CROWD_USER_ID = "crowd";
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    /** One round's crowd-account outcome. {@code traded=false} (all other fields {@code null}) for
     * a tie or no positions (Р-5). */
    public record RoundResult(
            String roundId,
            boolean traded,
            Direction direction,
            Integer leverage,
            BigDecimal margin,
            BigDecimal entryPrice,
            boolean liquidated,
            BigDecimal capitalBefore,
            BigDecimal capitalAfter) {
    }

    private BigDecimal capital;
    private final List<RoundResult> history = new ArrayList<>();

    public CrowdAccount() {
        this(STARTING_CAPITAL);
    }

    public CrowdAccount(BigDecimal startingCapital) {
        this.capital = startingCapital;
    }

    public BigDecimal capital() {
        return capital;
    }

    /** Capital after every round processed so far, oldest first — the source for the "толпа против рынка" chart (Р-17). */
    public List<RoundResult> history() {
        return List.copyOf(history);
    }

    /**
     * Computes and applies this round's crowd trade from the round's player bets: majority side
     * by SUM OF STAKES (Р-5), leverage = stake-weighted average of the majority side, rounded
     * HALF_UP and clamped to [2,40] (Р-14), margin = 10% of current capital, entered at
     * {@code openPrice} and monitored for liquidation across {@code path} exactly like a player
     * position (Р-8, reusing {@code PositionSimulator}). A tie (including zero bets on both sides)
     * makes no trade.
     */
    public RoundResult processRound(String roundId, Collection<Bet> bets, BigDecimal openPrice, List<Candle> path) {
        BigDecimal capitalBefore = capital;
        Majority majority = majoritySide(bets);
        if (majority == null) {
            RoundResult result = new RoundResult(roundId, false, null, null, null, null, false, capitalBefore, capitalBefore);
            history.add(result);
            return result;
        }

        int leverage = weightedLeverage(majority.bets());
        BigDecimal margin = capital.multiply(MARGIN_SHARE_OF_CAPITAL, MathContext.DECIMAL128).setScale(2, RoundingMode.HALF_UP);
        Position position = Position.openFromMargin(
                CROWD_USER_ID, roundId, majority.direction(), new Leverage(leverage), margin, openPrice);
        PositionSimulator.Outcome outcome = PositionSimulator.simulate(position, path);

        boolean liquidated = outcome instanceof PositionSimulator.Outcome.Liquidated;
        BigDecimal capitalAfter = liquidated
                ? capitalBefore.subtract(margin)
                : capitalBefore.add(((PositionSimulator.Outcome.Settled) outcome).pnl());
        capital = capitalAfter;

        RoundResult result = new RoundResult(
                roundId, true, majority.direction(), leverage, margin, openPrice, liquidated, capitalBefore, capitalAfter);
        history.add(result);
        return result;
    }

    private record Majority(Direction direction, List<Bet> bets) {
    }

    private static Majority majoritySide(Collection<Bet> bets) {
        BigDecimal upStake = ZERO_MONEY;
        BigDecimal downStake = ZERO_MONEY;
        List<Bet> upBets = new ArrayList<>();
        List<Bet> downBets = new ArrayList<>();
        for (Bet bet : bets) {
            BigDecimal stake = bet.stake().toMoney();
            if (bet.direction() == Direction.UP) {
                upStake = upStake.add(stake);
                upBets.add(bet);
            } else {
                downStake = downStake.add(stake);
                downBets.add(bet);
            }
        }
        int cmp = upStake.compareTo(downStake);
        if (cmp == 0) {
            return null; // tie, including 0 vs 0 (no bets) -> no trade, Р-5
        }
        return cmp > 0 ? new Majority(Direction.UP, upBets) : new Majority(Direction.DOWN, downBets);
    }

    /** Stake-weighted average leverage of the majority side, HALF_UP to the nearest integer, clamped [2,40] (Р-14). */
    private static int weightedLeverage(List<Bet> sideBets) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal totalStake = BigDecimal.ZERO;
        for (Bet bet : sideBets) {
            BigDecimal stake = bet.stake().toMoney();
            weightedSum = weightedSum.add(BigDecimal.valueOf(bet.leverage().value()).multiply(stake));
            totalStake = totalStake.add(stake);
        }
        BigDecimal average = weightedSum.divide(totalStake, MathContext.DECIMAL128);
        int rounded = average.setScale(0, RoundingMode.HALF_UP).intValueExact();
        return Math.max(Leverage.MIN, Math.min(Leverage.MAX, rounded));
    }
}
