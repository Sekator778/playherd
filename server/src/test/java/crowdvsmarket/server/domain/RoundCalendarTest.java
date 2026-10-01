package crowdvsmarket.server.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundDurations;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.persistence.ServerMetaRepository;
import org.junit.jupiter.api.Test;

/**
 * Т-11 (docs/research/markets_architecture.md §2): a FRESH database in {@code mode=live} gets an
 * origin that is a multiple of the daily series' length from the epoch; {@code mode=replay} and any
 * database that already has an origin are unchanged.
 */
class RoundCalendarTest {

    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;
    /** 2026-10-01T14:37:21.456Z */
    private static final long NOW = 1_790_865_441_456L;

    private static RoundCalendar calendar(GameMode mode, RoundDurations durations, ServerMetaRepository meta) {
        GameConfig config = mock(GameConfig.class);
        when(config.mode()).thenReturn(mode);
        when(config.roundDurations()).thenReturn(durations);
        RoundCalendar calendar = new RoundCalendar();
        calendar.config = config;
        calendar.serverMeta = meta;
        return calendar;
    }

    private static ServerMetaRepository freshDatabase() {
        ServerMetaRepository meta = mock(ServerMetaRepository.class);
        when(meta.originMsOrInit(anyLong())).thenAnswer(inv -> inv.getArgument(0)); // nothing there: keeps what is offered
        return meta;
    }

    @Test
    void freshDatabaseInLiveModeStartsOnTheDailyBoundary() {
        ServerMetaRepository meta = freshDatabase();
        RoundCalendar calendar = calendar(GameMode.LIVE, new RoundDurations(HOUR, DAY), meta);

        long origin = calendar.init(NOW);

        assertThat(origin).isEqualTo(NOW - NOW % DAY).isEqualTo(1_790_812_800_000L); // 2026-10-01T00:00:00Z
        assertThat(origin % DAY).isZero();
        verify(meta).originMsOrInit(1_790_812_800_000L);
        assertThat(calendar.originMs()).isEqualTo(origin);
    }

    @Test
    void liveBoundariesAreEpochMultiplesForBothSeries() {
        RoundCalendar calendar = calendar(GameMode.LIVE, new RoundDurations(HOUR, DAY), freshDatabase());
        calendar.init(NOW);

        RoundWindow hourly = calendar.windowContaining("BTC", Interval.HOURLY, NOW);
        RoundWindow daily = calendar.windowContaining("ETH", Interval.DAILY, NOW);

        assertThat(hourly.startMs() % HOUR).isZero();
        assertThat(hourly.startMs()).isEqualTo(1_790_863_200_000L); // 14:00:00Z
        assertThat(hourly.endMs() - hourly.startMs()).isEqualTo(HOUR);
        assertThat(daily.startMs() % DAY).isZero();
        assertThat(daily.endMs() - daily.startMs()).isEqualTo(DAY);
        assertThat(hourly.market()).isEqualTo("BTC");
        assertThat(daily.market()).isEqualTo("ETH");
    }

    @Test
    void standDurationsLandOnTheThreeAndFifteenMinuteGrid() {
        RoundCalendar calendar = calendar(GameMode.LIVE, new RoundDurations(180_000L, 900_000L), freshDatabase());
        long origin = calendar.init(NOW);

        assertThat(origin % 900_000L).isZero();
        assertThat(calendar.windowContaining("BTC", Interval.HOURLY, NOW).startMs() % 180_000L).isZero();
        assertThat(calendar.windowContaining("BTC", Interval.DAILY, NOW).startMs() % 900_000L).isZero();
    }

    @Test
    void replayModeKeepsTheFirstBootInstantAsOrigin() {
        ServerMetaRepository meta = freshDatabase();
        RoundCalendar calendar = calendar(GameMode.REPLAY, new RoundDurations(180_000L, 900_000L), meta);

        assertThat(calendar.init(NOW)).isEqualTo(NOW);
        verify(meta).originMsOrInit(NOW);
    }

    @Test
    void aDatabaseThatAlreadyHasAnOriginKeepsIt() {
        ServerMetaRepository meta = mock(ServerMetaRepository.class);
        when(meta.originMsOrInit(anyLong())).thenReturn(1_700_000_000_123L); // stored long ago, off the grid
        RoundCalendar calendar = calendar(GameMode.LIVE, new RoundDurations(HOUR, DAY), meta);

        assertThat(calendar.init(NOW)).isEqualTo(1_700_000_000_123L);
        assertThat(calendar.originMs()).isEqualTo(1_700_000_000_123L);
        RoundWindow window = calendar.windowContaining("BTC", Interval.HOURLY, NOW);
        assertThat((window.startMs() - 1_700_000_000_123L) % HOUR).isZero();
    }

    @Test
    void firstWindowStartsExactlyAtTheOriginAndCarriesTheMarket() {
        RoundCalendar calendar = calendar(GameMode.REPLAY, new RoundDurations(180_000L, 900_000L), freshDatabase());
        long origin = calendar.init(NOW);

        RoundWindow first = calendar.firstWindow("PM123", Interval.HOURLY);

        assertThat(first.startMs()).isEqualTo(origin);
        assertThat(first.endMs()).isEqualTo(origin + 180_000L);
        assertThat(first.roundId()).isEqualTo("PM123-1h-" + origin);
    }
}
