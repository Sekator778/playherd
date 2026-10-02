package crowdvsmarket.server.service;

import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.prices.PriceUnavailableException;
import crowdvsmarket.server.prices.RoundPriceData;
import java.math.RoundingMode;

/**
 * What a settlement needs of the prices it is handed, checked BEFORE the engine lock is taken and
 * anything is written (docs/research/markets_architecture.md §3.2 п.4, §4 п.7; review of the
 * multi-market core, finding 3): data that cannot be settled on used to reach the settlement
 * transaction, fail there and get the whole engine rebuilt from the log, once a second, until the round
 * was cancelled an hour later (or, for an exception that was not a {@code PriceUnavailableException},
 * never). A violation is "no usable price" — {@link PriceUnavailableException}, which the scheduler
 * retries and, an hour after the window's end, cancels with a refund (Р-23).
 */
final class RoundPriceChecks {

    /** The most candles one round's path may have in {@code mode=live} (§4 п.7). */
    static final int MAX_PATH_CANDLES = 30;

    private RoundPriceChecks() {
    }

    /**
     * @param capPath whether the path is limited to {@link #MAX_PATH_CANDLES}: the live pipeline folds
     *                every round into at most that many; the replay series is whatever the history gives
     * @return {@code data}, when it has an open and a close that are positive at the engine's own scale
     *         (a price that rounds to zero is not one) and a path to simulate
     */
    static RoundPriceData requireUsable(RoundWindow window, RoundPriceData data, boolean capPath) {
        if (data == null || data.open() == null || data.close() == null || data.path() == null) {
            throw new PriceUnavailableException("the price source gave no data for " + window.roundId());
        }
        if (data.open().setScale(2, RoundingMode.HALF_UP).signum() <= 0
                || data.close().setScale(2, RoundingMode.HALF_UP).signum() <= 0) {
            throw new PriceUnavailableException("the price source gave a non-positive open or close for "
                    + window.roundId() + ": " + data.open() + " -> " + data.close());
        }
        if (capPath && data.path().size() > MAX_PATH_CANDLES) {
            throw new PriceUnavailableException("the price source gave " + data.path().size() + " candles for "
                    + window.roundId() + ", at most " + MAX_PATH_CANDLES + " are accepted");
        }
        return data;
    }
}
