package crowdvsmarket.server.prices;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the Hyperliquid price source tells the Hyperliquid catalog, per market id — the mirror of
 * {@link PolymarketLiveState}. Every successful poll of a dex answers, for free, whether each of its
 * polled assets {@code isDelisted}; the poll reports it here, and the catalog closes the market
 * (a delisted asset's {@code markPx} is frozen, so its market would only go stale, and its rounds
 * would wait for an hour to be refunded).
 *
 * <p><b>Closing needs fresh evidence.</b> A market is closed for good, so one answer is not enough, and
 * neither is an answer followed by silence: the state counts the <i>consecutive successful answers</i> that
 * report the asset delisted ({@link #delistedAnswers}) and the time of the first and of the latest of them,
 * an answer that lists the asset as live ({@link #reportListed}) or a poll that failed
 * ({@link #reportPollFailed}) starts the count again, and the catalog closes only on enough answers, long
 * enough, the last of them recent. The state is plain memory, read and written without locks.
 *
 * <p>The poll runs on the price feed's thread and the catalog on its own, so the reporting side never
 * calls the market registry — closing a market ends in the game engine's lock.
 */
@ApplicationScoped
@Unremovable
public class HyperliquidLiveState {

    /** An unbroken run of answers that say "delisted": when it began, when the newest one came, how many. */
    private record Run(long sinceMs, long lastMs, int answers) {
    }

    private final ConcurrentHashMap<String, Run> runs = new ConcurrentHashMap<>();

    /** A successful answer lists the asset of this market with {@code isDelisted: true}: one more in the run. */
    public void reportDelisted(String marketId, long nowMs) {
        runs.merge(marketId, new Run(nowMs, nowMs, 1), (old, one) -> new Run(old.sinceMs(), nowMs, old.answers() + 1));
    }

    /** A successful answer lists the asset as live: whatever run was going on stops. */
    public void reportListed(String marketId) {
        runs.remove(marketId);
    }

    /** The poll that should have answered for this market failed: an answer that never came proves nothing. */
    public void reportPollFailed(String marketId) {
        runs.remove(marketId);
    }

    /** Since when every answer has said the asset is delisted, if the latest one does. */
    public OptionalLong delistedSince(String marketId) {
        Run run = runs.get(marketId);
        return run == null ? OptionalLong.empty() : OptionalLong.of(run.sinceMs());
    }

    /** When the newest answer of the run came, if there is a run. */
    public OptionalLong lastDelistedAnswerAt(String marketId) {
        Run run = runs.get(marketId);
        return run == null ? OptionalLong.empty() : OptionalLong.of(run.lastMs());
    }

    /** How many consecutive successful answers have said the asset is delisted (empty: none). */
    public OptionalInt delistedAnswers(String marketId) {
        Run run = runs.get(marketId);
        return run == null ? OptionalInt.empty() : OptionalInt.of(run.answers());
    }

    /** Forgets a market (it closed). */
    public void forget(String marketId) {
        runs.remove(marketId);
    }
}
