package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundDurations;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.chain.OracleSeqGate;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.prices.Fixtures;
import crowdvsmarket.server.prices.Instruments;
import crowdvsmarket.server.prices.LiveMarketPrices;
import crowdvsmarket.server.prices.PricePoint;
import crowdvsmarket.server.prices.PriceSource;
import crowdvsmarket.server.prices.PricesConfig;
import crowdvsmarket.server.prices.PricingFixtures;
import crowdvsmarket.server.prices.RoundVenues;
import crowdvsmarket.server.prices.TestMarkets;
import crowdvsmarket.server.prices.TickStore;
import crowdvsmarket.server.service.GameService;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Letting a round go (Р-77) with the real price pipeline under the real scheduler — the scenario of the stand on 2026-10-03, in
 * simulated time, a second at a time: a round of a market with a backup exchange is opened on the exchange that has a live price
 * at its start, one of the two exchanges goes silent in the middle of it, and the settlement is asked what the real rings of the
 * real {@code LiveMarketPrices} say. The settlement and the question whether the other exchange is alive go to the real
 * pipeline; only the engine behind them (what a settlement and a cancellation write) is a stand-in that records. The exchanges'
 * sources are scripted: the ticks the test feeds, and a history that answers or fails when the test says.
 *
 * <p>The round is the stand's "hourly" one, three minutes, so the two minutes of the release are inside it, as on the stand. The release
 * after the minutes of the source (Р-79) is off in the scenarios of Р-77 and on in those of both exchanges silent.
 */
class VenueReleaseWithRealPricesTest {

    private static final long SEC = 1_000L;
    /** 2026-10-03T12:00:00Z. */
    private static final long S = 1_791_028_800_000L;
    private static final long STEP = 180_000L;
    private static final long END = S + STEP;
    private static final String RELEASE_REASON = "The exchange of this round stopped sending prices; stakes returned";
    private static final String SOURCE_REASON = "No price from the source; stakes returned";

    private final Market btc = TestMarkets.coinbaseWithKraken("BTC", 0, 1);
    private final AtomicLong clock = new AtomicLong(S);
    private final PricesConfig pricesConfig = PricesConfig.defaults();
    private final TickStore ticks = Fixtures.tickStore(clock::get);
    private final RoundVenues venues = RoundVenues.inMemory(pricesConfig.retentionMs(), clock::get);
    private final Source coinbase = new Source("coinbase");
    private final Source kraken = new Source("kraken");
    private final RoundProgress progress = new RoundProgress();
    private LiveMarketPrices prices;
    private RoundSchedulerService scheduler;

    private record Cancellation(String roundId, String reason, long atMs) {
    }

    private final List<Cancellation> cancellations = new ArrayList<>();
    private final List<String> settled = new ArrayList<>();

    /** A venue's source: its feed's limits, and a history that the test makes answer or fail. */
    private static final class Source implements PriceSource {

        private final String name;
        /** Whether a request for history fails (an exchange that does not answer). */
        volatile boolean historyDown;
        /** The history points it gives while it answers: every ten seconds, at {@code price}. */
        volatile boolean historyAnswers;

        Source(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<Feed> feeds() {
            return List.of(new Feed(name, 15_000, 30_000, m -> name.equals(m.source())));
        }

        @Override
        public Map<String, Quote> poll(Feed feed, List<Market> markets) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PricePoint> history(Market market, long fromMs, long toMs) throws IOException {
            if (historyDown) {
                throw new IOException("the exchange does not answer");
            }
            List<PricePoint> points = new ArrayList<>();
            if (historyAnswers) {
                for (long at = fromMs - fromMs % 10_000L + 10_000L; at < toMs; at += 10_000L) {
                    points.add(new PricePoint(at, BigDecimal.valueOf(100.0)));
                }
            }
            return points;
        }
    }

    @BeforeEach
    void setUp() {
        prices = PricingFixtures.livePrices(ticks, PricingFixtures.sources(List.of(coinbase, kraken)), pricesConfig, venues,
                clock::get);
        GameService gameService = mock(GameService.class);
        GameConfig config = mock(GameConfig.class);
        OracleSeqGate gate = mock(OracleSeqGate.class);
        when(config.roundDurations()).thenReturn(new RoundDurations(STEP, 5 * STEP));
        when(gate.open()).thenReturn(true);
        when(gameService.firstWindowToResolve(any(), anyLong())).thenAnswer(inv -> inv.getArgument(0));
        // the settlement and the question go to the real pipeline; what the engine writes is recorded
        when(gameService.resolveRound(any(), anyLong())).thenAnswer(inv -> {
            RoundWindow window = inv.getArgument(0);
            prices.resolve(btc, window); // PriceUnavailableException when the exchange of the round cannot price it
            settled.add(window.roundId());
            return null;
        });
        when(gameService.otherVenueLive(any(), any(), anyLong()))
                .thenAnswer(inv -> prices.otherVenueLive(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(gameService.hasPendingBets(any())).thenReturn(true); // a round with a bet: one nobody bet on is skipped at once (Р-79)
        // what GameService#instrumentOf does: the exchange the round was decided on, from the real pipeline
        when(gameService.instrumentOf(any(), any())).thenAnswer(inv -> {
            Market market = inv.getArgument(0);
            return Instruments.runningOn(market, prices.decidedVenue(market, inv.getArgument(1)));
        });
        when(gameService.cancelRound(any(), any(), anyLong())).thenAnswer(inv -> {
            RoundWindow window = inv.getArgument(0);
            cancellations.add(new Cancellation(window.roundId(), inv.getArgument(1), inv.getArgument(2)));
            return new Rounds.CancellationDto(window.roundId(), window.market(), "BTC", inv.getArgument(1),
                    inv.getArgument(2), 0);
        });
        scheduler = new RoundSchedulerService();
        scheduler.gameService = gameService;
        scheduler.roundProgress = progress;
        scheduler.roundResults = mock(RoundResultRepository.class);
        scheduler.oracleSeqGate = gate;
        scheduler.config = config;
        scheduler.pricesConfig = pricesConfig.withReleaseAfter(PricesConfig.ReleaseAfter.off()); // Р-79's release has its own scenarios below
        progress.seed(btc.id(), Interval.HOURLY, window(S));
    }

    private RoundWindow window(long start) {
        return new RoundWindow(btc.id(), Interval.HOURLY, start, start + STEP);
    }

    private static BigDecimal price(long now, double basis) {
        // a different price every second, so that a hole is never "a quiet asset that did not move" (a carried price)
        return BigDecimal.valueOf(100.0 + basis).add(BigDecimal.valueOf((now - S) / SEC).movePointLeft(2));
    }

    /**
     * A second of the world at each instant from {@code fromMs} to {@code toMs}: the exchanges that speak then give a tick, the
     * scheduler opens the round that runs and then works its series — what the application does every second.
     */
    private void run(long fromMs, long toMs, LongPredicate coinbaseSpeaks, LongPredicate krakenSpeaks) {
        for (long now = fromMs; now <= toMs; now += SEC) {
            clock.set(now);
            if (coinbaseSpeaks.test(now)) {
                ticks.append("coinbase", now, Map.of(btc.id(), price(now, 0.0)));
            }
            if (krakenSpeaks.test(now)) {
                ticks.append("kraken", now, Map.of(Instruments.shadowId(btc.id(), "kraken"), price(now, 0.3)));
            }
            progress.findNext(btc.id(), Interval.HOURLY).ifPresent(running -> {
                if (clock.get() >= running.startMs()) {
                    prices.openRound(btc, running, clock.get()); // GameService.openRounds, every tick
                }
            });
            scheduler.resolveOverdueRounds(btc, Interval.HOURLY, now);
        }
    }

    private static LongPredicate always() {
        return now -> true;
    }

    private static LongPredicate until(long lastMs) {
        return now -> now <= lastMs;
    }

    private static LongPredicate silentFromTo(long fromMs, long toMs) {
        return now -> now < fromMs || now > toMs;
    }

    // ------------------------------------------------------------------ the scenario of the stand

    @Test
    void theExchangeOfTheRoundGoesSilentInTheMiddleAndTheOtherOneIsAliveTheRoundIsLetGoTwoMinutesAfterItsEnd() {
        RoundWindow w0 = window(S);

        run(S - 10 * SEC, END + 120 * SEC - SEC, until(S + 100 * SEC), always()); // Coinbase stops 100 s into the round

        assertThat(prices.decidedVenue(btc, w0)).as("opened on Coinbase: it had a live price at the start").contains("coinbase");
        assertThat(settled).isEmpty();
        assertThat(cancellations).as("not before two minutes").isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(w0);

        run(END + 120 * SEC, END + 125 * SEC, until(S + 100 * SEC), always());

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.roundId()).isEqualTo(w0.roundId());
            assertThat(c.reason()).isEqualTo(RELEASE_REASON);
            assertThat(c.atMs()).isEqualTo(END + 120 * SEC);
        });
        assertThat(prices.decidedVenue(btc, w0)).as("the round keeps its exchange").contains("coinbase");
        RoundWindow w1 = RoundClock.next(w0);
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).as("the series goes on").contains(w1);
        assertThat(prices.decidedVenue(btc, w1)).as("the next round opens where a live tick is: Coinbase has none at its start")
                .contains("kraken");
    }

    @Test
    void theNextRoundRunsOnTheOtherExchangeAndIsSettledThereAsAlways() {
        RoundWindow w1 = window(END);
        run(S - 10 * SEC, END + 125 * SEC, until(S + 100 * SEC), always());
        assertThat(cancellations).hasSize(1);

        run(END + 126 * SEC, END + STEP + 5 * SEC, until(S + 100 * SEC), always()); // to the end of the next round

        assertThat(settled).containsExactly(w1.roundId());
        assertThat(cancellations).hasSize(1);
        assertThat(prices.resolve(btc, w1).venue()).isEqualTo("kraken");
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(RoundClock.next(w1));
    }

    @Test
    void withTheOtherExchangeSilentTooTheRoundWaitsAndIsLetGoWhenItComesAliveAgain() {
        RoundWindow w0 = window(S);
        LongPredicate krakenBack = now -> now <= S + 100 * SEC || now >= END + 10 * 60 * SEC;

        run(S - 10 * SEC, END + 10 * 60 * SEC - SEC, until(S + 100 * SEC), krakenBack);

        assertThat(cancellations).as("neither exchange has sent a price for ten minutes").isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(w0);

        run(END + 10 * 60 * SEC, END + 11 * 60 * SEC, until(S + 100 * SEC), krakenBack);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(RELEASE_REASON);
            assertThat(c.atMs()).as("within a backoff of the other exchange coming back")
                    .isBetween(END + 10 * 60 * SEC, END + 11 * 60 * SEC);
        });
    }

    @Test
    void aRoundOnTheBackupWhoseBackupWentSilentIsLetGoWhileThePrimaryIsAlive() {
        RoundWindow w0 = window(S);
        // Coinbase is out at the start (its last tick is 20 s old) and back at 100 s; Kraken is the one that stops at 100 s
        LongPredicate coinbaseSpeaks = now -> now <= S - 20 * SEC || now >= S + 100 * SEC;

        run(S - 30 * SEC, END + 125 * SEC, coinbaseSpeaks, until(S + 100 * SEC));

        assertThat(prices.decidedVenue(btc, w0)).as("opened on the backup").contains("kraken");
        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(RELEASE_REASON);
            assertThat(c.atMs()).isEqualTo(END + 120 * SEC);
        });
        assertThat(prices.decidedVenue(btc, w0)).as("and keeps it").contains("kraken");
    }

    @Test
    void anExchangeThatCameBackWithAHoleThatNothingHealsStillLetsTheRoundGoAtTheTwoMinutes() {
        RoundWindow w0 = window(S);

        // silent from 100 s into the round to a minute after its end, and then back — alive when the two minutes are up
        run(S - 10 * SEC, END + 125 * SEC, silentFromTo(S + 100 * SEC, END + 60 * SEC), always());

        assertThat(settled).as("the hole in its prices is still a hole").isEmpty();
        assertThat(cancellations).singleElement().satisfies(c -> assertThat(c.atMs()).isEqualTo(END + 120 * SEC));
        assertThat(prices.decidedVenue(btc, w0)).contains("coinbase");
    }

    // ------------------------------------------------------------------ both exchanges silent: the minutes of the exchange of the round (Р-79)

    @Test
    void withBothExchangesSilentTheRoundIsLetGoAtTheFiveMinutesOfItsExchangeWithTheWordsOfThePriceSource() {
        scheduler.pricesConfig = pricesConfig; // Р-79's release is on, as it is by default
        RoundWindow w0 = window(S);

        run(S - 10 * SEC, END + 5 * 60 * SEC - SEC, until(S + 100 * SEC), until(S + 100 * SEC)); // both stop 100 s into the round

        assertThat(prices.decidedVenue(btc, w0)).contains("coinbase");
        assertThat(cancellations).as("the two minutes of Р-77 do not apply: the other exchange is silent too").isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(w0);

        run(END + 5 * 60 * SEC, END + 5 * 60 * SEC + 5 * SEC, until(S + 100 * SEC), until(S + 100 * SEC));

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.roundId()).isEqualTo(w0.roundId());
            assertThat(c.reason()).isEqualTo(SOURCE_REASON);
            assertThat(c.atMs()).isEqualTo(END + 5 * 60 * SEC);
        });
        assertThat(prices.decidedVenue(btc, w0)).as("the round keeps its exchange").contains("coinbase");
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).as("the series goes on").contains(RoundClock.next(w0));
    }

    @Test
    void aRoundThatRanOnTheBackupIsLetGoAtTheMinutesOfTheBackupNotOfThePrimary() {
        scheduler.pricesConfig = pricesConfig
                .withReleaseAfter(new PricesConfig.ReleaseAfter(5 * 60 * SEC, 5 * 60 * SEC, 3 * 60 * SEC, 4 * 60 * SEC, 5 * 60 * SEC,
                        5 * 60 * SEC)); // Coinbase's three minutes, Kraken's four
        RoundWindow w0 = window(S);
        LongPredicate coinbaseSpeaks = until(S - 20 * SEC); // out at the start and for good: Kraken is the exchange of the round
        LongPredicate krakenSpeaks = until(S + 100 * SEC);

        run(S - 30 * SEC, END + 4 * 60 * SEC - SEC, coinbaseSpeaks, krakenSpeaks);

        assertThat(prices.decidedVenue(btc, w0)).as("opened on the backup").contains("kraken");
        assertThat(cancellations).as("not at Coinbase's three minutes").isEmpty();

        run(END + 4 * 60 * SEC, END + 4 * 60 * SEC + 5 * SEC, coinbaseSpeaks, krakenSpeaks);

        assertThat(cancellations).singleElement().satisfies(c -> {
            assertThat(c.reason()).isEqualTo(SOURCE_REASON);
            assertThat(c.atMs()).as("at Kraken's four").isEqualTo(END + 4 * 60 * SEC);
        });
    }

    @Test
    void aRoundThatTheCandlesHealBeforeTheMinutesOfItsExchangeAreOverIsSettledAsAlways() {
        scheduler.pricesConfig = pricesConfig;
        RoundWindow w0 = window(S);
        coinbase.historyDown = true;

        run(S - 10 * SEC, END + 3 * 60 * SEC, until(S + 100 * SEC), until(S + 100 * SEC));
        assertThat(settled).isEmpty();
        assertThat(cancellations).isEmpty();

        coinbase.historyDown = false; // the candles can be had after a while: the next request after the cooldown fills the hole
        coinbase.historyAnswers = true;
        run(END + 3 * 60 * SEC + SEC, END + 4 * 60 * SEC + 30 * SEC, until(S + 100 * SEC), until(S + 100 * SEC));

        assertThat(settled).containsExactly(w0.roundId());
        assertThat(cancellations).as("healed first").isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(RoundClock.next(w0));
    }

    // ------------------------------------------------------------------ what is not let go

    @Test
    void aRoundWhoseExchangeStayedWithinItsLimitsIsSettledAtItsEndAndNothingIsAsked() {
        RoundWindow w0 = window(S);

        run(S - 10 * SEC, END + 5 * SEC, silentFromTo(S + 60 * SEC, S + 80 * SEC), always()); // a pause of 20 s: no hole

        assertThat(settled).containsExactly(w0.roundId());
        assertThat(cancellations).isEmpty();
    }

    @Test
    void aRoundThatTheExchangeHealsBeforeTheTwoMinutesAreUpIsSettledAsAlways() {
        RoundWindow w0 = window(S);
        coinbase.historyDown = true; // its candles cannot be had at first ...

        run(S - 10 * SEC, END + 40 * SEC, until(S + 100 * SEC), always());
        assertThat(settled).isEmpty();
        assertThat(cancellations).isEmpty();

        coinbase.historyDown = false; // ... and can be a little later: the next request after the cooldown fills the hole
        coinbase.historyAnswers = true;
        run(END + 41 * SEC, END + 115 * SEC, until(S + 100 * SEC), always());

        assertThat(settled).containsExactly(w0.roundId());
        assertThat(cancellations).as("healed first").isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(RoundClock.next(w0));
    }

    @Test
    void withTheSettingAtZeroTheRoundWaitsForItsExchangeHoweverAliveTheOtherOneIs() {
        scheduler.pricesConfig = scheduler.pricesConfig.withVenueReleaseAfterMs(0);

        run(S - 10 * SEC, END + 30 * 60 * SEC, until(S + 100 * SEC), always());

        assertThat(cancellations).isEmpty();
        assertThat(settled).isEmpty();
        assertThat(progress.findNext(btc.id(), Interval.HOURLY)).contains(window(S));
    }
}
