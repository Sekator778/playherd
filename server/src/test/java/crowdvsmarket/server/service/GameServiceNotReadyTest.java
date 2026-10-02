package crowdvsmarket.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.RoundCalendar;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.markets.MarketStatus;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A market listed or closed before the engine is ready — before the first rebuild has finished, or while a
 * failed recovery has not succeeded yet — must not throw out of the registry's {@code list}/{@code close}
 * (review of the multi-market core, finding 5): {@code onListed} used to reach {@code
 * RoundCalendar.windowContaining} and fail with "RoundCalendar.init() was not called yet", after the
 * listing had been committed. The rebuild creates the engines of every market in the registry and seeds the
 * running ones, so the listener has nothing to do: it does nothing, and touches nothing. Plain objects
 * stand in for the application — the calendar is the very one a process has before its first rebuild.
 */
class GameServiceNotReadyTest {

    private GameService service;
    private EngineHub hub;
    private RoundProgress progress;

    private static Market market(String id, MarketStatus status) {
        return new Market(id, MarketKind.PERP, "crypto", id, id, null, null, "synthetic", id, null, 10, 0, 1, status,
                0L, status == MarketStatus.CLOSED ? 1L : null, status == MarketStatus.CLOSED ? "gone" : null, null);
    }

    @BeforeEach
    void anEngineThatIsNotReady() {
        service = new GameService();
        EngineRebuildService rebuildService = mock(EngineRebuildService.class);
        when(rebuildService.isReady()).thenReturn(false);
        service.rebuildService = rebuildService;
        hub = new EngineHub();
        service.hub = hub;
        progress = new RoundProgress();
        service.roundProgress = progress;
        GameConfig config = mock(GameConfig.class);
        when(config.mode()).thenReturn(GameMode.LIVE);
        service.config = config;
        service.marketRegistry = mock(MarketRegistry.class);
        service.seeder = new SeriesSeeder();
        service.seeder.calendar = new RoundCalendar(); // init() has not run: asking it for a window throws
        // roundResults, eventStore, cancellations ... stay null: touching any of them fails the test
    }

    @Test
    void aListingIsANoOp() {
        Market listed = market("T_EARLY", MarketStatus.ACTIVE);

        assertThatCode(() -> service.onListed(listed)).doesNotThrowAnyException();
        assertThatCode(() -> service.ensureSeries(listed, 123_456L)).doesNotThrowAnyException();

        assertThat(hub.findMarket("T_EARLY")).as("no engines are made on a state that cannot be trusted").isEmpty();
        assertThat(progress.isSeeded("T_EARLY", Interval.HOURLY)).isFalse();
        assertThat(progress.isSeeded("T_EARLY", Interval.DAILY)).isFalse();
    }

    @Test
    void aClosingIsANoOpToo() {
        Market closed = market("T_EARLYCLOSE", MarketStatus.CLOSED);

        assertThatCode(() -> service.onClosed(closed, "gone")).doesNotThrowAnyException();

        assertThat(hub.findMarket("T_EARLYCLOSE")).isEmpty();
    }
}
