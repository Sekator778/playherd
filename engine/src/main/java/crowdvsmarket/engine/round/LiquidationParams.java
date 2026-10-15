package crowdvsmarket.engine.round;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The liquidation parameters a bet was placed with (economy stage B, fix of stage A): the leverage cap of its market and round length
 * and the maintenance constant {@code kappa}, so that its maintenance rate is {@code l = kappa / cap}. They are stored with the bet
 * (in the {@code BetPlaced} event) and used to settle it, so a later change of a market's cap or kappa (the weekly recalculation,
 * stage D, lowers caps) cannot change how a bet that is replayed from the log was settled.
 */
public record LiquidationParams(int cap, BigDecimal kappa) {

    public LiquidationParams {
        Objects.requireNonNull(kappa, "kappa");
        kappa = kappa.stripTrailingZeros();
    }

    /** The parameters a bet placed now on {@code market} in {@code interval}'s round gets. */
    public static LiquidationParams of(MarketSpec market, Interval interval) {
        return new LiquidationParams(market.capFor(interval), market.kappa());
    }
}
