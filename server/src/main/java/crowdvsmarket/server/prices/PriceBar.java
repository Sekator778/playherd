package crowdvsmarket.server.prices;

import java.math.BigDecimal;

/**
 * One finished 1-minute candle of a market's live ticks, as raw (display) prices: {@code openTimeMs} is the start of the
 * minute; {@code low <= open, close <= high}.
 */
public record PriceBar(long openTimeMs, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
}
