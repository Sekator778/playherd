package crowdvsmarket.server.domain;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import org.junit.jupiter.api.Test;

/** {@link BetWindows}: the windows of a series that may still hold a bet — added by a placement, dropped by a
 *  resolution or cancellation, earliest first, one list per series. */
class BetWindowsTest {

    private static RoundWindow window(String market, Interval interval, long start) {
        return new RoundWindow(market, interval, start, start + 60_000L);
    }

    @Test
    void aSeriesWithoutBetsHasNoWindows() {
        assertThat(new BetWindows().of("BTC", Interval.HOURLY)).isEmpty();
    }

    @Test
    void aPlacedWindowIsListedOnceAndEarliestFirst() {
        BetWindows windows = new BetWindows();

        windows.placed(window("BTC", Interval.HOURLY, 300_000L));
        windows.placed(window("BTC", Interval.HOURLY, 60_000L));
        windows.placed(window("BTC", Interval.HOURLY, 300_000L)); // a second bet on the same window

        assertThat(windows.of("BTC", Interval.HOURLY)).extracting(RoundWindow::startMs)
                .containsExactly(60_000L, 300_000L);
    }

    @Test
    void aClosedWindowIsGoneAndClosingAnUnknownOneIsHarmless() {
        BetWindows windows = new BetWindows();
        windows.placed(window("BTC", Interval.HOURLY, 60_000L));
        windows.placed(window("BTC", Interval.HOURLY, 120_000L));

        windows.closed(window("BTC", Interval.HOURLY, 60_000L));
        windows.closed(window("BTC", Interval.HOURLY, 600_000L));
        windows.closed(window("ETH", Interval.HOURLY, 60_000L));

        assertThat(windows.of("BTC", Interval.HOURLY)).extracting(RoundWindow::startMs).containsExactly(120_000L);
    }

    @Test
    void everyMarketAndIntervalHasItsOwnList() {
        BetWindows windows = new BetWindows();
        windows.placed(window("BTC", Interval.HOURLY, 60_000L));
        windows.placed(window("BTC", Interval.DAILY, 120_000L));
        windows.placed(window("ETH", Interval.HOURLY, 180_000L));

        assertThat(windows.of("BTC", Interval.HOURLY)).extracting(RoundWindow::startMs).containsExactly(60_000L);
        assertThat(windows.of("BTC", Interval.DAILY)).extracting(RoundWindow::startMs).containsExactly(120_000L);
        assertThat(windows.of("ETH", Interval.HOURLY)).extracting(RoundWindow::startMs).containsExactly(180_000L);
        assertThat(windows.of("ETH", Interval.DAILY)).isEmpty();
    }

    @Test
    void theHubKeepsItsIndexWithItsStateAndAdoptingAHubTakesTheIndexToo() {
        EngineHub live = new EngineHub();
        EngineHub aside = new EngineHub();
        aside.betWindows().placed(window("BTC", Interval.HOURLY, 60_000L));
        assertThat(live.betWindows().of("BTC", Interval.HOURLY)).isEmpty();

        live.adopt(aside);

        assertThat(live.betWindows().of("BTC", Interval.HOURLY)).extracting(RoundWindow::startMs)
                .containsExactly(60_000L);
    }
}
