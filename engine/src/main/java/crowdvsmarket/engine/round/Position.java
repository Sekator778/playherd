package crowdvsmarket.engine.round;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.margin.LiquidationCalculator;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * A crowd member's position in a round, matching the {@code Position} data format (PLAN.md
 * section 3):
 *
 * <pre>
 * Position { user_id, round_id, direction: up|down, leverage: 2..40,
 *            size, entry_price, liquidation_price, pnl }
 * </pre>
 *
 * <p>{@code pnl} is {@code null} for a freshly opened position (unknown until the round resolves
 * or the position is liquidated); {@link #withPnl} produces the settled record. Engine decision:
 * {@code pnl} here is the realized profit/loss in USD, i.e. {@code payout - initialMargin} (can be
 * negative) — {@link PositionSimulator} is what computes it, from the spec's payout formulas
 * (doc section 2.2).
 */
public record Position(
        String userId,
        String roundId,
        Direction direction,
        Leverage leverage,
        BigDecimal size,
        BigDecimal entryPrice,
        BigDecimal liquidationPrice,
        BigDecimal pnl) {

    public Position {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(leverage, "leverage");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(liquidationPrice, "liquidationPrice");
        // pnl is intentionally nullable: unknown until the position is settled/liquidated.
    }

    /** Opens a new position, deriving {@code liquidationPrice} from the spec's formula. */
    public static Position open(
            String userId,
            String roundId,
            Direction direction,
            Leverage leverage,
            BigDecimal size,
            BigDecimal entryPrice) {
        BigDecimal liquidationPrice =
                LiquidationCalculator.liquidationPrice(size, entryPrice, leverage, direction.toSide());
        return new Position(userId, roundId, direction, leverage, size, entryPrice, liquidationPrice, null);
    }

    /**
     * Opens a position from a PLAY margin rather than a raw BTC size — {@code size} is derived via
     * {@link PositionSizing} so that {@code margin} IS the position's initial margin exactly (see
     * that class's javadoc and docs/research/round_engine_design.md "Units"). This is the factory
     * the round engine ({@code Bet}/{@code GameEngine}) and the crowd account use; {@link #open} is
     * unchanged and still takes a raw size directly.
     */
    public static Position openFromMargin(
            String userId,
            String roundId,
            Direction direction,
            Leverage leverage,
            BigDecimal margin,
            BigDecimal entryPrice) {
        BigDecimal size = PositionSizing.sizeForMargin(margin, leverage, entryPrice);
        return open(userId, roundId, direction, leverage, size, entryPrice);
    }

    public boolean isSettled() {
        return pnl != null;
    }

    public Position withPnl(BigDecimal pnl) {
        return new Position(userId, roundId, direction, leverage, size, entryPrice, liquidationPrice, pnl);
    }
}
