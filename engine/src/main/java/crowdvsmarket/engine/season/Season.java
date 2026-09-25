package crowdvsmarket.engine.season;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * A calendar-month season, UTC (Р-10, Р-16: "сезоны по месяцу").
 *
 * <p><b>Предложение</b> (see docs/research/round_engine_design.md "Sezons"): the sources fix the
 * season length at one calendar month but not which calendar/timezone; UTC is used everywhere
 * else in this engine (Р-19's round boundaries), so a season boundary always lands exactly on an
 * hourly-round boundary too, and picking anything else would need its own justification.
 *
 * <p>Resets ratings only, never balances (Р-16) — see {@code LedgerBook}, which adds a fresh
 * starter pack on top of the carried-over balance at the start of each season.
 */
public record Season(int year, int month) implements Comparable<Season> {

    public Season {
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month must be 1..12, got " + month);
        }
    }

    public static Season of(Instant instant) {
        ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        return new Season(utc.getYear(), utc.getMonthValue());
    }

    /** {@code "YYYY-MM"}, stable and sortable as a string too. */
    public String id() {
        return "%04d-%02d".formatted(year, month);
    }

    @Override
    public int compareTo(Season other) {
        int byYear = Integer.compare(year, other.year);
        return byYear != 0 ? byYear : Integer.compare(month, other.month);
    }

    @Override
    public String toString() {
        return id();
    }
}
