package crowdvsmarket.server.domain;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The authoritative "next window to resolve" per series (Р-19) — deliberately not recomputed from
 * wall-clock {@code now} on every read (see {@link RoundCalendar}'s javadoc for the boundary race
 * that would cause). A series is a market plus an interval (docs/research/markets_architecture.md
 * §4 п.1): the key is {@code (marketId, Interval)}. {@code RoundSchedulerService} is the only
 * writer in normal operation ({@link #advance}, {@link #jumpTo}); every reader (the RPC layer,
 * bots) treats {@link #next} as "RUNNING" and {@code RoundClock.next(next(...))} as "BETTING" for
 * that series right now.
 *
 * <p>Seeded at startup ({@code EngineRebuildService}) from the latest resolved round of the
 * series in {@code round_results} (its {@code window_end_ms} becomes the new {@code next.startMs()}),
 * or from the window containing "now" when the market has no round yet (§4 п.2 — never from the
 * calendar origin, which would make the scheduler resolve every window since then); a market
 * listed later is seeded the same way by {@code GameService.onListed}. A closed market's series is
 * {@linkplain #remove removed}.
 */
@ApplicationScoped
public final class RoundProgress {

    /** One round series. */
    public record SeriesKey(String marketId, Interval interval) {
    }

    private final ConcurrentHashMap<SeriesKey, RoundWindow> next = new ConcurrentHashMap<>();

    /** Sets the cursor of {@code (marketId, interval)} to {@code window} — which must be a window of
     *  that very market and interval, or the series would silently drift into another one. */
    public void seed(String marketId, Interval interval, RoundWindow window) {
        Objects.requireNonNull(window, "window");
        if (!window.market().equals(marketId) || window.interval() != interval) {
            throw new IllegalArgumentException("window " + window.roundId() + " does not belong to series "
                    + marketId + "/" + interval);
        }
        next.put(new SeriesKey(marketId, interval), window);
    }

    /** True once {@link #seed} has run for the series — false only in the brief startup window
     *  before {@code EngineRebuildService.rebuild()} (a {@code StartupEvent} observer on the main
     *  thread) has seeded it, and for a market that was just listed / has been closed.
     *  {@code RoundSchedulerService}'s {@code @Scheduled} tick runs on its own executor thread and
     *  can be armed before that observer completes (bug fix: the two used to race — the tick would
     *  call {@link #next}/{@link #bettingWindow}, throw, and log "RoundProgress not seeded for X"
     *  every second until rebuild() won the race); callers reachable from the scheduler thread
     *  check this first instead. */
    public boolean isSeeded(String marketId, Interval interval) {
        return next.containsKey(new SeriesKey(marketId, interval));
    }

    public RoundWindow next(String marketId, Interval interval) {
        RoundWindow w = next.get(new SeriesKey(marketId, interval));
        if (w == null) {
            throw new IllegalStateException("RoundProgress not seeded for " + marketId + "/" + interval);
        }
        return w;
    }

    /** {@link #next} as an {@code Optional}: empty when the series is not seeded (yet, or any more).
     *  The form for a caller that does not hold the engine lock, where {@link #isSeeded} followed by
     *  {@link #next} could be overtaken by a market closing in between. */
    public Optional<RoundWindow> findNext(String marketId, Interval interval) {
        return Optional.ofNullable(next.get(new SeriesKey(marketId, interval)));
    }

    public RoundWindow bettingWindow(String marketId, Interval interval) {
        return RoundClock.next(next(marketId, interval));
    }

    /** Moves the cursor of the series one step forward, whatever it currently is. */
    public void advance(String marketId, Interval interval) {
        next.computeIfPresent(new SeriesKey(marketId, interval), (k, w) -> RoundClock.next(w));
    }

    /** Moves the cursor one step forward only if it still is {@code expected}: the scheduler
     *  resolved {@code expected} while a rebuild may have re-seeded the series meanwhile, and
     *  advancing a freshly re-seeded cursor would skip a window.
     *
     *  @return whether the cursor moved */
    public boolean advance(String marketId, Interval interval, RoundWindow expected) {
        return next.replace(new SeriesKey(marketId, interval), expected, RoundClock.next(expected));
    }

    /** Moves the cursor from {@code expected} to {@code target} — a catch-up jump over empty
     *  overdue windows (§4 п.5, no event, no result) — only if it still is {@code expected}.
     *
     *  @return whether the cursor moved */
    public boolean jumpTo(String marketId, Interval interval, RoundWindow expected, RoundWindow target) {
        if (!target.market().equals(marketId) || target.interval() != interval) {
            throw new IllegalArgumentException("window " + target.roundId() + " does not belong to series "
                    + marketId + "/" + interval);
        }
        return next.replace(new SeriesKey(marketId, interval), expected, target);
    }

    /** Stops the series of a closed market. */
    public void remove(String marketId, Interval interval) {
        next.remove(new SeriesKey(marketId, interval));
    }

    /** Forgets every series — the first step of a rebuild. */
    public void clear() {
        next.clear();
    }

    /** The series that currently run, in no particular order. */
    public List<SeriesKey> seededSeries() {
        return List.copyOf(next.keySet());
    }
}
