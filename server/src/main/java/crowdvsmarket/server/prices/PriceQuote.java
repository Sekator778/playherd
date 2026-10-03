package crowdvsmarket.server.prices;

import java.math.BigDecimal;

/**
 * A market's latest price for the lobby.
 *
 * @param change24h relative change vs 24h ago (0.0098 = +0.98%); null when the source gives none
 * @param stale     the last tick is older than the source's staleness limit
 * @param venue     the exchange the quote is from when it is not the market's own primary one — a market with a backup
 *                  exchange whose primary has no fresh tick shows the backup's ({@code kraken}); {@code null} for the
 *                  primary's quote, and for every market that has no backup
 */
public record PriceQuote(BigDecimal raw, long asOfMs, Double change24h, boolean stale, String venue) {

    /** The quote of a market's primary venue (or of a market that has only one). */
    public PriceQuote(BigDecimal raw, long asOfMs, Double change24h, boolean stale) {
        this(raw, asOfMs, change24h, stale, null);
    }

    public PriceQuote withVenue(String venue) {
        return new PriceQuote(raw, asOfMs, change24h, stale, venue);
    }
}
