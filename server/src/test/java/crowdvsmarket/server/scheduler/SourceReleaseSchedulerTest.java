package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
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
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Letting a round go after the minutes of its source (D-79), on the scheduler alone, with plain objects around it. A round that
 * cannot be priced holds its series — the rounds of a series are settled in order — on every market, not only on one with a backup
 * exchange; so a round whose settlement fails for want of a price ({@link PriceUnavailableException}) is cancelled with a refund as
 * soon as the minutes of the source that prices it (five, fifteen for Polymarket) have passed since the end of the round or the
 * start of the server, whichever is later. D-77 comes first, the hour of D-23 is the upper bound, and a round nobody bet on that
 * cannot be priced does not wait at all. What the cancellation writes is {@code SourceReleaseTest}, over a database; the
 * timing and the conditions are pinned here, and the settings in {@code PricesConfigTest}.
 *
 * <p>Time is the test's: {@code resolveOverdueRounds(market, interval, now)} is called with chosen instants, a tick a second where
 * the second matters. The markets' sources are silent while {@code silent} holds: a settlement of any round throws {@code failure}.
 * Every round carries a bet unless {@code hasBets} says otherwise, and the market's other exchange is silent unless {@code
 * otherAlive} says it is. The server "started" at the beginning of time unless a test says {@code startedAt}.
 */
class SourceReleaseSchedulerTest {

    private static final long SEC = 1_000L;
    private static final long MIN = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;
    /** 2026-10-03T12:00:00Z: a whole hour. */
    private static final long S = 1_791_028_800_000L;
    private static final String SOURCE_REASON = "No price from the source; stakes returned";
    private static final String VENUE_REASON = "The exchange of this round stopped sending prices; stakes returned";
    private static final String NO_PRICE_REASON = "no oracle price available within 1 hour of round close";
    private static final String SETTLEMENT_FAILED_REASON = "settlement failed within 1 hour of round close";

    /** Coinbase with Kraken behind it. */
    private final Market spotBtc = TestMarkets.coinbaseWithKraken("BTC", 0, 1);
    /** Coinbase alone. */
    private final Market spotDoge = TestMarkets.coinbase("DOGE", 0, 3);
    /** A perpetual of the dex xyz. */
    private final Market xyzNvda = TestMarkets.perp("xyz_NVDA", "xyz:NVDA", Category.STOCKS, 10, 2, 5);
    /** A perpetual of Hyperliquid's own dex. */
    private final Market eth = TestMarkets.hl("ETH", "ETH", 0);
    /** A spot pair of Hyperliquid, with nothing behind it here. */
    private final Market hype = TestMarkets.hlSpot("HYPE", "@107", 2, 2);
    /** Kraken's own market (no catalog lists one; a test needs the source). */
    private final Market spotXrp = TestMarkets.kraken("XRP", 0, 4);
    private final Market odds = TestMarkets.odds("PM1", "123");
    private final Market walk = TestMarkets.synthetic("SYN1", MarketKind.PERP, "SYN", 0, null);

    private final GameService gameService = mock(GameService.class);
    private final OracleSeqGate gate = mock(OracleSeqGate.class);
    private final GameConfig config = mock(GameConfig.class);
    private final RoundProgress progress = new RoundProgress();
    private RoundSchedulerService scheduler;

    /** Whether a settlement throws: no source gives a price. */
    private final AtomicBoolean silent = new AtomicBoolean(true);
    private final AtomicReference<RuntimeException> failure =
            new AtomicReference<>(new PriceUnavailableException("no tick fresh at the end of the round"));
    /** Whether any bet waits in a round. */
    private final AtomicBoolean hasBets = new AtomicBoolean(true);
    /** Whether the other exchange of a market with a backup is alive. */
    private final AtomicBoolean otherAlive = new AtomicBoolean(false);
    /** The exchange the rounds of a market were decided on, by market id: none until a test decides. */
    private final Map<String, String> decidedOn = new HashMap<>();

    private record Settlement(String roundId, long atMs) {
    }

    private record Cancellation(String roundId, String reason, long atMs) {
    }

    private final List<Settlement> settlements = new ArrayList<>();
    private final List<Cancellation> cancellations = new ArrayList<>();
    /** The reason {@code cancelRound} fails for. */
    private final AtomicReference<String> cancelFailsFor = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        scheduler = new RoundSchedulerService();
        scheduler.gameService = gameService;
        scheduler.roundProgress = progress;
        scheduler.oracleSeqGate = gate;
        scheduler.config = config;
        scheduler.pricesConfig = PricesConfig.defaults();
        when(config.roundDurations()).thenReturn(new RoundDurations(HOUR, DAY));
        when(gate.open()).thenReturn(true);
        when(gameService.firstWindowToResolve(any(), anyLong())).thenAnswer(inv -> inv.getArgument(0));
        when(gameService.resolveRound(any(), anyLong())).thenAnswer(inv -> {
            RoundWindow window = inv.getArgument(0);
            settlements.add(new Settlement(window.roundId(), inv.getArgument(1)));
            if (silent.get()) {
                throw failure.get();
            }
            return null; // settled
        });
        when(gameService.hasPendingBets(any())).thenAnswer(inv -> hasBets.get());
        when(gameService.instrumentOf(any(), any())).thenAnswer(inv -> {
            Market market = inv.getArgument(0);
            return Instruments.runningOn(market, Optional.ofNullable(decidedOn.get(market.id())));
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
            return new Rounds.CancellationDto(window.roundId(), window.market(), "X", reason, now, 0);
        });
    }

    private static RoundWindow hourly(Market market, long start) {
        return new RoundWindow(market.id(), Interval.HOURLY, start, start + HOUR);
    }

    private void seedHourly(Market market, RoundWindow window) {
        progress.seed(market.id(), Interval.HOURLY, window);
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

    private List<Long> attemptOffsets(String roundId, long endMs) {
        return settlements.stream().filter(s -> s.roundId().equals(roundId)).map(s -> (s.atMs() - endMs) / SEC).toList();
    }

    private Cancellation cancellationOf(String roundId) {
        return cancellations.stream().filter(c -> c.roundId().equals(roundId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------ the release, on a market with one exchange

    @Test
    void aRoundOfAMarketWithoutABackupIsLetGoFiveMinutesAfterItsEndAndTheSeriesGoesOn() {
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        everySecond(xyzNvda, w0.endMs(), w0.endMs() + 5 * MIN);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.roundId()).isEqualTo(w0.roundId());
            assertThat(c.reason()).isEqualTo(SOURCE_REASON);
            assertThat(c.atMs()).as("at the second the five minutes are over, not after it").isEqualTo(w0.endMs() + 5 * MIN);
        });
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).as("the series goes on").contains(RoundClock.next(w0));
        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).as("the attempts of the backoff, and one at the release")
                .containsExactly(0L, 2L, 6L, 14L, 30L, 62L, 122L, 182L, 242L, 300L);
    }

    @Test
    void notBeforeTheFiveMinutesHoweverTheBackoffFalls() {
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        tick(xyzNvda, w0.endMs());
        tick(xyzNvda, w0.endMs() + 62 * SEC);   // the backoff would now wait a minute: until 122 s
        tick(xyzNvda, w0.endMs() + 242 * SEC);  // ... and now until 302 s: past the five minutes
        tick(xyzNvda, w0.endMs() + 5 * MIN - 1);

        assertThat(cancellations).isEmpty();
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(w0);

        tick(xyzNvda, w0.endMs() + 5 * MIN);

        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(SOURCE_REASON);
    }

    @Test
    void aRoundNoOneTriedBeforeIsLetGoAtTheFirstAttemptWhenItsFiveMinutesAreOver() {
        RoundWindow w0 = hourly(eth, S);
        seedHourly(eth, w0);

        tick(eth, w0.endMs() + 20 * MIN);

        assertThat(settlements).as("the settlement is tried first").hasSize(1);
        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(SOURCE_REASON);
            assertThat(c.atMs()).isEqualTo(w0.endMs() + 20 * MIN);
        });
    }

    @Test
    void theMinutesCountFromTheEndOfTheRoundNotFromTheFirstFailure() {
        RoundWindow w0 = hourly(eth, S);
        seedHourly(eth, w0);

        tick(eth, w0.endMs() + 100 * SEC);  // the first failure is 100 s after the end
        tick(eth, w0.endMs() + 299 * SEC);
        assertThat(cancellations).isEmpty();
        tick(eth, w0.endMs() + 300 * SEC);

        assertThat(cancellations).extracting(Cancellation::atMs).containsExactly(w0.endMs() + 5 * MIN);
    }

    @Test
    void aPolymarketRoundWaitsItsOwnQuarterOfAnHour() {
        RoundWindow w0 = hourly(odds, S);
        seedHourly(odds, w0);

        everySecond(odds, w0.endMs(), w0.endMs() + 15 * MIN - SEC);
        assertThat(cancellations).as("not at five minutes, not at fourteen minutes fifty-nine").isEmpty();
        assertThat(progress.findNext(odds.id(), Interval.HOURLY)).contains(w0);
        tick(odds, w0.endMs() + 15 * MIN);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(SOURCE_REASON);
            assertThat(c.atMs()).isEqualTo(w0.endMs() + 15 * MIN);
        });
        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).as("the last attempt is the one at the release").last().isEqualTo(900L);
    }

    @Test
    void everySourceIsLetGoAfterItsOwnMinutes() {
        scheduler.pricesConfig = PricesConfig.defaults()
                .withReleaseAfter(new PricesConfig.ReleaseAfter(1 * MIN, 2 * MIN, 3 * MIN, 4 * MIN, 5 * MIN, 6 * MIN));
        Map<Market, Long> waits = new java.util.LinkedHashMap<>();
        waits.put(eth, 1 * MIN);        // hyperliquid
        waits.put(xyzNvda, 1 * MIN);    // hyperliquid:xyz — both dexes have the one setting
        waits.put(hype, 2 * MIN);       // hyperliquid:spot
        waits.put(spotDoge, 3 * MIN);   // coinbase
        waits.put(spotXrp, 4 * MIN);    // kraken
        waits.put(odds, 5 * MIN);       // polymarket
        waits.put(walk, 6 * MIN);       // synthetic
        RoundWindow w0 = hourly(eth, S);
        for (Market market : waits.keySet()) {
            seedHourly(market, hourly(market, S));
        }

        for (long at = w0.endMs(); at <= w0.endMs() + 6 * MIN; at += SEC) {
            for (Market market : waits.keySet()) {
                tick(market, at);
            }
        }

        assertThat(cancellations).hasSize(waits.size());
        waits.forEach((market, wait) -> assertThat(cancellationOf(hourly(market, S).roundId()).atMs())
                .as(market.id() + " (" + market.source() + " " + market.sourceRef() + ")").isEqualTo(w0.endMs() + wait));
    }

    @Test
    void aRoundThatHealsBeforeTheMinutesAreOverIsSettledAsAlways() {
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);
        everySecond(xyzNvda, w0.endMs(), w0.endMs() + 200 * SEC);

        silent.set(false); // the source is back and the candles heal the round
        everySecond(xyzNvda, w0.endMs() + 201 * SEC, w0.endMs() + 330 * SEC);

        assertThat(cancellations).isEmpty();
        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).as("settled by the attempt that was due").endsWith(242L);
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(RoundClock.next(w0));
    }

    @Test
    void aRoundThatHealsAtTheVeryInstantItWouldBeLetGoIsSettledNotLetGo() {
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);
        everySecond(xyzNvda, w0.endMs(), w0.endMs() + 299 * SEC);

        silent.set(false);
        tick(xyzNvda, w0.endMs() + 5 * MIN);

        assertThat(cancellations).as("every attempt tries to settle first").isEmpty();
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(RoundClock.next(w0));
    }

    // ------------------------------------------------------------------ a restart, and the hour

    @Test
    void aRestartInTheMiddleGivesTheRoundItsFiveMinutesAgain() {
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);
        long restart = w0.endMs() + 20 * MIN;
        scheduler.startedAt(restart); // the end of the round was twenty minutes ago: its five minutes are long over

        tick(xyzNvda, restart);

        assertThat(cancellations).as("not at once: the candles have five minutes to heal the gap the restart left").isEmpty();
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(w0);

        everySecond(xyzNvda, restart + SEC, restart + 5 * MIN - SEC);
        assertThat(cancellations).isEmpty();
        tick(xyzNvda, restart + 5 * MIN);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(SOURCE_REASON);
            assertThat(c.atMs()).as("five minutes after the start").isEqualTo(restart + 5 * MIN);
        });
    }

    @Test
    void aRestartInTheMiddleHealsARoundThatTheCandlesCanHeal() {
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);
        long restart = w0.endMs() + 20 * MIN;
        scheduler.startedAt(restart);
        tick(xyzNvda, restart);

        silent.set(false); // the candles answer at the next attempt
        everySecond(xyzNvda, restart + SEC, restart + 10 * SEC);

        assertThat(cancellations).isEmpty();
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).as("settled, and the series goes on")
                .contains(RoundClock.next(w0));
    }

    @Test
    void aRestartBeforeTheEndOfTheRoundChangesNothing() {
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);
        scheduler.startedAt(w0.endMs() - 10 * MIN);

        everySecond(xyzNvda, w0.endMs(), w0.endMs() + 5 * MIN);

        assertThat(cancellations).extracting(Cancellation::atMs).containsExactly(w0.endMs() + 5 * MIN);
    }

    @Test
    void theHourStaysTheUpperBoundWhateverTheRestartDid() {
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);
        scheduler.startedAt(w0.endMs() + 58 * MIN); // its five minutes would end at 63 minutes

        everySecond(xyzNvda, w0.endMs() + 58 * MIN, w0.endMs() + HOUR - SEC);
        assertThat(cancellations).isEmpty();
        tick(xyzNvda, w0.endMs() + HOUR);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).as("the hour's own words").isEqualTo(NO_PRICE_REASON);
            assertThat(c.atMs()).isEqualTo(w0.endMs() + HOUR);
        });
    }

    @Test
    void aRoundWhoseEndIsMoreThanAnHourBehindIsRefundedAtOnceAsToday() {
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);
        RoundWindow w1 = RoundClock.next(w0);
        RoundWindow w2 = RoundClock.next(w1);
        long restart = w0.endMs() + 3 * HOUR;
        scheduler.startedAt(restart);

        tick(xyzNvda, restart);

        assertThat(cancellations).extracting(Cancellation::roundId).as("the rounds that ended three, two and one hours ago")
                .containsExactly(w0.roundId(), w1.roundId(), w2.roundId());
        assertThat(cancellations).extracting(Cancellation::reason).containsOnly(NO_PRICE_REASON);
        assertThat(cancellations).extracting(Cancellation::atMs).containsOnly(restart);
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).as("the round that has just ended has its five minutes")
                .contains(RoundClock.next(w2));
    }

    @Test
    void aWaitLongerThanTheHourIsCutAtTheHour() {
        scheduler.pricesConfig = PricesConfig.defaults()
                .withReleaseAfter(new PricesConfig.ReleaseAfter(2 * HOUR, 2 * HOUR, 2 * HOUR, 2 * HOUR, 2 * HOUR, 2 * HOUR));
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        tick(xyzNvda, w0.endMs());
        tick(xyzNvda, w0.endMs() + HOUR - 1);
        assertThat(cancellations).isEmpty();
        tick(xyzNvda, w0.endMs() + HOUR);

        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(NO_PRICE_REASON);
    }

    // ------------------------------------------------------------------ a market with a backup exchange: D-77 first

    @Test
    void withTheOtherExchangeAliveTheVenueReleaseComesFirstAtTwoMinutes() {
        otherAlive.set(true);
        decidedOn.put(spotBtc.id(), "coinbase");
        RoundWindow w0 = hourly(spotBtc, S);
        seedHourly(spotBtc, w0);

        everySecond(spotBtc, w0.endMs(), w0.endMs() + 2 * MIN);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).as("the words of D-77, not of D-79").isEqualTo(VENUE_REASON);
            assertThat(c.atMs()).isEqualTo(w0.endMs() + 2 * MIN);
        });
    }

    @Test
    void whenBothTimesAreOverAtTheFirstAttemptAndTheOtherExchangeIsAliveTheVenueReleaseStillComesFirst() {
        otherAlive.set(true);
        decidedOn.put(spotBtc.id(), "coinbase");
        RoundWindow w0 = hourly(spotBtc, S);
        seedHourly(spotBtc, w0);

        tick(spotBtc, w0.endMs() + 20 * MIN); // nobody tried before: its two minutes and its five are long over

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).as("the words that name the exchange: it is the more exact of the two").isEqualTo(VENUE_REASON);
            assertThat(c.atMs()).isEqualTo(w0.endMs() + 20 * MIN);
        });
    }

    @Test
    void withBothExchangesSilentTheMinutesOfTheExchangeOfTheRoundApply() {
        scheduler.pricesConfig = PricesConfig.defaults()
                .withReleaseAfter(new PricesConfig.ReleaseAfter(1 * MIN, 2 * MIN, 3 * MIN, 4 * MIN, 5 * MIN, 6 * MIN));
        Market onCoinbase = TestMarkets.coinbaseWithKraken("ETH", 0, 2);
        Market onKraken = TestMarkets.coinbaseWithKraken("SOL", 0, 5);
        Market undecided = TestMarkets.coinbaseWithKraken("XRP", 0, 6);
        decidedOn.put(onCoinbase.id(), "coinbase");
        decidedOn.put(onKraken.id(), "kraken");
        for (Market market : List.of(onCoinbase, onKraken, undecided)) {
            seedHourly(market, hourly(market, S));
        }

        for (long at = S + HOUR; at <= S + HOUR + 5 * MIN; at += SEC) {
            for (Market market : List.of(onCoinbase, onKraken, undecided)) {
                tick(market, at);
            }
        }

        assertThat(cancellationOf(hourly(onCoinbase, S).roundId()).atMs()).as("Coinbase's three minutes").isEqualTo(S + HOUR + 3 * MIN);
        assertThat(cancellationOf(hourly(onKraken, S).roundId()).atMs()).as("Kraken's four minutes").isEqualTo(S + HOUR + 4 * MIN);
        assertThat(cancellationOf(hourly(undecided, S).roundId()).atMs()).as("not decided: the market's own exchange, Coinbase")
                .isEqualTo(S + HOUR + 3 * MIN);
        assertThat(cancellations).extracting(Cancellation::reason).containsOnly(SOURCE_REASON);
    }

    @Test
    void theOtherExchangeComingAliveBeforeTheMinutesAreOverLetsTheRoundGoByTheRuleOfTheTwoMinutes() {
        decidedOn.put(spotBtc.id(), "coinbase");
        RoundWindow w0 = hourly(spotBtc, S);
        seedHourly(spotBtc, w0);
        everySecond(spotBtc, w0.endMs(), w0.endMs() + 3 * MIN); // both silent: past the two minutes, short of the five
        assertThat(cancellations).isEmpty();

        otherAlive.set(true);
        everySecond(spotBtc, w0.endMs() + 3 * MIN + SEC, w0.endMs() + 4 * MIN);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(VENUE_REASON);
            assertThat(c.atMs()).as("within a backoff of the other exchange coming alive")
                    .isBetween(w0.endMs() + 3 * MIN, w0.endMs() + 4 * MIN);
        });
    }

    @Test
    void theBackoffIsCutAtTheVenueReleaseAndThenAtTheMinutesOfTheSource() {
        decidedOn.put(spotBtc.id(), "coinbase");
        RoundWindow w0 = hourly(spotBtc, S);
        seedHourly(spotBtc, w0);

        everySecond(spotBtc, w0.endMs(), w0.endMs() + 5 * MIN);

        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).as("one attempt at 120 s, one at 300 s")
                .containsExactly(0L, 2L, 6L, 14L, 30L, 62L, 120L, 180L, 240L, 300L);
        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(SOURCE_REASON);
    }

    @Test
    void onceTheVenueReleaseHasPassedTheBackoffIsCutAtTheMinutesOfTheSourceEvenOffItsGrid() {
        scheduler.pricesConfig = PricesConfig.defaults()
                .withReleaseAfter(new PricesConfig.ReleaseAfter(5 * MIN, 5 * MIN, 270 * SEC, 5 * MIN, 15 * MIN, 5 * MIN));
        decidedOn.put(spotBtc.id(), "coinbase");
        RoundWindow w0 = hourly(spotBtc, S);
        seedHourly(spotBtc, w0);

        everySecond(spotBtc, w0.endMs(), w0.endMs() + 270 * SEC);

        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).as("the minute's backoff would have made the last one 300")
                .containsExactly(0L, 2L, 6L, 14L, 30L, 62L, 120L, 180L, 240L, 270L);
        assertThat(cancellations).extracting(Cancellation::atMs).containsExactly(w0.endMs() + 270 * SEC);
    }

    // ------------------------------------------------------------------ what it is not

    @Test
    void aFailureThatIsNotNoPriceKeepsTheHour() {
        failure.set(new IllegalStateException("HTTP 502 from the price source"));
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        everySecond(xyzNvda, w0.endMs(), w0.endMs() + 6 * MIN);

        assertThat(cancellations).isEmpty();
        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).as("no cut at the five minutes: the old backoff")
                .containsExactly(0L, 2L, 6L, 14L, 30L, 62L, 122L, 182L, 242L, 302L);
        verify(gameService, never()).hasPendingBets(any());
        verify(gameService, never()).instrumentOf(any(), any());

        tick(xyzNvda, w0.endMs() + HOUR);

        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(SETTLEMENT_FAILED_REASON);
    }

    @Test
    void aClosedOracleGateIsNotAReleaseEither() {
        when(gate.open()).thenReturn(false);
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        everySecond(xyzNvda, w0.endMs(), w0.endMs() + 6 * MIN);

        assertThat(settlements).isEmpty();
        assertThat(cancellations).isEmpty();

        tick(xyzNvda, w0.endMs() + HOUR);

        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(SETTLEMENT_FAILED_REASON);
    }

    @Test
    void zeroSwitchesTheReleaseOffForThatSourceAloneAndItsRoundsWaitTheHour() {
        scheduler.pricesConfig = PricesConfig.defaults()
                .withReleaseAfter(new PricesConfig.ReleaseAfter(5 * MIN, 5 * MIN, 0, 5 * MIN, 15 * MIN, 5 * MIN));
        RoundWindow doge = hourly(spotDoge, S);
        RoundWindow nvda = hourly(xyzNvda, S);
        seedHourly(spotDoge, doge);
        seedHourly(xyzNvda, nvda);

        for (long at = S + HOUR; at <= S + HOUR + 5 * MIN; at += SEC) {
            tick(spotDoge, at);
            tick(xyzNvda, at);
        }

        assertThat(cancellations).extracting(Cancellation::roundId).as("the other source is still let go").containsExactly(nvda.roundId());
        assertThat(progress.findNext(spotDoge.id(), Interval.HOURLY)).as("Coinbase: switched off").contains(doge);
        assertThat(attemptOffsets(doge.roundId(), doge.endMs())).as("no attempt at the five minutes: nothing to cut for")
                .containsExactly(0L, 2L, 6L, 14L, 30L, 62L, 122L, 182L, 242L);

        tick(spotDoge, doge.endMs() + HOUR);

        assertThat(cancellationOf(doge.roundId()).reason()).isEqualTo(NO_PRICE_REASON);
        assertThat(cancellationOf(doge.roundId()).atMs()).isEqualTo(doge.endMs() + HOUR);
    }

    @Test
    void allZeroIsTheOldBehaviourOfTheHourOnEveryMarket() {
        scheduler.pricesConfig = PricesConfig.defaults().withReleaseAfter(PricesConfig.ReleaseAfter.off());
        RoundWindow w0 = hourly(odds, S);
        seedHourly(odds, w0);

        everySecond(odds, w0.endMs(), w0.endMs() + 20 * MIN);
        assertThat(cancellations).isEmpty();
        tick(odds, w0.endMs() + HOUR);

        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(NO_PRICE_REASON);
    }

    @Test
    void inSyntheticModeTheWalkPricesEveryMarketAndItsMinutesApply() {
        scheduler.pricesConfig = PricesConfig.defaults()
                .withReleaseAfter(new PricesConfig.ReleaseAfter(1 * MIN, 1 * MIN, 1 * MIN, 1 * MIN, 1 * MIN, 7 * MIN))
                .withMode(true, PricesConfig.SourceKind.SYNTHETIC);
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        everySecond(xyzNvda, w0.endMs(), w0.endMs() + 7 * MIN);

        assertThat(cancellations).extracting(Cancellation::atMs).containsExactly(w0.endMs() + 7 * MIN);
    }

    @Test
    void inReplayModeThereIsNoSourceToWaitForAndTheHourIsTheOnlyWait() {
        scheduler.pricesConfig = PricesConfig.defaults().withMode(false, PricesConfig.SourceKind.LIVE);
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        everySecond(xyzNvda, w0.endMs(), w0.endMs() + 20 * MIN);
        assertThat(cancellations).isEmpty();
        tick(xyzNvda, w0.endMs() + HOUR);

        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(NO_PRICE_REASON);
    }

    // ------------------------------------------------------------------ both series, the whole pass

    @Test
    void bothSeriesOfAMarketAreLetGoEachFiveMinutesAfterItsOwnEnd() {
        long end = S + DAY;
        RoundWindow daily = new RoundWindow(xyzNvda.id(), Interval.DAILY, S, end);
        RoundWindow hourly = new RoundWindow(xyzNvda.id(), Interval.HOURLY, end - HOUR, end);
        progress.seed(xyzNvda.id(), Interval.DAILY, daily);
        progress.seed(xyzNvda.id(), Interval.HOURLY, hourly);

        everySecond(xyzNvda, end, end + 5 * MIN - SEC);
        assertThat(cancellations).isEmpty();
        tick(xyzNvda, end + 5 * MIN);

        assertThat(cancellations).extracting(Cancellation::roundId).containsExactlyInAnyOrder(daily.roundId(), hourly.roundId());
        assertThat(cancellations).extracting(Cancellation::reason).containsOnly(SOURCE_REASON);
        assertThat(progress.findNext(xyzNvda.id(), Interval.DAILY)).contains(RoundClock.next(daily));
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(RoundClock.next(hourly));
    }

    @Test
    void theTwoSeriesAreLetGoOnTheirOwnClocksWhenTheirRoundsEndAtDifferentInstants() {
        RoundWindow earlier = hourly(xyzNvda, S);
        RoundWindow daily = new RoundWindow(xyzNvda.id(), Interval.DAILY, S - DAY + 2 * HOUR, S + 2 * HOUR);
        progress.seed(xyzNvda.id(), Interval.HOURLY, earlier);
        progress.seed(xyzNvda.id(), Interval.DAILY, daily);

        everySecond(xyzNvda, earlier.endMs(), earlier.endMs() + 5 * MIN);
        assertThat(cancellations).extracting(Cancellation::roundId).containsExactly(earlier.roundId());

        everySecond(xyzNvda, earlier.endMs() + 5 * MIN + SEC, daily.endMs() + 5 * MIN);
        assertThat(cancellationOf(daily.roundId()).atMs()).isEqualTo(daily.endMs() + 5 * MIN);
    }

    @Test
    void aPassAfterALongPauseLetsGoEveryRoundWhoseTimeIsOverOneAfterTheOther() {
        RoundWindow w0 = hourly(xyzNvda, S);
        RoundWindow w1 = RoundClock.next(w0);
        RoundWindow w2 = RoundClock.next(w1);
        seedHourly(xyzNvda, w0);

        tick(xyzNvda, w1.endMs() + 5 * MIN); // the first attempt after a long pause

        assertThat(cancellationOf(w0.roundId()).reason()).as("an hour and five minutes behind: the hour").isEqualTo(NO_PRICE_REASON);
        assertThat(cancellationOf(w1.roundId()).reason()).as("five minutes behind: the minutes of its source")
                .isEqualTo(SOURCE_REASON);
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).as("the pass goes on to the next window and stops there")
                .contains(w2);
    }

    // ------------------------------------------------------------------ a release that cannot be made

    @Test
    void aReleaseThatFailsIsTriedAgainAtTheNextAttemptAndTheRoundKeepsWaiting() {
        cancelFailsFor.set(SOURCE_REASON);
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        everySecond(xyzNvda, w0.endMs(), w0.endMs() + 8 * MIN);

        assertThat(cancellations).isEmpty();
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(w0);

        cancelFailsFor.set(null); // the database answers again
        everySecond(xyzNvda, w0.endMs() + 8 * MIN + SEC, w0.endMs() + 10 * MIN);

        assertThat(cancellations).extracting(Cancellation::reason).containsExactly(SOURCE_REASON);
    }

    @Test
    void aReleaseThatFailedDoesNotDelayTheRefundOfTheHour() {
        // Hyperliquid's wait ends two seconds before the hour, so that the backoff after a failed release spans the hour
        scheduler.pricesConfig = PricesConfig.defaults()
                .withReleaseAfter(new PricesConfig.ReleaseAfter(HOUR - 2 * SEC, 5 * MIN, 5 * MIN, 5 * MIN, 15 * MIN, 5 * MIN));
        cancelFailsFor.set(SOURCE_REASON);
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);
        tick(xyzNvda, w0.endMs());
        tick(xyzNvda, w0.endMs() + HOUR - 2 * SEC); // the release fails here and the backoff after it is a minute: past the hour

        tick(xyzNvda, w0.endMs() + HOUR);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(NO_PRICE_REASON);
            assertThat(c.atMs()).isEqualTo(w0.endMs() + HOUR);
        });
    }

    @Test
    void anEngineThatIsNotReadyIsNotAFailedAttemptOfTheRound() {
        when(gameService.hasPendingBets(any())).thenThrow(ApiException.engineNotReady());
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        assertThatThrownBy(() -> tick(xyzNvda, w0.endMs())).isInstanceOf(ApiException.class);
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(w0);
    }

    // ------------------------------------------------------------------ a round nobody bet on (D)

    @Test
    void anEmptyRoundThatCannotBePricedIsSkippedAtOnceWithoutAResult() {
        hasBets.set(false);
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        tick(xyzNvda, w0.endMs());

        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).as("the series goes on at once").contains(RoundClock.next(w0));
        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).as("one attempt, no waiting").containsExactly(0L);
        assertThat(cancellations).as("no cancellation, so no event and no row").isEmpty();
        verify(gameService, never()).cancelRound(any(), any(), anyLong());
        verify(gameService, never()).otherVenueLive(any(), any(), anyLong());
    }

    @Test
    void aSkippedRoundIsNotTriedAgainAndTheNextOneIsTriedAsAlways() {
        hasBets.set(false);
        RoundWindow w0 = hourly(xyzNvda, S);
        RoundWindow w1 = RoundClock.next(w0);
        seedHourly(xyzNvda, w0);
        tick(xyzNvda, w0.endMs());

        everySecond(xyzNvda, w0.endMs() + SEC, w0.endMs() + 10 * SEC);
        assertThat(settlements).extracting(Settlement::roundId).containsOnly(w0.roundId());

        tick(xyzNvda, w1.endMs());

        assertThat(attemptOffsets(w1.roundId(), w1.endMs())).containsExactly(0L);
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(RoundClock.next(w1));
    }

    @Test
    void anEmptyRoundThatCanBePricedIsSettledAsBefore() {
        hasBets.set(false);
        silent.set(false);
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        tick(xyzNvda, w0.endMs());

        assertThat(settlements).extracting(Settlement::roundId).containsExactly(w0.roundId());
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(RoundClock.next(w0));
        verify(gameService, never()).hasPendingBets(any()); // nobody asks whether it is empty when the price is there
        verify(gameService, never()).cancelRound(any(), any(), anyLong());
    }

    @Test
    void aRoundWithABetIsNeverSkippedItWaitsItsMinutes() {
        hasBets.set(true);
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        everySecond(xyzNvda, w0.endMs(), w0.endMs() + 5 * MIN - SEC);

        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(w0);
        assertThat(cancellations).isEmpty();
        verify(gameService, atLeastOnce()).hasPendingBets(w0); // asked at every failed attempt: the answer is "a bet"
    }

    @Test
    void onlyANoPriceFailureSkipsAnEmptyRoundNotAnyOther() {
        hasBets.set(false);
        failure.set(new IllegalStateException("HTTP 502 from the price source"));
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        everySecond(xyzNvda, w0.endMs(), w0.endMs() + 60 * SEC);

        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).as("retried, as ever").contains(w0);
        assertThat(attemptOffsets(w0.roundId(), w0.endMs())).as("the backoff of any other failure")
                .containsExactly(0L, 2L, 6L, 14L, 30L);
        verify(gameService, never()).hasPendingBets(any());
    }

    @Test
    void anEmptyRoundIsSkippedOnEveryMarketWhateverItsSourceAndWhateverItsSettingSays() {
        hasBets.set(false);
        scheduler.pricesConfig = PricesConfig.defaults().withReleaseAfter(PricesConfig.ReleaseAfter.off());
        List<Market> markets = List.of(eth, xyzNvda, hype, spotDoge, spotXrp, odds, walk, spotBtc);
        for (Market market : markets) {
            seedHourly(market, hourly(market, S));
        }

        for (Market market : markets) {
            tick(market, S + HOUR);
        }

        for (Market market : markets) {
            assertThat(progress.findNext(market.id(), Interval.HOURLY)).as(market.id())
                    .contains(RoundClock.next(hourly(market, S)));
        }
        assertThat(cancellations).isEmpty();
    }

    @Test
    void anEmptyRoundIsSkippedNotRefundedEvenWhenItsEndIsMoreThanAnHourBehind() {
        hasBets.set(false);
        RoundWindow w0 = hourly(xyzNvda, S);
        seedHourly(xyzNvda, w0);

        tick(xyzNvda, w0.endMs() + 2 * HOUR - 1); // a daily round, say, that nobody tried: its price is still not there

        assertThat(cancellations).as("nothing to give back, so no cancellation for the public log").isEmpty();
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).as("the hour of D-23 is for the rounds that hold stakes: "
                + "both rounds that ended an hour or more ago are stepped over").contains(RoundClock.next(RoundClock.next(w0)));
    }

    @Test
    void bothSeriesOfAMarketSkipTheirEmptyRounds() {
        hasBets.set(false);
        long end = S + DAY;
        RoundWindow daily = new RoundWindow(xyzNvda.id(), Interval.DAILY, S, end);
        RoundWindow hourly = new RoundWindow(xyzNvda.id(), Interval.HOURLY, end - HOUR, end);
        progress.seed(xyzNvda.id(), Interval.DAILY, daily);
        progress.seed(xyzNvda.id(), Interval.HOURLY, hourly);

        tick(xyzNvda, end);

        assertThat(progress.findNext(xyzNvda.id(), Interval.DAILY)).contains(RoundClock.next(daily));
        assertThat(progress.findNext(xyzNvda.id(), Interval.HOURLY)).contains(RoundClock.next(hourly));
        assertThat(cancellations).isEmpty();
    }

    @Test
    void anEmptyRoundIsSkippedWithoutAskingTheOtherExchangeWhateverTheMarket() {
        hasBets.set(false);
        otherAlive.set(true);
        decidedOn.put(spotBtc.id(), "coinbase");
        RoundWindow w0 = hourly(spotBtc, S);
        seedHourly(spotBtc, w0);

        tick(spotBtc, w0.endMs());

        assertThat(progress.findNext(spotBtc.id(), Interval.HOURLY)).contains(RoundClock.next(w0));
        assertThat(cancellations).as("no release of D-77 either: there is nothing to give back").isEmpty();
        verify(gameService, never()).otherVenueLive(any(), any(), anyLong());
    }
}
