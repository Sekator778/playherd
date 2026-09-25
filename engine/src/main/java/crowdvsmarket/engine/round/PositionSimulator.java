package crowdvsmarket.engine.round;

import crowdvsmarket.engine.margin.LiquidationCalculator;
import crowdvsmarket.engine.margin.Side;
import crowdvsmarket.engine.market.Candle;
import java.math.BigDecimal;
import java.util.List;

/**
 * Replays a {@link Position} through a round's candle path and determines its outcome, per
 * docs/research/hl_liquidation_math.md section 2.3:
 *
 * <ul>
 *   <li>Long is liquidated in a candle iff {@code candle.low <= liquidationPrice}.
 *   <li>Short is liquidated in a candle iff {@code candle.high >= liquidationPrice}.
 *   <li>Same-candle rule: a candle that is both the round's closing candle and triggers
 *       liquidation is treated as a liquidation, not a settlement (checked first for every candle
 *       in the path, including the last — so this falls out of the loop order below without a
 *       special case).
 *   <li>Gap-through rule: the {@code low <= liq}/{@code high >= liq} test already covers a candle
 *       gapping straight past the level; execution is always at {@code liquidationPrice}
 *       regardless of overshoot (no order-book data to model slippage).
 * </ul>
 */
public final class PositionSimulator {

    private PositionSimulator() {
    }

    /** Sealed outcome of replaying a position through its round's candle path. */
    public sealed interface Outcome {

        /** The position was liquidated inside {@code candle.openTimeMs()}; {@code loss} is USD lost (>= 0). */
        record Liquidated(long candleOpenTimeMs, BigDecimal loss) implements Outcome {
        }

        /** The round closed without liquidation; {@code pnl} may be negative (but payout floors at 0). */
        record Settled(BigDecimal closePrice, BigDecimal pnl) implements Outcome {
        }
    }

    /**
     * @param position an open position (as produced by {@link Position#open})
     * @param path     the round's candle path, in chronological order, at least one candle
     */
    public static Outcome simulate(Position position, List<Candle> path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("candle path must not be empty");
        }
        Side side = position.direction().toSide();
        BigDecimal liqPrice = position.liquidationPrice();

        for (Candle candle : path) {
            boolean liquidated = switch (side) {
                case LONG -> candle.low().compareTo(liqPrice) <= 0;
                case SHORT -> candle.high().compareTo(liqPrice) >= 0;
            };
            if (liquidated) {
                BigDecimal initialMargin = LiquidationCalculator.initialMargin(
                        position.size(), position.entryPrice(), position.leverage());
                BigDecimal payout = LiquidationCalculator.payoutOnLiquidation(position.size(), position.entryPrice());
                BigDecimal loss = initialMargin.subtract(payout);
                return new Outcome.Liquidated(candle.openTimeMs(), loss);
            }
        }

        Candle last = path.get(path.size() - 1);
        BigDecimal closePrice = last.close();
        BigDecimal initialMargin = LiquidationCalculator.initialMargin(
                position.size(), position.entryPrice(), position.leverage());
        BigDecimal payout = LiquidationCalculator.payoutAtRoundClose(
                position.size(), position.entryPrice(), position.leverage(), side, closePrice);
        BigDecimal pnl = payout.subtract(initialMargin);
        return new Outcome.Settled(closePrice, pnl);
    }
}
