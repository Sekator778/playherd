package crowdvsmarket.server.prices;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the Polymarket price source and the Polymarket catalog tell each other, per market id:
 *
 * <ul>
 *   <li>the poll reports a token that was <b>missing</b> from {@code /midpoints} (a closed market's
 *       token silently drops out of the batch answer, polymarket_data_api.md §5) and the time since
 *       when it has been missing — the catalog closes the market when that is 60 s <i>and</i> enough
 *       consecutive successful answers said so ({@link #missingAnswers}); an answer that has the token
 *       ({@link #reportPresent}) or a poll that failed ({@link #reportPollFailed}) starts the count again, so
 *       one partial answer followed by a CLOB that times out never closes a market for good;</li>
 *   <li>the catalog reports the market's {@code oneDayPriceChange} from Gamma, an <i>absolute</i>
 *       change in probability ({@code -0.04} = four points down) — measured 2026-10-02: for three open
 *       markets it equals the CLOB's price now minus the price about 24 hours earlier from the data
 *       API's history (Kane {@code -0.0775} = 0.3455 − 0.423, Lula {@code 0.01} = 0.395 − 0.385,
 *       Yamal {@code 0.1415} = 0.575 − 0.4335), a relative change would have been 0.026 / −0.18 /
 *       0.33 (docs/research/polymarket_data_api.md §13.2) — and the poll turns it into the relative
 *       {@code change24h} of the quote.</li>
 * </ul>
 */
@ApplicationScoped
@Unremovable
public class PolymarketLiveState {

    /** An unbroken run of answers in which the token was missing. */
    private record Run(long sinceMs, long lastMs, int answers) {
    }

    private final ConcurrentHashMap<String, Run> runs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> oneDayChange = new ConcurrentHashMap<>();

    /** The token of this market was in the latest {@code /midpoints} answer. */
    public void reportPresent(String marketId) {
        runs.remove(marketId);
    }

    /** The token was missing from an answer that did contain others: one more answer in the run. */
    public void reportMissing(String marketId, long nowMs) {
        runs.merge(marketId, new Run(nowMs, nowMs, 1), (old, one) -> new Run(old.sinceMs(), nowMs, old.answers() + 1));
    }

    /** The poll that should have answered failed: an answer that never came proves nothing. */
    public void reportPollFailed(String marketId) {
        runs.remove(marketId);
    }

    /** Since when the token has been missing from every answer, if it is missing now. */
    public OptionalLong missingSince(String marketId) {
        Run run = runs.get(marketId);
        return run == null ? OptionalLong.empty() : OptionalLong.of(run.sinceMs());
    }

    /** When the newest answer without the token came, if the token is missing now. */
    public OptionalLong lastMissingAnswerAt(String marketId) {
        Run run = runs.get(marketId);
        return run == null ? OptionalLong.empty() : OptionalLong.of(run.lastMs());
    }

    /** How many consecutive successful answers have lacked the token (empty: none). */
    public OptionalInt missingAnswers(String marketId) {
        Run run = runs.get(marketId);
        return run == null ? OptionalInt.empty() : OptionalInt.of(run.answers());
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
        runs.remove(marketId);
        oneDayChange.remove(marketId);
    }
}
