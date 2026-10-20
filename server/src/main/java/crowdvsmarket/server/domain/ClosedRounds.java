package crowdvsmarket.server.domain;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Which rounds are resolved or cancelled, as the engine knows it: set by {@code EventApplier} when it applies a
 * {@code RoundResolved} or a {@code RoundCancelled} (live, and in a replay of the journal, so a rebuild restores it).
 * This — not the {@code round_results} table, which is a projection of the same events and may be missing or late —
 * is what {@code GameService.resolveRound}, {@code cancelRound} and {@code placeBet} ask, what seeds a series' cursor
 * after a rebuild, and what the scheduler asks before it resolves a round
 * (docs/research/settle_outside_lock_2026-10-18.md §11).
 *
 * <p>Per series the most recent {@value #KEEP} windows are kept (by start): every question is about the cursor's
 * window or the one after it, and the cursor is never seeded further back than the latest closed window. The newest
 * end of a series is kept apart and never forgotten. 256 is about 13 hours of 3-minute rounds and 2.7 days of
 * 15-minute ones; with ~480 series (odds markets included) that is ~10 MB of heap, where 4096 would be ~150 MB.
 */
public final class ClosedRounds {

    static final int KEEP = 256;

    private final ConcurrentHashMap<RoundProgress.SeriesKey, ConcurrentSkipListMap<Long, Long>> byStart =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RoundProgress.SeriesKey, Long> latestEnd = new ConcurrentHashMap<>();

    /** Marks {@code window} resolved or cancelled. */
    public void closed(RoundWindow window) {
        RoundProgress.SeriesKey key = new RoundProgress.SeriesKey(window.market(), window.interval());
        ConcurrentSkipListMap<Long, Long> windows = byStart.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>());
        windows.put(window.startMs(), window.endMs());
        while (windows.size() > KEEP) {
            windows.pollFirstEntry();
        }
        latestEnd.merge(key, window.endMs(), Math::max);
    }

    /** Whether {@code window} was resolved or cancelled (and is still among the newest {@value #KEEP} of its series). */
    /** The closed windows still remembered of a series as {@code {startMs, endMs}}, oldest first (a snapshot walks them). */
    public java.util.List<long[]> remembered(String marketId, Interval interval) {
        ConcurrentSkipListMap<Long, Long> windows = byStart.get(new RoundProgress.SeriesKey(marketId, interval));
        java.util.List<long[]> out = new java.util.ArrayList<>();
        if (windows != null) {
            windows.forEach((start, end) -> out.add(new long[] {start, end}));
        }
        return out;
    }

    /** Puts the saved memory of a series back: the windows {@link #remembered} gave and the newest end ({@code null}: none). */
    public void restore(String marketId, Interval interval, java.util.List<long[]> windows, Long newestEnd) {
        RoundProgress.SeriesKey key = new RoundProgress.SeriesKey(marketId, interval);
        if (!windows.isEmpty()) {
            ConcurrentSkipListMap<Long, Long> map = byStart.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>());
            for (long[] window : windows) {
                map.put(window[0], window[1]);
            }
        }
        if (newestEnd != null) {
            latestEnd.put(key, newestEnd);
        }
    }

    public boolean isClosed(RoundWindow window) {
        ConcurrentSkipListMap<Long, Long> windows =
                byStart.get(new RoundProgress.SeriesKey(window.market(), window.interval()));
        return windows != null && windows.containsKey(window.startMs());
    }

    /** The end of the newest resolved or cancelled window of the series: where its cursor starts at the earliest. */
    public Optional<Long> latestEnd(String marketId, Interval interval) {
        return Optional.ofNullable(latestEnd.get(new RoundProgress.SeriesKey(marketId, interval)));
    }

    /** An independent copy (the read views keep their own, updated after each commit - architecture plan F8). */
    public ClosedRounds copy() {
        ClosedRounds copy = new ClosedRounds();
        byStart.forEach((key, windows) -> copy.byStart.put(key, new ConcurrentSkipListMap<>(windows)));
        copy.latestEnd.putAll(latestEnd);
        return copy;
    }
}
