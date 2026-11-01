package crowdvsmarket.engine.round;

import crowdvsmarket.engine.market.Candle;
import java.math.BigDecimal;
import java.util.List;

/**
 * Resolves a {@link RoundWindow}'s open/close prices and the candle path used to monitor
 * liquidation during the round (D-8, D-19). Kept as an interface — called {@code PriceFeed} in
 * the task, named {@code RoundPrices} here to read naturally next to {@link RoundWindow} — so
 * tests can supply fixture candles ({@link CandleSeriesRoundPrices}) while a later live
 * implementation reads the Hyperliquid oracle instead; no other class in this engine talks to a
 * price source directly.
 */
public interface RoundPrices {

    BigDecimal openPrice(RoundWindow window);

    BigDecimal closePrice(RoundWindow window);

    /** Chronological candle path spanning the window, at least one candle, for {@code PositionSimulator}. */
    List<Candle> path(RoundWindow window);
}
