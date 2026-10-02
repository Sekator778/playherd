package crowdvsmarket.server.prices;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.round.RoundDurations;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.RoundCalendar;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * F5: a server with real price sources refuses to start when a round boundary is not on a whole minute, because
 * the history it settles from has a minute's resolution — the origin of a database made in replay mode is the
 * millisecond of its first start, and a round of 90.5 s would not tile the minutes either. Only where it matters:
 * live mode, real sources, scheduler on.
 */
class RoundAlignmentCheckTest {

    /** 2026-10-01T19:40:00Z: on a minute. */
    private static final long ALIGNED = 1_790_883_600_000L;
    private static final long NOW = ALIGNED + 5_000L;

    private static RoundAlignmentCheck check(PricesConfig prices, long hourlyMs, long dailyMs, long originMs,
                                             boolean schedulerEnabled) {
        RoundAlignmentCheck check = new RoundAlignmentCheck();
        check.prices = prices;
        check.game = new GameConfig() {
            @Override
            public RoundDurations roundDurations() {
                return new RoundDurations(hourlyMs, dailyMs);
            }
        };
        check.calendar = new RoundCalendar() {
            @Override
            public synchronized long init(long nowMs) {
                return originMs;
            }
        };
        check.schedulerEnabled = schedulerEnabled;
        return check;
    }

    @Test
    void aWholeMinuteEverywhereIsFineTheStandsAndProductionsDurations() {
        assertThat(RoundAlignmentCheck.problems(180_000L, 900_000L, ALIGNED)).isEmpty(); // the stand
        assertThat(RoundAlignmentCheck.problems(3_600_000L, 86_400_000L, 0L)).isEmpty(); // production, on the epoch
        assertThat(RoundAlignmentCheck.problems(60_000L, 120_000L, ALIGNED + 7 * 60_000L)).isEmpty();
        assertThat(RoundAlignmentCheck.problems(60_000L, 60_000L, -120_000L)).as("a negative origin").isEmpty();
    }

    @Test
    void everyOffendingValueIsNamedWithItsNumber() {
        List<String> problems = RoundAlignmentCheck.problems(90_500L, 905_000L, ALIGNED + 123L);

        assertThat(problems).hasSize(3);
        assertThat(problems.get(0)).contains("HOURLY").contains("90500").contains("herd.round-durations.hourly-sec");
        assertThat(problems.get(1)).contains("DAILY").contains("905000").contains("herd.round-durations.daily-sec");
        assertThat(problems.get(2)).contains("origin").contains(String.valueOf(ALIGNED + 123L)).contains("123 ms");
        assertThat(RoundAlignmentCheck.problems(180_000L, 900_000L, ALIGNED - 1L).get(0)).contains("59999 ms past");
    }

    @Test
    void anOriginOffAMinuteRefusesToStartInLiveModeWithRealSourcesAndTheSchedulerOn() {
        RoundAlignmentCheck unaligned = check(PricesConfig.defaults(), 180_000L, 900_000L, ALIGNED + 1_234L, true);

        assertThatThrownBy(() -> unaligned.check(NOW)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whole minute")
                .hasMessageContaining(String.valueOf(ALIGNED + 1_234L))
                .hasMessageContaining("1234 ms past a whole minute")
                .hasMessageContaining("herd.prices.require-minute-aligned-rounds=false")
                .hasMessageContaining("fresh database");
    }

    @Test
    void aDurationOffAMinuteRefusesToStartToo() {
        assertThatThrownBy(() -> check(PricesConfig.defaults(), 90_500L, 900_000L, ALIGNED, true).check(NOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HOURLY round is 90500 ms");
        assertThatThrownBy(() -> check(PricesConfig.defaults(), 180_000L, 86_400_500L, ALIGNED, true).check(NOW))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("DAILY round is 86400500 ms");
    }

    @Test
    void aFreshDatabaseOfTheStandOrOfProductionStartsWithoutAComplaint() {
        assertThatCode(() -> check(PricesConfig.defaults(), 180_000L, 900_000L, ALIGNED, true).check(NOW))
                .doesNotThrowAnyException();
        assertThatCode(() -> check(PricesConfig.defaults(), 3_600_000L, 86_400_000L, ALIGNED - ALIGNED % 86_400_000L, true)
                .check(NOW)).doesNotThrowAnyException();
    }

    @Test
    void theCheckOnlyCountsWhereARealSourceSettlesRoundsFromMinuteHistory() {
        long off = ALIGNED + 1_234L;
        // replay mode: no pipeline at all
        assertThatCode(() -> check(PricesConfig.defaults().withMode(false, PricesConfig.SourceKind.LIVE), 180_000L,
                900_000L, off, true).check(NOW)).doesNotThrowAnyException();
        // the synthetic walk has a price at every second: rounds of 6 and 12 s run on it in the tests
        assertThatCode(() -> check(PricesConfig.defaults().withMode(true, PricesConfig.SourceKind.SYNTHETIC), 6_000L,
                12_000L, off, true).check(NOW)).doesNotThrowAnyException();
        // the scheduler off: nothing ever asks a source for anything
        assertThatCode(() -> check(PricesConfig.defaults(), 180_000L, 900_000L, off, false).check(NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void theCheckCanBeTurnedIntoAnErrorLineInTheLog() {
        PricesConfig lenient = new PricesConfig(true, PricesConfig.SourceKind.LIVE, 900_000L,
                PricesConfig.DEFAULT_HYPERLIQUID_URL, PricesConfig.DEFAULT_POLYMARKET_CLOB_URL,
                PricesConfig.DEFAULT_POLYMARKET_GAMMA_URL, PricesConfig.DEFAULT_POLYMARKET_DATA_URL, 15_000, 20_000,
                30_000, 30_000, 90_000, 5_000, 10_000, 60_000, 3_000, 5_000, 600_000L, 260, false);

        assertThatCode(() -> check(lenient, 180_000L, 900_000L, ALIGNED + 1_234L, true).check(NOW))
                .doesNotThrowAnyException();
    }

    @Test
    void theMessageSaysWhatToDo() {
        String message = RoundAlignmentCheck.message(RoundAlignmentCheck.problems(180_000L, 900_000L, ALIGNED + 5L));

        assertThat(message).contains("multiples of 60 s").contains("a database whose origin is a multiple of the DAILY round")
                .contains("replay mode");
    }
}
