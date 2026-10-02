package crowdvsmarket.server.domain;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import java.util.List;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Every window of every series that has had a bet placed on it and has not been resolved or cancelled
 * since: the windows to look at when the question is "where does a stake still sit" — the engine's
 * {@code BetBook} can only be asked about a round id it is given, and has no list of the rounds it holds.
 * Two callers need that list: the seeding of a series, which must not put its cursor behind a pending bet
 * ({@code SeriesSeeder}), and the closing of a market, which refunds every window that holds a bet, not only
 * the two the cursor points at ({@code RoundCancellations#closeSeries}).
 *
 * <p>It is kept by the one code path that changes the engine, {@code EventApplier}: a {@code BetPlaced}
 * adds its window, a {@code RoundResolved} or {@code RoundCancelled} removes it — live and in every replay
 * alike, so a state built aside by a rebuild comes with its index, and {@link EngineHub#adopt} swaps both
 * together. A bet that its player cancels leaves the window in: the index is a list of candidates, "holds a
 * bet" is what the bet book says when it is asked about them.
 */
public final class BetWindows {

    private final ConcurrentHashMap<RoundProgress.SeriesKey, NavigableMap<Long, RoundWindow>> byStart =
            new ConcurrentHashMap<>();

    /** A bet was placed on {@code window}. */
    public void placed(RoundWindow window) {
        byStart.computeIfAbsent(key(window), k -> new ConcurrentSkipListMap<>()).put(window.startMs(), window);
    }

    /** {@code window} was resolved or cancelled: it holds no bet any more. */
    public void closed(RoundWindow window) {
        NavigableMap<Long, RoundWindow> windows = byStart.get(key(window));
        if (windows != null) {
            windows.remove(window.startMs());
        }
    }

    /** The windows of the series that may still hold a bet, earliest first. */
    public List<RoundWindow> of(String marketId, Interval interval) {
        NavigableMap<Long, RoundWindow> windows = byStart.get(new RoundProgress.SeriesKey(marketId, interval));
        return windows == null ? List.of() : List.copyOf(windows.values());
    }

    private static RoundProgress.SeriesKey key(RoundWindow window) {
        return new RoundProgress.SeriesKey(window.market(), window.interval());
    }
}
