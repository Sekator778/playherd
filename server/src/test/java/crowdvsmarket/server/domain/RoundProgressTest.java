package crowdvsmarket.server.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import org.junit.jupiter.api.Test;

/** {@link RoundProgress} is keyed by (market, interval) — docs/research/markets_architecture.md §4
 *  п.1: one cursor per series, no series leaks into another. */
class RoundProgressTest {

    private static final long STEP = 60_000L;

    private static RoundWindow window(String market, Interval interval, long start) {
        return new RoundWindow(market, interval, start, start + STEP);
    }

    @Test
    void seriesAreIndependentPerMarketAndPerInterval() {
        RoundProgress progress = new RoundProgress();
        progress.seed("BTC", Interval.HOURLY, window("BTC", Interval.HOURLY, 600_000L));
        progress.seed("ETH", Interval.HOURLY, window("ETH", Interval.HOURLY, 1_200_000L));

        assertThat(progress.isSeeded("BTC", Interval.HOURLY)).isTrue();
        assertThat(progress.isSeeded("ETH", Interval.HOURLY)).isTrue();
        assertThat(progress.isSeeded("BTC", Interval.DAILY)).isFalse();
        assertThat(progress.isSeeded("SOL", Interval.HOURLY)).isFalse();
        assertThat(progress.next("BTC", Interval.HOURLY).startMs()).isEqualTo(600_000L);
        assertThat(progress.next("ETH", Interval.HOURLY).startMs()).isEqualTo(1_200_000L);
        assertThat(progress.next("ETH", Interval.HOURLY).roundId()).isEqualTo("ETH-1h-1200000");
        // betting = the window right after the running one, of the same market
        assertThat(progress.bettingWindow("ETH", Interval.HOURLY))
                .isEqualTo(window("ETH", Interval.HOURLY, 1_260_000L));
    }

    @Test
    void replaceAllSwapsEveryCursorAtOnceAndKeepsNoneOfTheOldOnes() {
        RoundProgress progress = new RoundProgress();
        progress.seed("BTC", Interval.HOURLY, window("BTC", Interval.HOURLY, 600_000L));
        progress.seed("OLD", Interval.DAILY, window("OLD", Interval.DAILY, 0L));

        progress.replaceAll(java.util.Map.of(
                new RoundProgress.SeriesKey("BTC", Interval.HOURLY), window("BTC", Interval.HOURLY, 1_200_000L),
                new RoundProgress.SeriesKey("ETH", Interval.DAILY), window("ETH", Interval.DAILY, 60_000L)));

        assertThat(progress.next("BTC", Interval.HOURLY).startMs()).isEqualTo(1_200_000L);
        assertThat(progress.next("ETH", Interval.DAILY).startMs()).isEqualTo(60_000L);
        assertThat(progress.isSeeded("OLD", Interval.DAILY)).isFalse();
        assertThat(progress.seededSeries()).containsExactlyInAnyOrder(new RoundProgress.SeriesKey("BTC", Interval.HOURLY),
                new RoundProgress.SeriesKey("ETH", Interval.DAILY));
        // the cursors move as before afterwards
        assertThat(progress.advance("BTC", Interval.HOURLY, window("BTC", Interval.HOURLY, 1_200_000L))).isTrue();
        assertThat(progress.next("BTC", Interval.HOURLY).startMs()).isEqualTo(1_260_000L);
    }

    @Test
    void replaceAllRejectsAWindowThatDoesNotBelongToItsSeriesAndChangesNothing() {
        RoundProgress progress = new RoundProgress();
        progress.seed("BTC", Interval.HOURLY, window("BTC", Interval.HOURLY, 600_000L));

        assertThatThrownBy(() -> progress.replaceAll(java.util.Map.of(
                new RoundProgress.SeriesKey("ETH", Interval.HOURLY), window("BTC", Interval.HOURLY, 0L))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(progress.next("BTC", Interval.HOURLY).startMs()).isEqualTo(600_000L);
        assertThat(progress.isSeeded("ETH", Interval.HOURLY)).isFalse();
    }

    @Test
    void unseededSeriesIsAnErrorForNextAndEmptyForFindNext() {
        RoundProgress progress = new RoundProgress();

        assertThatThrownBy(() -> progress.next("BTC", Interval.HOURLY))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BTC/HOURLY");
        assertThatThrownBy(() -> progress.bettingWindow("BTC", Interval.HOURLY))
                .isInstanceOf(IllegalStateException.class);
        assertThat(progress.findNext("BTC", Interval.HOURLY)).isEmpty();
    }

    @Test
    void seedRejectsAWindowOfAnotherMarketOrInterval() {
        RoundProgress progress = new RoundProgress();

        assertThatThrownBy(() -> progress.seed("BTC", Interval.HOURLY, window("ETH", Interval.HOURLY, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> progress.seed("BTC", Interval.HOURLY, window("BTC", Interval.DAILY, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(progress.isSeeded("BTC", Interval.HOURLY)).isFalse();
    }

    @Test
    void advanceMovesOneStepAndTheGuardedFormOnlyWhenTheCursorIsStillWhereTheCallerSawIt() {
        RoundProgress progress = new RoundProgress();
        RoundWindow first = window("ETH", Interval.DAILY, 0L);
        progress.seed("ETH", Interval.DAILY, first);

        assertThat(progress.advance("ETH", Interval.DAILY, first)).isTrue();
        assertThat(progress.next("ETH", Interval.DAILY).startMs()).isEqualTo(STEP);

        // a stale caller (a rebuild re-seeded the series meanwhile) must not skip a window
        assertThat(progress.advance("ETH", Interval.DAILY, first)).isFalse();
        assertThat(progress.next("ETH", Interval.DAILY).startMs()).isEqualTo(STEP);

        progress.advance("ETH", Interval.DAILY);
        assertThat(progress.next("ETH", Interval.DAILY).startMs()).isEqualTo(2 * STEP);
    }

    @Test
    void jumpToMovesTheCursorOnlyFromTheExpectedWindowAndOnlyWithinTheSeries() {
        RoundProgress progress = new RoundProgress();
        RoundWindow first = window("ETH", Interval.HOURLY, 0L);
        RoundWindow target = window("ETH", Interval.HOURLY, 50 * STEP);
        progress.seed("ETH", Interval.HOURLY, first);

        assertThat(progress.jumpTo("ETH", Interval.HOURLY, target, target)).as("cursor is not at 'target'").isFalse();
        assertThat(progress.jumpTo("ETH", Interval.HOURLY, first, target)).isTrue();
        assertThat(progress.next("ETH", Interval.HOURLY)).isEqualTo(target);
        assertThatThrownBy(() -> progress.jumpTo("ETH", Interval.HOURLY, target, window("BTC", Interval.HOURLY, 0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removeStopsOneSeriesAndClearForgetsAll() {
        RoundProgress progress = new RoundProgress();
        progress.seed("ETH", Interval.HOURLY, window("ETH", Interval.HOURLY, 0L));
        progress.seed("ETH", Interval.DAILY, window("ETH", Interval.DAILY, 0L));
        progress.seed("BTC", Interval.HOURLY, window("BTC", Interval.HOURLY, 0L));

        progress.remove("ETH", Interval.HOURLY);
        assertThat(progress.isSeeded("ETH", Interval.HOURLY)).isFalse();
        assertThat(progress.isSeeded("ETH", Interval.DAILY)).isTrue();
        assertThat(progress.seededSeries()).containsExactlyInAnyOrder(
                new RoundProgress.SeriesKey("ETH", Interval.DAILY), new RoundProgress.SeriesKey("BTC", Interval.HOURLY));

        progress.clear();
        assertThat(progress.seededSeries()).isEmpty();
    }

    @Test
    void bettingWindowCarriesTheMarketOfTheCursor() {
        RoundProgress progress = new RoundProgress();
        RoundWindow running = window("xyz_NVDA", Interval.HOURLY, 7 * STEP);
        progress.seed("xyz_NVDA", Interval.HOURLY, running);

        assertThat(progress.bettingWindow("xyz_NVDA", Interval.HOURLY)).isEqualTo(RoundClock.next(running));
        assertThat(progress.bettingWindow("xyz_NVDA", Interval.HOURLY).market()).isEqualTo("xyz_NVDA");
    }
}
