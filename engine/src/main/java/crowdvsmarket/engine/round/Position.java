package crowdvsmarket.engine.round;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.margin.LiquidationCalculator;
import crowdvsmarket.engine.market.MarketSpec;
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
 *
 * <p>The record's components do not depend on the market: only {@code liquidationPrice} does, and
 * it is derived once, at open, from the market's maintenance margin rate ({@link MarketSpec}, Р-69).
 * The factories without a {@code MarketSpec} open a BTC position, as before.
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

    /** Opens a new BTC position, deriving {@code liquidationPrice} from the spec's formula. */
    public static Position open(
            String userId,
            String roundId,
            Direction direction,
            Leverage leverage,
            BigDecimal size,
            BigDecimal entryPrice) {
        return open(userId, roundId, direction, leverage, size, entryPrice, MarketSpec.BTC);
    }

    /**
     * Opens a new position in {@code spec}'s market: the same formula, with that market's
     * maintenance margin rate. Does not check {@code leverage} against {@code spec.maxLeverage()} —
     * the cap is enforced where a bet is accepted ({@code GameEngine#placeBet}), so a position that
     * was accepted once can always be reopened when a round is resolved or replayed.
     */
    public static Position open(
            String userId,
            String roundId,
            Direction direction,
            Leverage leverage,
            BigDecimal size,
            BigDecimal entryPrice,
            MarketSpec spec) {
        BigDecimal liquidationPrice = LiquidationCalculator.liquidationPrice(
                size, entryPrice, leverage, direction.toSide(), spec.maintenanceMarginRate());
        return new Position(userId, roundId, direction, leverage, size, entryPrice, liquidationPrice, null);
    }

    /**
     * Opens a BTC position from a PLAY margin rather than a raw size — {@code size} is derived via
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
        return openFromMargin(userId, roundId, direction, leverage, margin, entryPrice, MarketSpec.BTC);
    }

    /** {@link #openFromMargin(String, String, Direction, Leverage, BigDecimal, BigDecimal)} in {@code spec}'s market (Р-69). */
    public static Position openFromMargin(
            String userId,
            String roundId,
            Direction direction,
            Leverage leverage,
            BigDecimal margin,
            BigDecimal entryPrice,
            MarketSpec spec) {
        BigDecimal size = PositionSizing.sizeForMargin(margin, leverage, entryPrice);
        return open(userId, roundId, direction, leverage, size, entryPrice, spec);
    }

    public boolean isSettled() {
        return pnl != null;
    }

    public Position withPnl(BigDecimal pnl) {
        return new Position(userId, roundId, direction, leverage, size, entryPrice, liquidationPrice, pnl);
    }
}
