package crowdvsmarket.server.prices;

import java.math.BigDecimal;

/**
 * A market's latest price for the lobby.
 *
 * @param change24h relative change vs 24h ago (0.0098 = +0.98%); null when the source gives none
 * @param stale     the last tick is older than the source's staleness limit
 */
public record PriceQuote(BigDecimal raw, long asOfMs, Double change24h, boolean stale) {
}
