package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.markets.MarketStatus;
import crowdvsmarket.server.prices.MarketPrices;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link GameService#openRounds} on plain objects (Р-76): every tick the scheduler asks the prices to open the running
 * round of each series of each market that has a backup exchange, once that round has started — and only those. A market's
 * failure never stops the others, and nothing is asked in replay mode.
 */
class GameServiceOpenRoundsTest {

    private static final long STEP = 60_000L;
    private static final long DAY = 120_000L;
    private static final String BACKUP = "{\"backup\":{\"source\":\"kraken\",\"ref\":\"BTC/USD\"}}";

    private GameService service;
    private MarketPrices marketPrices;
    private MarketRegistry registry;
    private RoundProgress progress;
    private GameConfig config;

    private static Market market(String id, String meta) {
        return new Market(id, MarketKind.PERP, "crypto", id, id, null, null, "coinbase", id + "-USD", meta, 10, 0, 1,
                MarketStatus.ACTIVE, 0L, null, null, null);
    }

    private static RoundWindow hourly(Market market, long start) {
        return new RoundWindow(market.id(), Interval.HOURLY, start, start + STEP);
    }

    private static RoundWindow daily(Market market, long start) {
        return new RoundWindow(market.id(), Interval.DAILY, start, start + DAY);
    }

    @BeforeEach
    void plainObjects() {
        service = new GameService();
        config = mock(GameConfig.class);
        when(config.mode()).thenReturn(GameMode.LIVE);
        service.config = config;
        registry = mock(MarketRegistry.class);
        service.marketRegistry = registry;
        progress = new RoundProgress();
        service.roundProgress = progress;
        marketPrices = mock(MarketPrices.class);
        service.marketPrices = marketPrices;
    }

    @Test
    void theRunningRoundOfEverySeriesOfAMarketWithABackupIsOpenedOnceItHasStarted() {
        Market spot = market("spot_BTC", BACKUP);
        when(registry.active()).thenReturn(List.of(spot));
        progress.seed(spot.id(), Interval.HOURLY, hourly(spot, STEP));
        progress.seed(spot.id(), Interval.DAILY, daily(spot, 0L));

        service.openRounds(STEP + 5_000L);

        verify(marketPrices).openRound(spot, hourly(spot, STEP), STEP + 5_000L);
        verify(marketPrices).openRound(spot, daily(spot, 0L), STEP + 5_000L);
    }

    @Test
    void aRoundThatHasNotStartedYetIsLeftAlone() {
        Market spot = market("spot_BTC", BACKUP);
        when(registry.active()).thenReturn(List.of(spot));
        progress.seed(spot.id(), Interval.HOURLY, hourly(spot, STEP));
        progress.seed(spot.id(), Interval.DAILY, daily(spot, DAY));

        service.openRounds(STEP - 1);

        verify(marketPrices, never()).openRound(any(), any(), anyLong());
        service.openRounds(STEP);
        verify(marketPrices).openRound(spot, hourly(spot, STEP), STEP);
        verify(marketPrices, never()).openRound(eq(spot), eq(daily(spot, DAY)), anyLong());
    }

    @Test
    void aMarketWithoutABackupIsNeverAskedAndNeitherIsASeriesThatIsNotSeeded() {
        Market spot = market("spot_BTC", BACKUP);
        Market plain = market("BTC", null);
        Market other = market("DOGE", "{\"gameStartTime\":\"2026-10-01 18:45:00+00\"}");
        when(registry.active()).thenReturn(List.of(plain, other, spot));
        progress.seed(plain.id(), Interval.HOURLY, hourly(plain, STEP));
        progress.seed(other.id(), Interval.HOURLY, hourly(other, STEP));
        progress.seed(spot.id(), Interval.HOURLY, hourly(spot, STEP)); // its daily series has no cursor

        service.openRounds(2 * STEP);

        verify(marketPrices).openRound(spot, hourly(spot, STEP), 2 * STEP);
        verify(marketPrices, never()).openRound(eq(plain), any(), anyLong());
        verify(marketPrices, never()).openRound(eq(other), any(), anyLong());
        verify(marketPrices, never()).openRound(eq(spot), eq(daily(spot, 0L)), anyLong());
    }

    @Test
    void oneMarketsFailureStopsNeitherItsOtherSeriesNorTheOtherMarkets() {
        Market first = market("spot_BTC", BACKUP);
        Market second = market("spot_ETH", BACKUP);
        when(registry.active()).thenReturn(List.of(first, second));
        for (Market m : List.of(first, second)) {
            progress.seed(m.id(), Interval.HOURLY, hourly(m, STEP));
            progress.seed(m.id(), Interval.DAILY, daily(m, 0L));
        }
        when(marketPrices.openRound(eq(first), eq(hourly(first, STEP)), anyLong()))
                .thenThrow(new IllegalStateException("db is down"));

        assertThatCode(() -> service.openRounds(2 * STEP)).doesNotThrowAnyException();
        assertThatCode(() -> service.openRounds(2 * STEP + 1)).doesNotThrowAnyException();

        verify(marketPrices).openRound(first, daily(first, 0L), 2 * STEP);
        verify(marketPrices).openRound(second, hourly(second, STEP), 2 * STEP);
        verify(marketPrices).openRound(second, daily(second, 0L), 2 * STEP);
    }

    @Test
    void inReplayModeNothingIsAsked() {
        when(config.mode()).thenReturn(GameMode.REPLAY);
        Market spot = market("spot_BTC", BACKUP);
        when(registry.active()).thenReturn(List.of(spot));
        progress.seed(spot.id(), Interval.HOURLY, hourly(spot, STEP));

        service.openRounds(2 * STEP);

        verifyNoInteractions(marketPrices);
    }

    @Test
    void theAnswerIsNotNeededByTheCaller() {
        Market spot = market("spot_BTC", BACKUP);
        when(registry.active()).thenReturn(List.of(spot));
        progress.seed(spot.id(), Interval.HOURLY, hourly(spot, STEP));
        when(marketPrices.openRound(any(), any(), anyLong())).thenReturn(Optional.of("kraken"));

        assertThatCode(() -> service.openRounds(2 * STEP)).doesNotThrowAnyException();
        verify(marketPrices).openRound(spot, hourly(spot, STEP), 2 * STEP);
    }
}
