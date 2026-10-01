package crowdvsmarket.server.prices;

import java.math.BigDecimal;

/** A raw (display) price observed at {@code atMs} — the moment the source's answer arrived. */
public record PricePoint(long atMs, BigDecimal raw) {
}
