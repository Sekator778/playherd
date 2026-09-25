package crowdvsmarket.engine.season;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SeasonTest {

    @Test
    void ofUsesUtcCalendarMonth() {
        // 2026-04-30T23:59:59Z is still April UTC even if a local timezone would already be May 1.
        Instant lastMomentOfApril = Instant.parse("2026-04-30T23:59:59Z");
        assertThat(Season.of(lastMomentOfApril)).isEqualTo(new Season(2026, 4));

        Instant firstMomentOfMay = Instant.parse("2026-05-01T00:00:00Z");
        assertThat(Season.of(firstMomentOfMay)).isEqualTo(new Season(2026, 5));
    }

    @Test
    void ofHandlesYearBoundary() {
        Instant newYearsEve = Instant.parse("2026-12-31T23:59:59Z");
        Instant newYear = Instant.parse("2027-01-01T00:00:00Z");
        assertThat(Season.of(newYearsEve)).isEqualTo(new Season(2026, 12));
        assertThat(Season.of(newYear)).isEqualTo(new Season(2027, 1));
    }

    @Test
    void idFormatsAsYearDashMonth() {
        assertThat(new Season(2026, 4).id()).isEqualTo("2026-04");
        assertThat(new Season(2026, 12).id()).isEqualTo("2026-12");
    }

    @Test
    void compareToOrdersChronologically() {
        Season april = new Season(2026, 4);
        Season may = new Season(2026, 5);
        Season nextJan = new Season(2027, 1);

        assertThat(april.compareTo(may)).isLessThan(0);
        assertThat(may.compareTo(april)).isGreaterThan(0);
        assertThat(may.compareTo(nextJan)).isLessThan(0);
        assertThat(april.compareTo(new Season(2026, 4))).isZero();
    }

    @Test
    void rejectsInvalidMonth() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Season(2026, 0))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Season(2026, 13))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
