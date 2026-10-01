package crowdvsmarket.server.prices;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the Polymarket price source and the Polymarket catalog tell each other, per market id:
 *
 * <ul>
 *   <li>the poll reports a token that was <b>missing</b> from {@code /midpoints} (a closed market's
 *       token silently drops out of the batch answer, polymarket_data_api.md §5) and the time since
 *       when it has been missing — the catalog closes the market when that is 60 s;</li>
 *   <li>the catalog reports the market's {@code oneDayPriceChange} from Gamma, read as an
 *       <i>absolute</i> change in probability ({@code -0.04} = four points down) — UNVERIFIED as a
 *       definition: Gamma's docs were not read for it, the samples of 2026-10-01 read most naturally
 *       that way (Liechtenstein at 0.0005 with {@code -0.0375}, Netherlands at 0.065 with
 *       {@code -0.4} after going 2-0 down) — and the poll turns it into the relative
 *       {@code change24h} of the quote.</li>
 * </ul>
 */
@ApplicationScoped
@Unremovable
public class PolymarketLiveState {

    private final ConcurrentHashMap<String, Long> missingSince = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> oneDayChange = new ConcurrentHashMap<>();

    /** The token of this market was in the latest {@code /midpoints} answer. */
    public void reportPresent(String marketId) {
        missingSince.remove(marketId);
    }

    /** The token was missing from an answer that did contain others; remembers the first such time. */
    public void reportMissing(String marketId, long nowMs) {
        missingSince.putIfAbsent(marketId, nowMs);
    }

    /** Since when the token has been missing from every answer, if it is missing now. */
    public OptionalLong missingSince(String marketId) {
        Long since = missingSince.get(marketId);
        return since == null ? OptionalLong.empty() : OptionalLong.of(since);
    }

    public void setOneDayChange(String marketId, Double absoluteChange) {
        if (absoluteChange == null || absoluteChange.isNaN() || absoluteChange.isInfinite()) {
            oneDayChange.remove(marketId);
        } else {
            oneDayChange.put(marketId, absoluteChange);
        }
    }

    /** Gamma's {@code oneDayPriceChange}: probability now minus probability 24 hours ago. */
    public Double oneDayChange(String marketId) {
        return oneDayChange.get(marketId);
    }

    /** Forgets a market (it closed). */
    public void forget(String marketId) {
        missingSince.remove(marketId);
        oneDayChange.remove(marketId);
    }
}
