package crowdvsmarket.server.prices;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.server.domain.RoundCalendar;
import crowdvsmarket.server.markets.Market;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Where the round windows that are running <i>now</i> started: the DAILY series' window first, then the
 * HOURLY one — from the round calendar the scheduler uses ({@link RoundCalendar#windowContaining}), through
 * its public accessors only. {@link HistorySeeder} looks for a price at those starts: a window whose start has
 * no fresh tick has no opening price. The interface is a function of time so that a test can hand the
 * seeder starts of its own.
 */
@ApplicationScoped
@Unremovable
public class RunningWindows {

    @Inject
    RoundCalendar calendar;

    /**
     * The start of the DAILY and of the HOURLY window containing {@code nowMs} (one number when they coincide);
     * empty while the calendar's origin is not known yet ({@code RoundCalendar.init} has not run).
     */
    public long[] startsAt(long nowMs) {
        try {
            // the boundaries do not depend on the market: any valid id names a window of the one clock — also while the
            // registry's BTC row is closed (its price moved to another source): this is arithmetic on the calendar's
            // origin, nothing here reads the registry or needs an active market
            long daily = calendar.windowContaining(Market.BTC_ID, Interval.DAILY, nowMs).startMs();
            long hourly = calendar.windowContaining(Market.BTC_ID, Interval.HOURLY, nowMs).startMs();
            return daily == hourly ? new long[] {daily} : new long[] {daily, hourly};
        } catch (IllegalStateException e) {
            return new long[0];
        }
    }
}
