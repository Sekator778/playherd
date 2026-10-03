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
 * @param venue      the exchange the round runs on and these prices are from ({@code coinbase}, {@code kraken},
 *                   {@code hyperliquid}) — only for a market that has a backup exchange, {@code null} for any other (the
 *                   market's own source is then the only one there is, and the round's record has no venue)
 */
public record RoundPriceData(BigDecimal open, BigDecimal close, List<Candle> path, long sourceTsMs, String venue) {

    /** Prices of a market that has no backup exchange: no venue. */
    public RoundPriceData(BigDecimal open, BigDecimal close, List<Candle> path, long sourceTsMs) {
        this(open, close, path, sourceTsMs, null);
    }

    public RoundPriceData withVenue(String venue) {
        return new RoundPriceData(open, close, path, sourceTsMs, venue);
    }
}
