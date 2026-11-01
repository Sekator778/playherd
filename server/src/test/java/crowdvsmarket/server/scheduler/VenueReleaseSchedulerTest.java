package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundDurations;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.chain.OracleSeqGate;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.prices.PriceUnavailableException;
import crowdvsmarket.server.prices.PricesConfig;
import crowdvsmarket.server.prices.TestMarkets;
import crowdvsmarket.server.service.GameService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Letting a round go (D-77) on the scheduler alone, with plain objects around it: the rounds of a series are settled in
 * order, so a round whose exchange stopped sending prices holds the series — and, when the market's other exchange is alive,
 * it is cancelled with a refund {@code herd.prices.venue-release-after-ms} (two minutes) after its end — or after the start of
 * the server, if that is later: a restart gives the round its two minutes again — instead of an hour after it (D-23). What this
 * class pins is the timing and the conditions; what the cancellation writes (the reason, the exchange kept in the result, the
 * refund) is {@code VenueReleaseTest}, over a database, and what "alive" means is {@code LiveMarketPricesReleaseTest}.
 *
 * <p>Time is the test's: {@code resolveOverdueRounds(market, interval, now)} is called with chosen instants, a tick a
 * second where the second matters. The market's exchange is silent while {@code silent} holds: a settlement of any round of it
 * throws {@link PriceUnavailableException}. The release after the minutes of the source (D-79) is switched off here, so that
 * what is pinned is this one rule alone — {@code SourceReleaseSchedulerTest} has the other, and how the two meet.
 */
class VenueReleaseSchedulerTest {

    private static final long SEC = 1_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;
    /** 2026-10-03T12:00:00Z: a whole hour. */
    private static final long S = 1_791_028_800_000L;
    private static final String RELEASE_REASON = "The exchange of this round stopped sending prices; stakes returned";
    private static final String NO_PRICE_REASON = "no oracle price available within 1 hour of round close";
    private static final String SETTLEMENT_FAILED_REASON = "settlement failed within 1 hour of round close";

    private final Market btc = TestMarkets.coinbaseWithKraken("BTC", 0, 1);
    private final Market plain = TestMarkets.coinbase("ETH", 0, 2);

    private final GameService gameService = mock(GameService.class);
    private final OracleSeqGate gate = mock(OracleSeqGate.class);
    private final GameConfig config = mock(GameConfig.class);
    private final RoundProgress progress = new RoundProgress();
    private RoundSchedulerService scheduler;

    /** Whether a settlement throws: the exchange the round runs on gives no price. */
    private final AtomicBoolean silent = new AtomicBoolean(true);
    /** What the settlement throws while {@link #silent}. */
    private final AtomicReference<RuntimeException> failure =
            new AtomicReference<>(new PriceUnavailableException("no tick of coinbase fresh at the end of the round"));
    /** Whether the market's other exchange is alive. */
    private final AtomicBoolean otherAlive = new AtomicBoolean(true);

    private record Settlement(String roundId, long atMs) {
    }

    private record Cancellation(String roundId, String reason, long atMs) {
    }

    private final List<Settlement> settlements = new ArrayList<>();
    private final List<Cancellation> cancellations = new ArrayList<>();
    /** What {@code cancelRound} does with the reason it is given: throws when this says so. */
    private final AtomicReference<String> cancelFailsFor = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        scheduler = new RoundSchedulerService();
        scheduler.gameService = gameService;
        scheduler.roundProgress = progress;
        scheduler.oracleSeqGate = gate;
        scheduler.config = config;
        scheduler.pricesConfig = venueReleaseOnly();
        when(config.roundDurations()).thenReturn(new RoundDurations(HOUR, DAY));
        when(gate.open()).thenReturn(true);
        when(gameService.firstWindowToResolve(any(), anyLong())).thenAnswer(inv -> inv.getArgument(0));
        when(gameService.hasPendingBets(any())).thenReturn(true); // a round with a bet: one nobody bet on is skipped at once (D-79)
        when(gameService.instrumentOf(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(gameService.resolveRound(any(), anyLong())).thenAnswer(inv -> {
            RoundWindow window = inv.getArgument(0);
            long now = inv.getArgument(1);
            settlements.add(new Settlement(window.roundId(), now));
            if (silent.get()) {
                throw failure.get();
            }
            return null; // settled
        });
        when(gameService.otherVenueLive(any(), any(), anyLong())).thenAnswer(inv -> otherAlive.get());
        when(gameService.cancelRound(any(), any(), anyLong())).thenAnswer(inv -> {
            RoundWindow window = inv.getArgument(0);
            String reason = inv.getArgument(1);
            long now = inv.getArgument(2);
            if (reason.equals(cancelFailsFor.get())) {
                throw new IllegalStateException("the database does not answer");
            }
            cancellations.add(new Cancellation(window.roundId(), reason, now));
            return new Rounds.CancellationDto(window.roundId(), window.market(), "BTC", reason, now, 0);
        });
    }

    /** The defaults, with the release after the minutes of the source (D-79) switched off for every source. */
    private static PricesConfig venueReleaseOnly() {
        return PricesConfig.defaults().withReleaseAfter(PricesConfig.ReleaseAfter.off());
    }

    private RoundWindow hourly(long start) {
        return new RoundWindow(btc.id(), Interval.HOURLY, start, start + HOUR);
    }

    private void seedHourly(Market market, RoundWindow window) {
        progress.seed(market.id(), Interval.HOURLY, new RoundWindow(market.id(), Interval.HOURLY, window.startMs(),
                window.endMs()));
    }

    private void tick(Market market, long now) {
        for (Interval interval : Interval.values()) {
            scheduler.resolveOverdueRounds(market, interval, now);
        }
    }

    /** A tick every second from {@code fromMs} to {@code toMs}, both included. */
    private void everySecond(Market market, long fromMs, long toMs) {
        for (long at = fromMs; at <= toMs; at += SEC) {
            tick(market, at);
        }
    }

    private List<Long> attemptsAt(String roundId) {
        return settlements.stream().filter(s -> s.roundId().equals(roundId)).map(Settlement::atMs).toList();
    }

    /** Offsets from {@code endMs} in whole seconds. */
    private List<Long> attemptOffsets(String roundId, long endMs) {
        return attemptsAt(roundId).stream().map(at -> (at - endMs) / SEC).toList();
    }

    // ------------------------------------------------------------------ the release

    @Test
    void aRoundWhoseExchangeIsSilentIsLetGoTwoMinutesAfterItsEndAndTheSeriesGoesOn() {
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);

        everySecond(btc, w0.endMs(), w0.endMs() + 120 * SEC);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.roundId()).isEqualTo(w0.roundId());
            assertThat(c.reason()).isEqualTo(RELEASE_REASON);
            assertThat(c.atMs()).as("at the second the two minutes are over, not after it").isEqualTo(w0.endMs() + 120 * SEC);
        });
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).as("the series goes on").contains(RoundClock.next(w0));
        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).as("the attempts of the backoff, and one at the release")
                .containsExactly(0L, 2L, 6L, 14L, 30L, 62L, 120L);
    }

    @Test
    void notBeforeTwoMinutesHoweverTheBackoffFalls() {
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);

        tick(btc, w0.endMs());
        tick(btc, w0.endMs() + 62 * SEC); // the backoff would now wait a minute: until 122 s
        tick(btc, w0.endMs() + 119_999L);

        assertThat(cancellations).isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(w0);

        tick(btc, w0.endMs() + 120 * SEC);

        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(RELEASE_REASON);
    }

    @Test
    void aRoundNoOneTriedBeforeIsLetGoAtTheFirstAttemptWhenItsTwoMinutesAreOver() {
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0); // the scheduler remembers nothing of the failures before this attempt, and started long ago

        tick(btc, w0.endMs() + 10 * 60 * SEC);

        assertThat(settlements).as("the settlement is tried first").hasSize(1);
        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(RELEASE_REASON);
            assertThat(c.atMs()).isEqualTo(w0.endMs() + 10 * 60 * SEC);
        });
        verify(gameService).otherVenueLive(btc, w0, w0.endMs() + 10 * 60 * SEC);
    }

    @Test
    void theTwoMinutesCountFromTheEndOfTheRoundNotFromItsStartOrFromTheFirstFailure() {
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);

        tick(btc, w0.endMs() + 100 * SEC); // the first failure is 100 s after the end
        tick(btc, w0.endMs() + 119 * SEC);
        assertThat(cancellations).isEmpty();
        tick(btc, w0.endMs() + 120 * SEC);

        assertThat(cancellations).extracting(Cancellation::atMs).containsExactly(w0.endMs() + 120 * SEC);
    }

    @Test
    void aRestartInTheMiddleGivesTheTwoMinutesAgainEvenWithTheOtherExchangeAlive() {
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);
        long restart = w0.endMs() + 20 * 60 * SEC; // the round ended twenty minutes ago: its two minutes are long over
        scheduler.startedAt(restart);

        tick(btc, restart);

        assertThat(cancellations).as("not at once: its exchange has two minutes to heal the hole that the restart left").isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(w0);

        everySecond(btc, restart + SEC, restart + 120 * SEC - SEC);
        assertThat(cancellations).isEmpty();
        tick(btc, restart + 120 * SEC);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(RELEASE_REASON);
            assertThat(c.atMs()).as("two minutes after the start of the server, at the second they are over").isEqualTo(restart + 120 * SEC);
        });
        assertThat(attemptOffsets(w0.roundId(), restart)).as("the attempts of the backoff, and one at the release")
                .containsExactly(0L, 2L, 6L, 14L, 30L, 62L, 120L);
    }

    @Test
    void aRestartBeforeTheEndOfTheRoundChangesNothingForTheTwoMinutes() {
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);
        scheduler.startedAt(w0.endMs() - 10 * 60 * SEC);

        everySecond(btc, w0.endMs(), w0.endMs() + 120 * SEC);

        assertThat(cancellations).extracting(Cancellation::atMs).as("from the end of the round, as without a restart")
                .containsExactly(w0.endMs() + 120 * SEC);
    }

    // ------------------------------------------------------------------ what the release is not

    @Test
    void aRoundThatHealsBeforeTheTwoMinutesIsSettledAsAlways() {
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);
        everySecond(btc, w0.endMs(), w0.endMs() + 60 * SEC);

        silent.set(false); // the exchange is back and the candles heal the round
        everySecond(btc, w0.endMs() + 61 * SEC, w0.endMs() + 130 * SEC);

        assertThat(cancellations).isEmpty();
        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).as("settled by the attempt that was due").endsWith(62L);
        assertThat(attemptsAt(RoundClock.next(w0).roundId())).as("w1 is still running").isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(RoundClock.next(w0));
        verify(gameService, never()).otherVenueLive(any(), any(), anyLong());
    }

    @Test
    void aRoundThatHealedBeforeTheReleaseButIsFoundOutAtTheAttemptOfTheReleaseIsSettledNotLetGo() {
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);
        everySecond(btc, w0.endMs(), w0.endMs() + 90 * SEC);

        silent.set(false); // back at 91 s: the next attempt is due at 120 s, the very instant of the release
        everySecond(btc, w0.endMs() + 91 * SEC, w0.endMs() + 130 * SEC);

        assertThat(cancellations).as("every attempt tries to settle first").isEmpty();
        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).endsWith(120L);
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(RoundClock.next(w0));
    }

    @Test
    void aRoundThatHealsAtTheVeryInstantItWouldBeLetGoIsSettledNotLetGo() {
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);
        tick(btc, w0.endMs());
        tick(btc, w0.endMs() + 119_999L); // the next attempt is due at 120 s

        silent.set(false);
        tick(btc, w0.endMs() + 120 * SEC);

        assertThat(cancellations).isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(RoundClock.next(w0));
    }

    @Test
    void withTheOtherExchangeSilentTooNothingChangesTheRoundWaitsAndIsRefundedAfterTheHour() {
        otherAlive.set(false);
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);

        everySecond(btc, w0.endMs(), w0.endMs() + HOUR - SEC);

        assertThat(cancellations).isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).as("the series stands still").contains(w0);
        assertThat(attemptOffsets(w0.roundId(), w0.endMs()).subList(0, 9)).as("one attempt at the release instant, then a minute apart")
                .containsExactly(0L, 2L, 6L, 14L, 30L, 62L, 120L, 180L, 240L);

        tick(btc, w0.endMs() + HOUR);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(NO_PRICE_REASON);
            assertThat(c.atMs()).isEqualTo(w0.endMs() + HOUR);
        });
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(RoundClock.next(w0));
    }

    @Test
    void theOtherExchangeComingAliveLaterLetsTheWaitingRoundGoAtTheNextAttempt() {
        otherAlive.set(false);
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);
        everySecond(btc, w0.endMs(), w0.endMs() + 10 * 60 * SEC - SEC);
        assertThat(cancellations).as("ten minutes without either exchange").isEmpty();

        otherAlive.set(true);
        everySecond(btc, w0.endMs() + 10 * 60 * SEC, w0.endMs() + 12 * 60 * SEC);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(RELEASE_REASON);
            assertThat(c.atMs()).as("within a backoff of the other exchange coming alive")
                    .isBetween(w0.endMs() + 10 * 60 * SEC, w0.endMs() + 11 * 60 * SEC);
        });
    }

    @Test
    void aMarketWithoutABackupIsNotTouchedAndTheBackoffIsTheOldOne() {
        RoundWindow w0 = new RoundWindow(plain.id(), Interval.HOURLY, S, S + HOUR);
        seedHourly(plain, w0);
        failure.set(new PriceUnavailableException("no tick of coinbase"));

        everySecond(plain, w0.endMs(), w0.endMs() + 130 * SEC);

        assertThat(cancellations).isEmpty();
        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).as("no attempt at the release instant: nothing to release")
                .containsExactly(0L, 2L, 6L, 14L, 30L, 62L, 122L);
        verify(gameService, never()).otherVenueLive(any(), any(), anyLong());

        tick(plain, w0.endMs() + HOUR);
        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(NO_PRICE_REASON);
    }

    @Test
    void withTheSettingAtZeroTheRuleIsOffAndTheBackoffIsTheOldOne() {
        scheduler.pricesConfig = venueReleaseOnly().withVenueReleaseAfterMs(0);
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);

        everySecond(btc, w0.endMs(), w0.endMs() + 130 * SEC);

        assertThat(cancellations).isEmpty();
        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).containsExactly(0L, 2L, 6L, 14L, 30L, 62L, 122L);
        verify(gameService, never()).otherVenueLive(any(), any(), anyLong());

        everySecond(btc, w0.endMs() + 131 * SEC, w0.endMs() + HOUR - SEC);
        assertThat(cancellations).as("the other exchange is alive all the while").isEmpty();
        tick(btc, w0.endMs() + HOUR);
        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(NO_PRICE_REASON);
    }

    @Test
    void aDifferentWaitIsHonoured() {
        scheduler.pricesConfig = venueReleaseOnly().withVenueReleaseAfterMs(30 * SEC);
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);

        everySecond(btc, w0.endMs(), w0.endMs() + 29 * SEC);
        assertThat(cancellations).isEmpty();
        tick(btc, w0.endMs() + 30 * SEC);

        assertThat(cancellations).extracting(Cancellation::atMs).containsExactly(w0.endMs() + 30 * SEC);
    }

    @Test
    void onlyAFailureToFindAPriceIsLetGoNotAnyOtherFailure() {
        failure.set(new IllegalStateException("HTTP 502 from the price source"));
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);

        everySecond(btc, w0.endMs(), w0.endMs() + 130 * SEC);

        assertThat(cancellations).isEmpty();
        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).as("no cap, no release: the old backoff")
                .containsExactly(0L, 2L, 6L, 14L, 30L, 62L, 122L);
        verify(gameService, never()).otherVenueLive(any(), any(), anyLong());

        tick(btc, w0.endMs() + HOUR);
        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(SETTLEMENT_FAILED_REASON);
    }

    @Test
    void whileTheOracleGateIsClosedNothingIsTriedAndNothingIsLetGo() {
        when(gate.open()).thenReturn(false);
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);

        everySecond(btc, w0.endMs(), w0.endMs() + 130 * SEC);

        assertThat(settlements).isEmpty();
        assertThat(cancellations).isEmpty();
        verify(gameService, never()).otherVenueLive(any(), any(), anyLong());
    }

    // ------------------------------------------------------------------ both series of a market

    @Test
    void bothSeriesOfAMarketAreLetGoEachTwoMinutesAfterItsOwnEnd() {
        long end = S + DAY;
        RoundWindow daily = new RoundWindow(btc.id(), Interval.DAILY, S, end);
        RoundWindow hourly = new RoundWindow(btc.id(), Interval.HOURLY, end - HOUR, end);
        progress.seed(btc.id(), Interval.DAILY, daily);
        progress.seed(btc.id(), Interval.HOURLY, hourly);

        everySecond(btc, end, end + 119 * SEC);
        assertThat(cancellations).isEmpty();
        tick(btc, end + 120 * SEC);

        assertThat(cancellations).extracting(Cancellation::roundId)
                .containsExactlyInAnyOrder(daily.roundId(), hourly.roundId());
        assertThat(cancellations).extracting(Cancellation::reason).containsOnly(RELEASE_REASON);
        assertThat(progress.findNext(btc.id(), Interval.DAILY)).contains(RoundClock.next(daily));
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(RoundClock.next(hourly));
    }

    @Test
    void theTwoSeriesAreLetGoOnTheirOwnClocksWhenTheirRoundsEndAtDifferentInstants() {
        RoundWindow earlier = hourly(S);
        RoundWindow daily = new RoundWindow(btc.id(), Interval.DAILY, S - DAY + 2 * HOUR, S + 2 * HOUR);
        progress.seed(btc.id(), Interval.HOURLY, earlier);
        progress.seed(btc.id(), Interval.DAILY, daily);

        everySecond(btc, earlier.endMs(), earlier.endMs() + 120 * SEC);
        assertThat(cancellations).extracting(Cancellation::roundId).containsExactly(earlier.roundId());

        everySecond(btc, earlier.endMs() + 121 * SEC, daily.endMs() + 120 * SEC);
        assertThat(cancellations).extracting(Cancellation::roundId).contains(daily.roundId());
        assertThat(cancellations.stream().filter(c -> c.roundId().equals(daily.roundId())).findFirst().orElseThrow().atMs())
                .isEqualTo(daily.endMs() + 120 * SEC);
    }

    // ------------------------------------------------------------------ a release that cannot be made

    @Test
    void aReleaseThatFailsIsTriedAgainAtTheNextAttemptAndTheRoundKeepsWaiting() {
        cancelFailsFor.set(RELEASE_REASON);
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);

        everySecond(btc, w0.endMs(), w0.endMs() + 5 * 60 * SEC);

        assertThat(cancellations).isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(w0);

        cancelFailsFor.set(null); // the database answers again
        everySecond(btc, w0.endMs() + 5 * 60 * SEC + SEC, w0.endMs() + 7 * 60 * SEC);

        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(RELEASE_REASON);
    }

    @Test
    void aReleaseThatFailedDoesNotDelayTheRefundOfTheHour() {
        cancelFailsFor.set(RELEASE_REASON);
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);
        tick(btc, w0.endMs());
        tick(btc, w0.endMs() + 3_598 * SEC); // the release fails here and the backoff after it is 4 s: until 3 602 s

        tick(btc, w0.endMs() + HOUR); // the hour is over: its refund is not held back by the backoff of a release

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(NO_PRICE_REASON);
            assertThat(c.atMs()).isEqualTo(w0.endMs() + HOUR);
        });
    }

    @Test
    void aQuestionThatCannotBeAnsweredIsNotAReasonToLetTheRoundGoAndNeverFailsThePass() {
        doThrow(new IllegalStateException("db is down")).when(gameService).otherVenueLive(any(), any(), anyLong());
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);

        everySecond(btc, w0.endMs(), w0.endMs() + 130 * SEC); // no exception escapes

        assertThat(cancellations).isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(w0);

        tick(btc, w0.endMs() + HOUR);
        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(NO_PRICE_REASON);
    }

    @Test
    void anEngineThatIsNotReadyIsNotAFailedAttemptOfTheRound() {
        doThrow(ApiException.engineNotReady()).when(gameService).cancelRound(any(), any(), anyLong());
        RoundWindow w0 = hourly(S);
        seedHourly(btc, w0);

        assertThatThrownBy(() -> tick(btc, w0.endMs() + 10 * 60 * SEC))
                .isInstanceOf(ApiException.class);
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(w0);
    }
}
