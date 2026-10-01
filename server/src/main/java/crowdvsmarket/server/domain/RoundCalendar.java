package crowdvsmarket.server.domain;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.persistence.ServerMetaRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * {@link #originMs()} is the instant every round boundary is a whole number of steps away from,
 * persisted once in {@code server_meta} so it survives restarts unchanged — a restart must not
 * shift any round boundary out from under bets already placed on it. Every market runs on the same
 * clock (a window belongs to a market, the boundaries do not).
 *
 * <p><b>Which instant (Т-11, docs/research/markets_architecture.md §2):</b> on a FRESH database in
 * {@code mode=live} the origin is {@code floor(now / dailyStep) * dailyStep}, so boundaries are
 * multiples of the daily series' length from the Unix epoch — hourly rounds on hh:00 and daily on
 * 00:00 UTC with the production durations, :00/:03/… and :00/:15/… on the 180 s / 900 s stand. In
 * {@code mode=replay}, and for any database that already has an origin, nothing changes: the origin
 * is the first-ever-boot instant, which is also what anchors {@code ReplayRoundPrices}' virtual
 * clock. {@link #firstWindow} is the window starting exactly at the origin.
 *
 * <p>Р-19's "always one running + one open [for betting] round per series" is <b>not</b>
 * recomputed here from wall-clock {@code now} on every read (naive
 * {@code RoundClock.windowContaining(interval, now, ...)} would, right at a boundary, race
 * against the scheduler that has not resolved the elapsed round yet) — see {@link RoundProgress},
 * which tracks the actual next-window-to-resolve explicitly instead. {@link #windowContaining} is
 * for the moments that really do mean "the window running now": seeding a series (a restart with
 * no round yet, a newly listed market) and the catch-up jump over empty overdue windows.
 */
@ApplicationScoped
public class RoundCalendar {

    @Inject
    GameConfig config;

    @Inject
    ServerMetaRepository serverMeta;

    private volatile long originMs = -1L;

    /** The origin a database that has none yet starts with: see the class javadoc. */
    static long freshOrigin(GameMode mode, long nowMs, long dailyStepMs) {
        return mode == GameMode.LIVE ? Math.floorDiv(nowMs, dailyStepMs) * dailyStepMs : nowMs;
    }

    /** Call once, early at startup (before the scheduler or any price source needs it) so the
     *  origin is fixed for the rest of the process's life. */
    public synchronized long init(long nowMs) {
        if (originMs < 0) {
            long dailyStepMs = config.roundDurations().forInterval(Interval.DAILY);
            originMs = serverMeta.originMsOrInit(freshOrigin(config.mode(), nowMs, dailyStepMs));
        }
        return originMs;
    }

    public long originMs() {
        if (originMs < 0) {
            throw new IllegalStateException("RoundCalendar.init() was not called yet");
        }
        return originMs;
    }

    /** The window of {@code market}'s series that starts exactly at the origin. */
    public RoundWindow firstWindow(String market, Interval interval) {
        return windowContaining(market, interval, originMs());
    }

    /** The window of {@code market}'s series that is running at {@code nowMs}. */
    public RoundWindow windowContaining(String market, Interval interval, long nowMs) {
        return RoundClock.windowContaining(market, interval, nowMs, config.roundDurations(), originMs());
    }
}
