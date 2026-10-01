package crowdvsmarket.server.prices;

import crowdvsmarket.engine.market.Candle;
import java.math.BigDecimal;
import java.util.List;

/**
 * One round's prices in engine (lot) units, ready for {@code GameEngine.runRound}:
 * {@code open = P(start)}, {@code close = P(end)}, {@code path} — at most 30 OHLC candles over the
 * window whose first candle opens at {@code open} and whose last closes at {@code close}
 * (docs/research/markets_architecture.md §3.2).
 *
 * @param sourceTsMs time of the tick the close price was read from
 */
public record RoundPriceData(BigDecimal open, BigDecimal close, List<Candle> path, long sourceTsMs) {
}
