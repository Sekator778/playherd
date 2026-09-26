package crowdvsmarket.server.domain;

import crowdvsmarket.engine.crowd.CrowdAccount;
import crowdvsmarket.engine.execution.ExecutionAdapter;
import crowdvsmarket.engine.execution.PaperTradingAdapter;
import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Предложение (contract.ts explicitly flags {@code btcHoldCapital}/{@code antiCrowdCapital} as
 * such — "no BtcHoldAccount/AntiCrowdAccount class exists in engine/ yet"): the two comparison
 * lines on the crowd chart (Р-26), computed alongside {@link CrowdAccount} rather than inside it,
 * one point appended per resolved round (any interval), same order and same count as
 * {@code crowdAccount.history()} — both are driven from the exact same {@code RoundResolved}
 * event stream (live or replayed), so index {@code i} here always lines up with
 * {@code crowdAccount.history().get(i)}.
 *
 * <ul>
 *   <li><b>"просто держать BTC"</b>: buys BTC once, at the very first round ever resolved
 *       (any series), with the same starting capital as the crowd account; every later point
 *       revalues that fixed BTC quantity at the round's close price. Never liquidates, never
 *       re-trades.
 *   <li><b>"анти-толпа"</b>: "тот же счёт, всегда против большинства" — same starting capital,
 *       same 10%-of-capital margin sizing and same leverage as the real crowd trade that round
 *       (Р-14), but the opposite direction; simulated exactly like {@link CrowdAccount} itself
 *       (reusing {@link Position}/{@link PositionSimulator}). No trade on a round the real crowd
 *       account also did not trade (tie/no bets, Р-5).
 * </ul>
 */
@ApplicationScoped
public final class ComparisonAccounts {

    private static final String ANTI_CROWD_USER_ID = "anti-crowd";

    /** Task item 7: was a direct {@code Position.openFromMargin} + {@code PositionSimulator
     *  .simulate} call inline in {@link #onRoundResolved} below (same primitives {@link
     *  CrowdAccount} itself used before this task) — now the same two calls behind {@link
     *  ExecutionAdapter}, same default as {@link CrowdAccount}'s own no-arg/startingCapital
     *  constructors. Stateless, so one shared instance is fine. */
    private final ExecutionAdapter executionAdapter = new PaperTradingAdapter();

    private BigDecimal btcHoldCapital;
    private BigDecimal btcHoldQtyBtc;
    private BigDecimal antiCrowdCapital;
    private final List<BigDecimal> btcHoldHistory = new ArrayList<>();
    private final List<BigDecimal> antiCrowdHistory = new ArrayList<>();

    public ComparisonAccounts() {
        reset();
    }

    public synchronized void reset() {
        btcHoldCapital = CrowdAccount.STARTING_CAPITAL;
        btcHoldQtyBtc = null;
        antiCrowdCapital = CrowdAccount.STARTING_CAPITAL;
        btcHoldHistory.clear();
        antiCrowdHistory.clear();
    }

    /** @param path the exact candle path the round was resolved against (for the anti-crowd
     *              position's own liquidation check) — not carried by {@link RoundReport} itself,
     *              so the caller (which already has it, to call {@code GameEngine.runRound})
     *              passes it through. */
    public synchronized void onRoundResolved(RoundReport report, List<Candle> path) {
        BigDecimal openPrice = report.openPrice();
        BigDecimal closePrice = report.closePrice();

        if (btcHoldQtyBtc == null) {
            btcHoldQtyBtc = btcHoldCapital.divide(openPrice, MathContext.DECIMAL128);
        }
        btcHoldCapital = btcHoldQtyBtc.multiply(closePrice, MathContext.DECIMAL128).setScale(2, RoundingMode.HALF_UP);
        btcHoldHistory.add(btcHoldCapital);

        CrowdAccount.RoundResult crowd = report.crowdResult();
        if (crowd.traded()) {
            Direction antiDirection = crowd.direction() == Direction.UP ? Direction.DOWN : Direction.UP;
            BigDecimal margin = antiCrowdCapital
                    .multiply(CrowdAccount.MARGIN_SHARE_OF_CAPITAL, MathContext.DECIMAL128)
                    .setScale(2, RoundingMode.HALF_UP);
            Position position = executionAdapter.openPosition(
                    ANTI_CROWD_USER_ID, report.window().roundId(), antiDirection,
                    new Leverage(crowd.leverage()), margin, openPrice);
            PositionSimulator.Outcome outcome = executionAdapter.closePosition(position, path);
            if (outcome instanceof PositionSimulator.Outcome.Liquidated) {
                antiCrowdCapital = antiCrowdCapital.subtract(margin);
            } else {
                antiCrowdCapital = antiCrowdCapital.add(((PositionSimulator.Outcome.Settled) outcome).pnl());
            }
        }
        antiCrowdHistory.add(antiCrowdCapital);
    }

    public synchronized List<BigDecimal> btcHoldHistory() {
        return List.copyOf(btcHoldHistory);
    }

    public synchronized List<BigDecimal> antiCrowdHistory() {
        return List.copyOf(antiCrowdHistory);
    }
}
