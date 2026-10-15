package crowdvsmarket.server.domain;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.execution.ExecutionAdapter;
import crowdvsmarket.engine.execution.PaperTradingAdapter;
import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.margin.LiquidationCalculator;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Предложение (contract.ts explicitly flags {@code holdCapital}/{@code antiCrowdCapital} as
 * such — "no HoldAccount/AntiCrowdAccount class exists in engine/ yet"): the two comparison
 * lines on a market's crowd chart (Р-26), computed alongside that market's {@link CrowdAccount}
 * rather than inside it, one point appended per resolved round (any interval), same order and
 * same count as {@code crowdAccount.history()} — both are driven from the exact same
 * {@code RoundResolved} event stream (live or replayed), so index {@code i} here always lines up
 * with {@code crowdAccount.history().get(i)} of the same market.
 *
 * <p>One instance per market (Т-6, docs/research/markets_architecture.md §2): {@link EngineHub}
 * owns them, next to the market's crowd account and engines; the class itself is a plain object,
 * not a CDI bean. All prices are engine (lot) prices — the same unit the market's crowd account
 * trades in — and both lines are scale-invariant, so the lot scaling (Т-3) does not matter here.
 *
 * <ul>
 *   <li><b>"просто держать актив"</b>: buys the asset once, at the very first round ever resolved
 *       for this market (any series), with the same starting capital as the crowd account; every
 *       later point revalues that fixed quantity at the round's close price. Never liquidates,
 *       never re-trades.
 *   <li><b>"анти-толпа"</b>: "тот же счёт, всегда против большинства" — same starting capital,
 *       same 10%-of-capital margin sizing and same leverage as the real crowd trade that round
 *       (Р-14), but the opposite direction; simulated exactly like {@link CrowdAccount} itself
 *       (through {@link ExecutionAdapter}, opened with the market's {@link MarketSpec}). No trade
 *       on a round the real crowd account also did not trade (tie/no bets, Р-5).
 * </ul>
 */
public final class ComparisonAccounts {

    private static final String ANTI_CROWD_USER_ID = "anti-crowd";

    /** Same two calls {@link CrowdAccount} makes behind {@link ExecutionAdapter} (task item 7);
     *  stateless, so one instance per market is plenty. */
    private final ExecutionAdapter executionAdapter = new PaperTradingAdapter();

    private final MarketSpec spec;
    private BigDecimal holdCapital = CrowdAccount.STARTING_CAPITAL;
    private BigDecimal holdQty;
    private BigDecimal antiCrowdCapital = CrowdAccount.STARTING_CAPITAL;
    private final List<BigDecimal> holdHistory = new ArrayList<>();
    private final List<BigDecimal> antiCrowdHistory = new ArrayList<>();

    public ComparisonAccounts(MarketSpec spec) {
        this.spec = Objects.requireNonNull(spec, "spec");
    }

    /** @param path the exact candle path the round was resolved against (for the anti-crowd
     *              position's own liquidation check) — not carried by {@link RoundReport} itself,
     *              so the caller (which already has it, to call {@code GameEngine.runRound})
     *              passes it through. */
    public synchronized void onRoundResolved(RoundReport report, List<Candle> path) {
        BigDecimal openPrice = report.openPrice();
        BigDecimal closePrice = report.closePrice();

        if (holdQty == null) {
            holdQty = holdCapital.divide(openPrice, MathContext.DECIMAL128);
        }
        holdCapital = holdQty.multiply(closePrice, MathContext.DECIMAL128).setScale(2, RoundingMode.HALF_UP);
        holdHistory.add(holdCapital);

        CrowdAccount.RoundResult crowd = report.crowdResult();
        if (crowd.traded()) {
            Direction antiDirection = crowd.direction() == Direction.UP ? Direction.DOWN : Direction.UP;
            BigDecimal margin = antiCrowdCapital
                    .multiply(CrowdAccount.MARGIN_SHARE_OF_CAPITAL, MathContext.DECIMAL128)
                    .setScale(2, RoundingMode.HALF_UP);
            // the line follows the crowd account's rules: under the economy-stage-A ones the round length's cap and
            // l = kappa / cap, and a liquidated position keeps the remainder at the candle extreme
            MarketSpec positionSpec = report.economyRules() ? spec.forInterval(report.window().interval()) : spec;
            Position position = executionAdapter.openPosition(positionSpec, ANTI_CROWD_USER_ID, report.window().roundId(),
                    antiDirection, new Leverage(crowd.leverage()), margin, openPrice);
            PositionSimulator.Outcome outcome = executionAdapter.closePosition(position, path);
            if (outcome instanceof PositionSimulator.Outcome.Liquidated liquidated) {
                BigDecimal remainder = report.economyRules()
                        ? LiquidationCalculator.payoutAtCandleExtreme(position.size(), position.entryPrice(),
                                position.leverage(), position.direction().toSide(), liquidated.extremePrice())
                        : BigDecimal.ZERO;
                antiCrowdCapital = antiCrowdCapital.subtract(margin).add(remainder);
            } else {
                antiCrowdCapital = antiCrowdCapital.add(((PositionSimulator.Outcome.Settled) outcome).pnl());
            }
        }
        antiCrowdHistory.add(antiCrowdCapital);
    }

    public synchronized List<BigDecimal> holdHistory() {
        return List.copyOf(holdHistory);
    }

    public synchronized List<BigDecimal> antiCrowdHistory() {
        return List.copyOf(antiCrowdHistory);
    }
}
