package crowdvsmarket.engine.ledger;

import java.time.Instant;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The house figures of a {@link LedgerBook} at one moment, as an immutable value: what the economy status, the reconciliation and the
 * circuit breaker read. The breaker depends on the time it is asked at, so the value keeps the daily maxima of TREASURY it is
 * computed from; {@link LedgerBook} answers its own breaker questions with the same functions.
 */
public record EconomyFigures(boolean economyActive, PlayAmount treasury, PlayAmount herd, PlayAmount openStakes,
                             PlayAmount playersTotal, PlayAmount openingTotal, PlayAmount externalFlowSinceCutover,
                             PlayAmount depositedTotal, PlayAmount depositedSinceCutover, PlayAmount withdrawnConfirmed,
                             PlayAmount withdrawalsPending, TreeMap<Long, PlayAmount> treasuryDayMax) {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    /** The highest TREASURY balance of the last 30 days up to {@code now}, the current one included (plan B6). */
    public PlayAmount treasuryMax30d(Instant now) {
        return treasuryMax30d(treasury, treasuryDayMax, now);
    }

    /** Whether TREASURY is below 90% of its maximum of the last 30 days (plan B6). Always {@code false} before the cutover. */
    public boolean circuitBreakerOpen(Instant now) {
        return circuitBreakerOpen(economyActive, treasury, treasuryDayMax, now);
    }

    static PlayAmount treasuryMax30d(PlayAmount treasury, NavigableMap<Long, PlayAmount> dayMax, Instant now) {
        long day = Math.floorDiv(now.toEpochMilli(), MILLIS_PER_DAY);
        PlayAmount max = treasury;
        for (PlayAmount value : dayMax.tailMap(day - (LedgerBook.BREAKER_WINDOW_DAYS - 1), true).values()) {
            if (value.compareTo(max) > 0) {
                max = value;
            }
        }
        return max;
    }

    static boolean circuitBreakerOpen(boolean economyActive, PlayAmount treasury, NavigableMap<Long, PlayAmount> dayMax, Instant now) {
        if (!economyActive) {
            return false;
        }
        long lhs = Math.multiplyExact(treasury.mojos(), 10L);
        long rhs = Math.multiplyExact(treasuryMax30d(treasury, dayMax, now).mojos(), 9L);
        return lhs < rhs;
    }
}
