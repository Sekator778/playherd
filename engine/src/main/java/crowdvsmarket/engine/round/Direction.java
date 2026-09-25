package crowdvsmarket.engine.round;

import crowdvsmarket.engine.margin.Side;

/**
 * A crowd position's direction, matching {@code direction: up|down} in the {@code Position} data
 * format (PLAN.md section 3). Maps 1:1 onto {@link Side} for the liquidation math: betting the
 * round closes {@code UP} is a long, {@code DOWN} is a short.
 */
public enum Direction {
    UP,
    DOWN;

    public Side toSide() {
        return this == UP ? Side.LONG : Side.SHORT;
    }
}
