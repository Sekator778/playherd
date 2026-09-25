package crowdvsmarket.server.domain;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The authoritative "next window to resolve" per series (Р-19) — deliberately not recomputed from
 * wall-clock {@code now} on every read (see {@link RoundCalendar}'s javadoc for the boundary race
 * that would cause). {@code RoundSchedulerService} is the only writer ({@link #advance}); every
 * reader (the RPC layer, bots) treats {@link #next} as "RUNNING" and
 * {@code RoundClock.next(next(interval))} as "BETTING" for that series right now.
 *
 * <p>Seeded at startup ({@code EngineRebuildService}) from the latest resolved round in
 * {@code round_results} (its {@code window_end_ms} becomes the new {@code next.startMs()}), or
 * from {@link RoundCalendar#firstWindow} if no round has ever been resolved.
 */
@ApplicationScoped
public final class RoundProgress {

    private volatile RoundWindow nextHourly;
    private volatile RoundWindow nextDaily;

    public void seed(Interval interval, RoundWindow window) {
        if (interval == Interval.HOURLY) {
            nextHourly = window;
        } else {
            nextDaily = window;
        }
    }

    public RoundWindow next(Interval interval) {
        RoundWindow w = interval == Interval.HOURLY ? nextHourly : nextDaily;
        if (w == null) {
            throw new IllegalStateException("RoundProgress not seeded for " + interval);
        }
        return w;
    }

    public RoundWindow bettingWindow(Interval interval) {
        return RoundClock.next(next(interval));
    }

    /** Called after {@code next(interval)} has been resolved — moves the cursor one step forward. */
    public void advance(Interval interval) {
        seed(interval, RoundClock.next(next(interval)));
    }
}
