package crowdvsmarket.engine.crowd;

import crowdvsmarket.engine.execution.ExecutionAdapter;
import crowdvsmarket.engine.execution.PaperTradingAdapter;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.MarketSpec;
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
import java.util.Objects;

/**
 * The public "счёт толпы" (crowd account, Р-5, Р-14, Р-17): one paper-USD position per round,
 * opened on the majority side of that round's player bets and sized as 10% of the account's own
 * running capital — entirely independent of player balances (Р-5: "на балансы игроков не
 * влияет", it is a display/scoreboard account, not something players pay into or out of).
 *
 * <p>One account per market (Р-69): it trades its {@link MarketSpec}'s market, with that market's
 * maintenance margin rate and leverage cap. The constructors without a spec are the BTC account,
 * as before.
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
    private final ExecutionAdapter executionAdapter;
    private final MarketSpec spec;

    public CrowdAccount() {
        this(STARTING_CAPITAL);
    }

    public CrowdAccount(BigDecimal startingCapital) {
        this(startingCapital, new PaperTradingAdapter());
    }

    /**
     * Task item 7: same account, executed through an explicit {@link ExecutionAdapter} instead of
     * always the default {@link PaperTradingAdapter} — e.g. a future caller that wants the crowd
     * account's majority-side trade actually placed on a real exchange (Р-38+, out of scope for
     * this task) constructs one with {@code new CrowdAccount(startingCapital, hyperliquidAdapter)}
     * instead. {@link #CrowdAccount(BigDecimal)}/{@link #CrowdAccount()} are unchanged and still
     * default to paper trading, so every existing caller keeps its exact prior behavior.
     */
    public CrowdAccount(BigDecimal startingCapital, ExecutionAdapter executionAdapter) {
        this(startingCapital, executionAdapter, MarketSpec.BTC);
    }

    /**
     * The crowd account of {@code spec}'s market (Р-69): positions open through
     * {@code executionAdapter.openPosition(spec, …)} and the stake-weighted leverage is clamped to
     * the market's own cap, so a 25x market never produces a 40x crowd position. The shorter
     * constructors are this one with {@link MarketSpec#BTC}.
     */
    public CrowdAccount(BigDecimal startingCapital, ExecutionAdapter executionAdapter, MarketSpec spec) {
        this.capital = startingCapital;
        this.executionAdapter = executionAdapter;
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    /** The market this account trades. */
    public MarketSpec spec() {
        return spec;
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
     * HALF_UP and clamped to [2, the market's cap] — [2,40] for BTC (Р-14), margin = 10% of
     * current capital, entered at {@code openPrice} and monitored for liquidation across
     * {@code path} exactly like a player position (Р-8, reusing {@code PositionSimulator}). A tie
     * (including zero bets on both sides) makes no trade.
     */
    public RoundResult processRound(String roundId, Collection<Bet> bets, BigDecimal openPrice, List<Candle> path) {
        BigDecimal capitalBefore = capital;
        Majority majority = majoritySide(bets);
        if (majority == null) {
            RoundResult result = new RoundResult(roundId, false, null, null, null, null, false, capitalBefore, capitalBefore);
            history.add(result);
            return result;
        }

        int leverage = weightedLeverage(majority.bets(), spec);
        BigDecimal margin = capital.multiply(MARGIN_SHARE_OF_CAPITAL, MathContext.DECIMAL128).setScale(2, RoundingMode.HALF_UP);
        // Task item 7: was `Position.openFromMargin(...)` + `PositionSimulator.simulate(...)`
        // called directly here — now the same two calls, behind ExecutionAdapter (see that
        // interface's javadoc); PaperTradingAdapter (the default, via the other constructors)
        // does exactly this and nothing else, so this is a byte-identical behavior change.
        Position position = executionAdapter.openPosition(
                spec, CROWD_USER_ID, roundId, majority.direction(), new Leverage(leverage), margin, openPrice);
        PositionSimulator.Outcome outcome = executionAdapter.closePosition(position, path);

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

    /** Stake-weighted average leverage of the majority side, HALF_UP to the nearest integer, clamped
     * to [2, the market's cap] — [2,40] for BTC (Р-14). */
    private static int weightedLeverage(List<Bet> sideBets, MarketSpec spec) {
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal totalStake = BigDecimal.ZERO;
        for (Bet bet : sideBets) {
            BigDecimal stake = bet.stake().toMoney();
            weightedSum = weightedSum.add(BigDecimal.valueOf(bet.leverage().value()).multiply(stake));
            totalStake = totalStake.add(stake);
        }
        BigDecimal average = weightedSum.divide(totalStake, MathContext.DECIMAL128);
        int rounded = average.setScale(0, RoundingMode.HALF_UP).intValueExact();
        return spec.clampLeverage(rounded);
    }
}
