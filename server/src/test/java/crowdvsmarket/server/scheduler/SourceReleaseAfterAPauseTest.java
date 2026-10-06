package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundDurations;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotService;
import crowdvsmarket.server.chain.OracleSeqGate;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.prices.Instruments;
import crowdvsmarket.server.prices.PriceUnavailableException;
import crowdvsmarket.server.prices.PricesConfig;
import crowdvsmarket.server.prices.TestMarkets;
import crowdvsmarket.server.service.GameService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the minutes of a round that cannot be priced (Р-79, and the two minutes of Р-77) are counted from, through the scheduled
 * pass itself — the {@code @Scheduled} {@code tick()}, one second apart on a clock the test owns, not the {@code tick(now)} that
 * other tests drive. They are counted from the end of the round or from the start of the server, whichever is later: a restart gives
 * every round that was in flight its minutes to heal from the candles, instead of letting it go at its first failed attempt. A
 * <i>pause</i> of the whole process does the same to the candles' healing as a restart does — a laptop that sleeps with Docker paused
 * (docs/HANDOFF.md §2: the containers survive the sleep) wakes up in the same JVM with the feeds reconnecting and every round that ended
 * in the meantime overdue — so the pass that follows a pause is a start too: the scheduler remembers when its previous scheduled pass
 * ended, and a pass that begins more than fifteen seconds after that, or is the first one of all, takes the minutes from its own
 * instant. The time between two passes is what counts, not how long a pass took: a slow pass (a price source that answers slowly) is
 * no pause.
 *
 * <p>The scheduler is a plain object with mocks around it, as in {@code SourceReleaseSchedulerTest}; the market's source is silent
 * while {@code silent} holds (a settlement throws {@link PriceUnavailableException}) and every round carries a bet.
 */
class SourceReleaseAfterAPauseTest {

    private static final long SEC = 1_000L;
    private static final long MIN = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;
    /** 2026-10-03T12:00:00Z: a whole hour. */
    private static final long S = 1_791_028_800_000L;
    private static final String SOURCE_REASON = "No price from the source; stakes returned";
    private static final String NO_PRICE_REASON = "no oracle price available within 1 hour of round close";

    /** A perpetual of the dex xyz: its source's five minutes, and no backup exchange. */
    private final Market xyzNvda = TestMarkets.perp("xyz_NVDA", "xyz:NVDA", Category.STOCKS, 10, 2, 5);

    private final GameService gameService = mock(GameService.class);
    private final EngineRebuildService rebuildService = mock(EngineRebuildService.class);
    private final BotService botService = mock(BotService.class);
    private final OraclePublisherService oraclePublisher = mock(OraclePublisherService.class);
    private final RoundResultRepository roundResults = mock(RoundResultRepository.class);
    private final OracleSeqGate gate = mock(OracleSeqGate.class);
    private final GameConfig config = mock(GameConfig.class);
    private final RoundProgress progress = new RoundProgress();
    private RoundSchedulerService scheduler;

    /** The wall clock the scheduler reads, and the test sets. */
    private final AtomicLong clock = new AtomicLong(S);
    /** Whether a settlement throws: no source gives a price. */
    private final AtomicBoolean silent = new AtomicBoolean(true);
    /** Whether the market's other exchange is alive (a market with a backup). */
    private final AtomicBoolean otherAlive = new AtomicBoolean(false);
    /** The exchange the rounds of a market were decided on, by market id. */
    private final Map<String, String> decidedOn = new HashMap<>();
    /** How long the next pass works for, in simulated time: the bots' turn takes this long, once. */
    private final AtomicLong nextPassTakes = new AtomicLong(0);
    /** The markets that run. */
    private List<Market> running = List.of();

    private record Settlement(String roundId, long atMs) {
    }

    private record Cancellation(String roundId, String reason, long atMs) {
    }

    private final List<Settlement> settlements = new ArrayList<>();
    private final List<Cancellation> cancellations = new ArrayList<>();

    @BeforeEach
    void setUp() {
        scheduler = new RoundSchedulerService();
        scheduler.gameService = gameService;
        scheduler.botService = botService;
        scheduler.oraclePublisher = oraclePublisher;
        scheduler.rebuildService = rebuildService;
        scheduler.roundProgress = progress;
        scheduler.roundResults = roundResults;
        scheduler.oracleSeqGate = gate;
        scheduler.config = config;
        scheduler.pricesConfig = PricesConfig.defaults();
        scheduler.wallClock = clock::get;
        when(config.roundDurations()).thenReturn(new RoundDurations(HOUR, DAY));
        when(rebuildService.isReady()).thenReturn(true);
        when(gate.open()).thenReturn(true);
        when(gameService.runningMarkets()).thenAnswer(inv -> running);
        when(gameService.firstWindowToResolve(any(), anyLong())).thenAnswer(inv -> inv.getArgument(0));
        when(gameService.hasPendingBets(any())).thenReturn(true);
        when(gameService.instrumentOf(any(), any())).thenAnswer(inv -> {
            Market market = inv.getArgument(0);
            return Instruments.runningOn(market, Optional.ofNullable(decidedOn.get(market.id())));
        });
        when(gameService.otherVenueLive(any(), any(), anyLong())).thenAnswer(inv -> otherAlive.get());
        when(gameService.resolveRound(any(), anyLong())).thenAnswer(inv -> {
            RoundWindow window = inv.getArgument(0);
            settlements.add(new Settlement(window.roundId(), inv.getArgument(1)));
            if (silent.get()) {
                throw new PriceUnavailableException("no tick fresh at the end of the round");
            }
            return null; // settled
        });
        when(gameService.cancelRound(any(), any(), anyLong())).thenAnswer(inv -> {
            RoundWindow window = inv.getArgument(0);
            cancellations.add(new Cancellation(window.roundId(), inv.getArgument(1), inv.getArgument(2)));
            return new Rounds.CancellationDto(window.roundId(), window.market(), "X", inv.getArgument(1), inv.getArgument(2), 0);
        });
        // a slow pass: the bots' turn, which comes first in a pass, takes its time on the test's clock
        doAnswer(inv -> {
            clock.addAndGet(nextPassTakes.getAndSet(0));
            return null;
        }).when(botService).tick(anyLong());
    }

    private static RoundWindow hourly(Market market, long start) {
        return new RoundWindow(market.id(), Interval.HOURLY, start, start + HOUR);
    }

    /** The market runs, with its hourly series at {@code window} and its daily one parked far ahead: only one round is in play. */
    private void run(Market market, RoundWindow window) {
        running = List.of(market);
        progress.seed(market.id(), Interval.HOURLY, window);
        progress.seed(market.id(), Interval.DAILY, new RoundWindow(market.id(), Interval.DAILY, S + 30 * DAY, S + 31 * DAY));
    }

    /** One scheduled pass starting at {@code at}: what Quarkus does every second. */
    private void pass(long at) {
        clock.set(at);
        scheduler.tick();
    }

    /** A pass every second from {@code fromMs} to {@code toMs}, both included. */
    private void everySecond(long fromMs, long toMs) {
        for (long at = fromMs; at <= toMs; at += SEC) {
            pass(at);
        }
    }

    // ------------------------------------------------------------------ a nap is a start

    @Test
    void afterANapTheCandlesHealTheRoundThatEndedWhileTheHostSleptAndNothingIsRefundedAtTheWake() {
        RoundWindow w0 = hourly(xyzNvda, S);
        run(xyzNvda, w0);
        everySecond(w0.endMs() - 2 * MIN - 10 * SEC, w0.endMs() - 2 * MIN); // the server has been up, the round is still running
        long wake = w0.endMs() + 20 * MIN; // the host slept across the end of the round: it is twenty minutes overdue at the wake

        pass(wake); // the feed is not back yet: the first attempt after the wake fails

        assertThat(cancellations).as("its five minutes are not over: they count from the wake, not from a start long ago").isEmpty();
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(w0);

        silent.set(false); // two seconds after the wake the feed is back and the candles close the hole
        everySecond(wake + SEC, wake + 3 * SEC);

        assertThat(settlements).extracting(Settlement::atMs).as("the failed attempt at the wake, and the one that heals it")
                .containsExactly(wake, wake + 2 * SEC);
        assertThat(cancellations).isEmpty();
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).as("settled, and the series goes on").contains(RoundClock.next(w0));
    }

    @Test
    void afterANapWithTheFeedStillSilentTheRoundIsLetGoFiveMinutesAfterTheWakeAndNotBefore() {
        RoundWindow w0 = hourly(xyzNvda, S);
        run(xyzNvda, w0);
        everySecond(w0.endMs() - 2 * MIN - 10 * SEC, w0.endMs() - 2 * MIN);
        long wake = w0.endMs() + 20 * MIN;

        everySecond(wake, wake + 5 * MIN - SEC);

        assertThat(cancellations).as("not at the first attempt, not at any of the 299 seconds that follow").isEmpty();
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(w0);

        pass(wake + 5 * MIN);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.roundId()).isEqualTo(w0.roundId());
            assertThat(c.reason()).isEqualTo(SOURCE_REASON);
            assertThat(c.atMs()).as("five minutes after the wake, at the second they are over").isEqualTo(wake + 5 * MIN);
        });
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).as("the series goes on").contains(RoundClock.next(w0));
    }

    @Test
    void aRoundMoreThanAnHourBehindAtTheWakeIsRefundedAtOnceWithTheWordsOfTheHour() {
        RoundWindow w0 = hourly(xyzNvda, S);
        run(xyzNvda, w0);
        everySecond(w0.endMs() - 2 * MIN - 10 * SEC, w0.endMs() - 2 * MIN);
        long wake = w0.endMs() + HOUR + MIN; // the round ended an hour and a minute ago: no candles heal that

        pass(wake);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).as("the hour's own words").isEqualTo(NO_PRICE_REASON);
            assertThat(c.atMs()).isEqualTo(wake);
        });
    }

    // ------------------------------------------------------------------ the first pass is a start

    @Test
    void theFirstScheduledPassIsTheStartOfTheServerEvenWhenNoStartupObserverHasRunAndTheEngineIsNotReadyYet() {
        assertThat(scheduler.startedAtMs).as("nothing has run: the beginning of time").isZero();
        RoundWindow w0 = hourly(xyzNvda, S);
        run(xyzNvda, w0);
        long first = w0.endMs() + 20 * MIN; // the server starts twenty minutes after the round ended
        when(rebuildService.isReady()).thenReturn(false);
        when(rebuildService.retryPendingRebuild(anyLong())).thenReturn(false);

        everySecond(first, first + 3 * SEC); // the engine is being rebuilt: the passes do nothing but ask for it

        assertThat(scheduler.startedAtMs).as("the first pass is the start").isEqualTo(first);
        assertThat(settlements).isEmpty();

        when(rebuildService.isReady()).thenReturn(true);
        everySecond(first + 4 * SEC, first + 5 * MIN - SEC);

        assertThat(scheduler.startedAtMs).as("the passes since then are no pauses").isEqualTo(first);
        assertThat(cancellations).as("its five minutes count from the start").isEmpty();
        pass(first + 5 * MIN);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(SOURCE_REASON);
            assertThat(c.atMs()).isEqualTo(first + 5 * MIN);
        });
    }

    // ------------------------------------------------------------------ what a pause is

    @Test
    void aGapOfTenSecondsBetweenTwoPassesIsNoPauseAndOneOfTwentyIs() {
        long t = S;
        pass(t);
        assertThat(scheduler.startedAtMs).isEqualTo(t);

        pass(t + 10 * SEC);
        assertThat(scheduler.startedAtMs).as("ten seconds: a busy second, not a sleep").isEqualTo(t);

        pass(t + 30 * SEC);
        assertThat(scheduler.startedAtMs).as("twenty seconds since the previous pass ended: the process was paused").isEqualTo(t + 30 * SEC);
    }

    @Test
    void aSlowPassIsNoPauseOnlyTheTimeBetweenTwoPassesCounts() {
        RoundWindow w0 = hourly(xyzNvda, S);
        run(xyzNvda, w0);
        long first = w0.endMs() - 3 * MIN;
        everySecond(first, w0.endMs() - 11 * SEC);

        nextPassTakes.set(40 * SEC); // a price source answers slowly: this pass lasts forty seconds, across the end of the round
        pass(w0.endMs() - 10 * SEC);
        assertThat(clock.get()).as("the pass ended thirty seconds after the end of the round").isEqualTo(w0.endMs() + 30 * SEC);
        everySecond(clock.get() + SEC, w0.endMs() + 5 * MIN - SEC);

        assertThat(scheduler.startedAtMs).as("forty seconds of work are no pause: the start is still the first pass").isEqualTo(first);
        assertThat(cancellations).isEmpty();
        pass(w0.endMs() + 5 * MIN);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(SOURCE_REASON);
            assertThat(c.atMs()).as("five minutes after the end of the round, as the start of the server was before it").isEqualTo(w0.endMs() + 5 * MIN);
        });
    }

    @Test
    void aPassThatFailsHasEndedTooSoALongRunOfFailingPassesIsNoPause() {
        running = List.of(xyzNvda);
        long t = S;
        pass(t);

        doThrow(new IllegalStateException("the database does not answer")).when(gameService).runningMarkets();
        for (long at = t + SEC; at <= t + 40 * SEC; at += SEC) {
            long failingAt = at;
            assertThatThrownBy(() -> pass(failingAt)).isInstanceOf(IllegalStateException.class);
        }
        doAnswer(inv -> running).when(gameService).runningMarkets();
        pass(t + 41 * SEC);

        assertThat(scheduler.startedAtMs).as("forty seconds of failing passes, one a second: the process was running all along").isEqualTo(t);
    }
}
