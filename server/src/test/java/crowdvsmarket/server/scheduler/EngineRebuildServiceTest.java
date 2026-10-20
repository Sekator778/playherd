package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotService;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundCalendar;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.service.BoundaryPrices;
import crowdvsmarket.server.service.GameService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The state machine of {@link EngineRebuildService}: not ready until the first rebuild, ready after it,
 * not ready again from a failure that may have left memory ahead of the database until a rebuild
 * succeeds, retried by the scheduler no more often than the retry gap — and a plain rebuild that fails
 * changes nothing. Every collaborator is a plain object or a mock: what is checked is the sequencing, not
 * the replay (that is {@code FailStopRecoveryTest}, over the real application).
 */
class EngineRebuildServiceTest {

    private EngineRebuildService service;
    private EngineStateBuilder builder;
    private BoundaryPrices boundaryPrices;
    private GameService gameService;
    private BotService botService;
    private PlayerDirectory players;
    private RoundProgress progress;
    private EngineHub hub;

    private static EngineStateBuilder.Built built(RoundProgress.SeriesKey series, RoundWindow cursor) {
        return new EngineStateBuilder.Built(new EngineHub(), new BetFeed(), Map.of(series, cursor), null, 3, 1, " x");
    }

    private final RoundProgress.SeriesKey btcHourly = new RoundProgress.SeriesKey("BTC", Interval.HOURLY);
    private final RoundWindow cursor = new RoundWindow("BTC", Interval.HOURLY, 60_000L, 120_000L);

    @BeforeEach
    void wire() {
        service = new EngineRebuildService();
        service.roundCalendar = mock(RoundCalendar.class);
        players = mock(PlayerDirectory.class);
        when(players.all()).thenReturn(java.util.List.of());
        service.players = players;
        service.marketRegistry = mock(MarketRegistry.class);
        hub = new EngineHub();
        service.hub = hub;
        service.betFeed = new BetFeed();
        progress = new RoundProgress();
        service.roundProgress = progress;
        builder = mock(EngineStateBuilder.class);
        service.builder = builder;
        boundaryPrices = mock(BoundaryPrices.class);
        service.boundaryPrices = boundaryPrices;
        botService = mock(BotService.class);
        service.botService = botService;
        gameService = mock(GameService.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(gameService).runExclusively(any(Runnable.class));
        service.gameService = gameService;
        service.retryGapMs = 0;
    }

    @Test
    void aServiceThatNeverRebuiltIsNotReadyAndHasNothingToRetry() {
        assertThat(service.isReady()).isFalse();

        assertThat(service.retryPendingRebuild()).as("the first rebuild is the startup observer's").isFalse();

        verify(gameService, never()).runExclusively(any());
        verify(builder, never()).build(anyLong());
    }

    @Test
    void aRebuildSwapsTheBuiltStateInAndMakesTheServiceReady() {
        when(builder.build(1_000L)).thenReturn(built(btcHourly, cursor));

        service.rebuild(1_000L);

        assertThat(service.isReady()).isTrue();
        assertThat(progress.next("BTC", Interval.HOURLY)).isEqualTo(cursor);
        verify(players).reload();
        verify(service.marketRegistry).reload();
        verify(botService).ensureBotsExist(1_000L); // after the engine lock, like before
        assertThat(service.retryPendingRebuild()).isTrue();
    }

    @Test
    void theReadViewsOfTheAdoptedStateArePublishedBeforeTheServiceIsReadyAndNotWhenTheRebuildFails() {
        java.util.List<String> order = new java.util.ArrayList<>();
        doAnswer(inv -> {
            order.add("views, ready=" + service.isReady() + ", cursor=" + progress.findNext("BTC", Interval.HOURLY).isPresent());
            return null;
        }).when(gameService).publishAllViews();
        when(builder.build(1_000L)).thenReturn(built(btcHourly, cursor));

        service.rebuild(1_000L);

        assertThat(order).as("after the swap, before ready").containsExactly("views, ready=false, cursor=true");
        assertThat(service.isReady()).isTrue();

        when(builder.build(2_000L)).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> service.rebuild(2_000L)).hasMessageContaining("db down");
        assertThat(order).as("a rebuild that failed publishes nothing").hasSize(1);
    }

    @Test
    void aRebuildThatFailsBeforeTheSwapChangesNothingAndLeavesAReadyServiceReady() {
        when(builder.build(anyLong())).thenReturn(built(btcHourly, cursor));
        service.rebuild(1_000L);
        var ledgerBefore = hub.ledger();
        when(builder.build(2_000L)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.rebuild(2_000L)).hasMessageContaining("db down");

        assertThat(service.isReady()).isTrue();
        assertThat(hub.ledger()).isSameAs(ledgerBefore);
        assertThat(progress.next("BTC", Interval.HOURLY)).isEqualTo(cursor);
        verify(botService, times(1)).ensureBotsExist(anyLong()); // not after a rebuild that failed
    }

    @Test
    void aFailedRecoveryIsNotReadyUntilARetrySucceeds() {
        when(builder.build(anyLong())).thenReturn(built(btcHourly, cursor));
        service.rebuild(1_000L);
        assertThat(service.isReady()).isTrue();
        when(builder.build(anyLong())).thenThrow(new IllegalStateException("db down"));
        Throwable cause = new IllegalStateException("the settlement failed");

        service.recoverAfterFailure(cause);   // never throws

        assertThat(service.isReady()).isFalse();
        assertThat(cause.getSuppressed()).singleElement().satisfies(s -> assertThat(s).hasMessage("db down"));
        assertThat(service.retryPendingRebuild()).as("still down").isFalse();
        assertThat(service.isReady()).isFalse();

        doReturn(built(btcHourly, cursor)).when(builder).build(anyLong());   // (when(..) would call the throwing stub)
        assertThat(service.retryPendingRebuild()).isTrue();
        assertThat(service.isReady()).isTrue();
        int builds = org.mockito.Mockito.mockingDetails(builder).getInvocations().size();
        assertThat(service.retryPendingRebuild()).as("ready again: nothing left to retry").isTrue();
        assertThat(org.mockito.Mockito.mockingDetails(builder).getInvocations()).hasSize(builds);
    }

    @Test
    void theRetryWaitsForTheGapBetweenAttempts() throws Exception {
        when(builder.build(anyLong())).thenThrow(new IllegalStateException("db down"));
        service.retryGapMs = 150;
        service.recoverAfterFailure(new IllegalStateException("the settlement failed"));   // attempt 1
        verify(builder, times(1)).build(anyLong());

        assertThat(service.retryPendingRebuild()).isFalse();
        verify(builder, times(1)).build(anyLong());   // inside the gap: no second attempt

        Thread.sleep(200);
        assertThat(service.retryPendingRebuild()).isFalse();
        verify(builder, times(2)).build(anyLong());   // after it: one more
        assertThat(service.retryPendingRebuild()).isFalse();
        verify(builder, times(2)).build(anyLong());   // and the gap starts over
    }

    /** What the service logs at ERROR. */
    private static final class Errors extends java.util.logging.Handler {
        final java.util.List<java.util.logging.LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void publish(java.util.logging.LogRecord record) {
            if (record.getLevel().intValue() >= java.util.logging.Level.SEVERE.intValue()) {
                records.add(record);
            }
        }

        long withTrace() {
            return records.stream().filter(r -> r.getThrown() != null).count();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    void theRecoveryOfAKeyIsLoggedWithATraceOnceThenALineAMinuteAndWorkingAgainMakesTheNextOneAFirstOneAgain() {
        when(builder.build(anyLong())).thenReturn(built(btcHourly, cursor));
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(EngineRebuildService.class.getName());
        Errors errors = new Errors();
        log.addHandler(errors);
        try {
            service.recoverAfterFailure(new IllegalStateException("one"), "round A", 1_000L);    // first: with its trace
            service.recoverAfterFailure(new IllegalStateException("two"), "round A", 2_000L);    // inside the minute
            service.recoverAfterFailure(new IllegalStateException("three"), "round A", 61_000L); // a minute on: a line
            service.recoverAfterFailure(new IllegalStateException("four"), "round B", 62_000L);  // another key: first
            service.recovered("round A");
            service.recoverAfterFailure(new IllegalStateException("five"), "round A", 63_000L);  // first again
        } finally {
            log.removeHandler(errors);
        }

        assertThat(errors.records).as("three traces and one line, nothing for the failure inside the minute")
                .hasSize(4);
        assertThat(errors.withTrace()).isEqualTo(3);
        verify(builder, times(5)).build(anyLong());   // but the engine is rebuilt after every one of them
        assertThat(service.isReady()).isTrue();
    }

    @Test
    void aPlainRebuildThatSucceedsEndsAPendingRecoveryToo() {
        when(builder.build(anyLong())).thenThrow(new IllegalStateException("db down"));
        service.recoverAfterFailure(new IllegalStateException("the settlement failed"));
        assertThat(service.isReady()).isFalse();
        doReturn(built(btcHourly, cursor)).when(builder).build(anyLong());

        service.rebuild(5_000L);   // e.g. an operator's restart of the engine

        assertThat(service.isReady()).isTrue();
        int builds = org.mockito.Mockito.mockingDetails(builder).getInvocations().size();
        assertThat(service.retryPendingRebuild()).isTrue();
        assertThat(org.mockito.Mockito.mockingDetails(builder).getInvocations()).hasSize(builds);
    }
}
