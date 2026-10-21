package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Economy;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Ledger;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.dto.Markets;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.service.MarketViewService;
import crowdvsmarket.server.service.ReadViews;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The read views of the running game (architecture plan F8) end to end: a change is in every read once its caller has been
 * answered; a batch that does not commit is in none; the reads take no lock and see one version of the state at a time; a
 * rebuild publishes the views before the engine is ready; a cursor the scheduler moves reaches the views.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class ReadViewsFlowTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;

    @InjectSpy
    EventStore eventStoreSpy;
    @Inject
    MarketViewService marketViews;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private Market market;
    private RoundWindow running;
    private RoundWindow betting;

    @BeforeEach
    void aMarketWithItsBettingRoundOpen() {
        market = list("T_RV" + Long.toString(System.nanoTime(), 36).toUpperCase(), MarketKind.PERP, Category.CRYPTO, "RVW", 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        running = roundProgress.next(market.id(), HOURLY);
        betting = roundProgress.bettingWindow(market.id(), HOURLY);
    }

    @AfterEach
    void heal() {
        Mockito.reset(eventStoreSpy);
        pool.shutdownNow();
        rebuild.rebuild(); // whatever a test left half-way: the next one starts on a ready engine
    }

    private long lastEventId() {
        return db.read(c -> db.queryOne(c, "SELECT COALESCE(MAX(id), 0) AS n FROM game_events", rs -> rs.getLong("n")));
    }

    private Rounds.RoundBetting bettingRound(String viewer) {
        return (Rounds.RoundBetting) gameService.round(betting.roundId(), viewer);
    }

    private Markets.MarketSeriesDto lobbySeries() {
        marketViews.invalidate();
        Markets.MarketDto dto = marketViews.markets(System.currentTimeMillis()).markets().stream()
                .filter(m -> m.id().equals(market.id())).findFirst().orElseThrow();
        return dto.series().stream().filter(s -> s.interval() == Enums.Interval.HOURLY).findFirst().orElseThrow();
    }

    @Test
    void aBetIsInEveryReadOnceItsCallerHasBeenAnswered() {
        String player = signUp("fresh");
        long before = gameService.snapshot().version();
        String balance = gameService.account(player).balance();
        assertThat(gameService.account(player).inPlay()).isEqualTo("0.000");

        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 5, "300.000"), betting.startMs() - 1);

        ReadViews.Snapshot snapshot = gameService.snapshot();
        assertThat(snapshot.version()).as("a new version").isGreaterThan(before);
        assertThat(snapshot.version()).as("the id of the last event of the journal").isEqualTo(lastEventId());
        assertThat(gameService.account(player).inPlay()).isEqualTo("300.000");
        assertThat(gameService.account(player).balance()).isNotEqualTo(balance);
        assertThat(gameService.balanceOf(player)).contains(hub.ledger().balance(player));
        assertThat(gameService.openBetRoundIds(player)).containsExactly(betting.roundId());
        assertThat(marketViews.myPositions(player, System.currentTimeMillis())).singleElement()
                .satisfies(p -> assertThat(p.stake()).isEqualTo("300.000"));
        assertThat(gameService.betFeed(betting.roundId(), null).entries()).hasSize(1);
        Rounds.RoundBetting round = bettingRound(player);
        assertThat(round.participantCount()).isEqualTo(1);
        assertThat(round.myBet()).isNotNull();
        assertThat(round.recentBets()).hasSize(1);
        assertThat(lobbySeries().participantCount()).isEqualTo(1);
        assertThat(gameService.hasPendingBets(betting)).isTrue();
    }

    @Test
    void aSettlementIsInEveryReadOnceItsCallerHasBeenAnsweredAndTheCursorFollows() {
        String player = signUp("settle");
        gameService.placeBet(player, bet(betting, Enums.Direction.DOWN, 10, "300.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs()); // the empty running window is settled; the bet's round starts to run
        prices.script(market, betting, "2500.000", "2400.000", "2400.000", "2510.000");

        scheduler.tick(betting.endMs());

        ReadViews.Snapshot snapshot = gameService.snapshot();
        assertThat(snapshot.version()).isEqualTo(lastEventId());
        assertThat(snapshot.cursor(market.id(), HOURLY)).as("the cursor of the live progress")
                .isEqualTo(roundProgress.findNext(market.id(), HOURLY));
        assertThat(snapshot.betCount(betting.roundId())).isZero();
        assertThat(snapshot.isClosed(betting)).isTrue();
        assertThat(gameService.account(player).inPlay()).isEqualTo("0.000");
        assertThat(gameService.balanceOf(player)).contains(hub.ledger().balance(player));
        assertThat(snapshot.crowd(market.id()).orElseThrow().capital()).isEqualByComparingTo(hub.crowdAccount(market.id()).capital());
        assertThat(snapshot.crowd(market.id()).orElseThrow().history()).hasSize(hub.crowdAccount(market.id()).history().size());
        assertThat(marketViews.myPositions(player, System.currentTimeMillis())).isEmpty();
        assertThat(gameService.crowdAccount(market.id()).history()).hasSameSizeAs(hub.crowdAccount(market.id()).history());
    }

    @Test
    void aCursorTheSchedulerMovesReachesTheViewsAtOnce() {
        RoundWindow expected = roundProgress.next(market.id(), HOURLY);
        assertThat(gameService.snapshot().cursor(market.id(), HOURLY)).contains(expected);

        assertThat(roundProgress.advance(market.id(), HOURLY, expected)).isTrue();

        assertThat(gameService.snapshot().cursor(market.id(), HOURLY))
                .contains(crowdvsmarket.engine.round.RoundClock.next(expected));
    }

    @Test
    void aBatchThatDoesNotCommitIsInNoReadNotWhileItIsUnderWayNorAfterTheEngineIsRebuilt() throws Exception {
        String player = signUp("lost");
        String balance = gameService.account(player).balance();
        long version = gameService.snapshot().version();
        long now = System.currentTimeMillis();
        Economy.ReconciliationDto books = gameService.reconciliation(now);
        Ledger.SeasonRecordDto seasonRecord = gameService.seasonRecord(player, null);
        CountDownLatch inAppend = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean armed = new AtomicBoolean(true);
        doAnswer(inv -> {
            if (armed.get()) {
                inAppend.countDown();
                release.await();
                throw new IllegalStateException("injected commit failure");
            }
            return inv.callRealMethod();
        }).when(eventStoreSpy).appendAll(any());

        Future<Rounds.BetDto> placing = pool.submit(() -> gameService.placeBet(player,
                bet(betting, Enums.Direction.UP, 5, "300.000"), betting.startMs() - 1));
        assertThat(inAppend.await(10, TimeUnit.SECONDS)).isTrue();

        // the engine holds the bet while the batch is committing: that is what the reads must not show
        assertThat(hub.engine(market.id(), HOURLY).betBook().betsFor(betting.roundId())).as("applied, not committed").hasSize(1);
        assertThat(placing.isDone()).isFalse();
        assertNothingOfTheBetIsRead(player, balance, version);
        assertThat(hub.ledger().openStakes()).as("the engine holds the stake").isNotEqualTo(PlayAmount.ZERO);
        assertThat(gameService.reconciliation(now)).as("the books of the last commit, not of the batch under way").isEqualTo(books);
        assertThat(gameService.seasonRecord(player, null)).isEqualTo(seasonRecord);
        assertThat(gameService.publicProfile(player).seasonRecord()).isEqualTo(seasonRecord);
        assertThat(gameService.home(player).topRich()).isEmpty();

        release.countDown();
        assertThatThrownBy(() -> placing.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e.getCause()).code()).isEqualTo(Enums.ApiErrorCode.ENGINE_NOT_READY));
        armed.set(false);

        assertThat(hub.engine(market.id(), HOURLY).betBook().betsFor(betting.roundId())).as("rebuilt from the journal").isEmpty();
        assertNothingOfTheBetIsRead(player, balance, gameService.snapshot().version());
        assertThat(gameService.reconciliation(now)).isEqualTo(books);
        assertThat(gameService.snapshot().version()).as("the rebuild published the journal's version").isEqualTo(lastEventId());

        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 5, "300.000"), betting.startMs() - 1);
        assertThat(gameService.account(player).inPlay()).as("the next bet works and is read").isEqualTo("300.000");
    }

    private void assertNothingOfTheBetIsRead(String player, String balance, long version) {
        assertThat(gameService.snapshot().version()).isEqualTo(version);
        assertThat(gameService.account(player).balance()).isEqualTo(balance);
        assertThat(gameService.account(player).inPlay()).isEqualTo("0.000");
        assertThat(gameService.openBetRoundIds(player)).isEmpty();
        assertThat(marketViews.myPositions(player, System.currentTimeMillis())).isEmpty();
        assertThat(gameService.betFeed(betting.roundId(), null).entries()).isEmpty();
        Rounds.RoundBetting round = bettingRound(player);
        assertThat(round.participantCount()).isZero();
        assertThat(round.myBet()).isNull();
        assertThat(round.recentBets()).isEmpty();
        assertThat(lobbySeries().participantCount()).isZero();
        assertThat(gameService.hasPendingBets(betting)).isFalse();
    }

    @Test
    void readsTakeNoLockAndAnswerWhileTheEngineLockAndTheWritersGateAreHeld() throws Exception {
        String player = signUp("nolock");
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 5, "300.000"), betting.startMs() - 1);
        AtomicLong answered = new AtomicLong();

        gameService.runExclusively(() -> { // the engine's write lock and the writer's gate, held by this thread
            Future<?> reading = pool.submit(() -> {
                for (int i = 0; i < 50; i++) {
                    assertThat(gameService.account(player).inPlay()).isEqualTo("300.000");
                    assertThat(marketViews.myPositions(player, System.currentTimeMillis())).hasSize(1);
                    assertThat(gameService.betFeed(betting.roundId(), null).entries()).hasSize(1);
                    assertThat(bettingRound(player).participantCount()).isEqualTo(1);
                    assertThat(gameService.crowdAccount(market.id()).capital()).isNotNull();
                    marketViews.crowdOverview();
                    assertThat(gameService.seasonRecord(player, null).season()).isNotNull();
                    gameService.publicProfile(player);
                    gameService.ratings(null);
                    gameService.botRatings(null);
                    gameService.weeklyRatings(System.currentTimeMillis());
                    gameService.botWeeklyRatings(System.currentTimeMillis());
                    gameService.ledgerCorrections(new Paging.PageParams(20, null));
                    gameService.economyActive();
                    gameService.circuitBreakerOpen(System.currentTimeMillis());
                    gameService.reconciliation(System.currentTimeMillis());
                    gameService.supply(System.currentTimeMillis());
                    assertThat(gameService.isClosed(betting)).isFalse();
                    gameService.home(player);
                    answered.incrementAndGet();
                }
            });
            try {
                reading.get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new AssertionError("a read waited for a lock", e);
            }
        });

        assertThat(answered.get()).isEqualTo(50);
    }

    @Test
    void readersAndWritersAtOnceEveryReadIsOneVersionOfTheStateAndVersionsOnlyGrow() throws Exception {
        int players = 12;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            ids.add(signUp("conc" + i));
        }
        PlayAmount starter = hub.ledger().balance(ids.get(0));
        AtomicBoolean stop = new AtomicBoolean();
        List<String> violations = Collections.synchronizedList(new ArrayList<>());
        AtomicLong reads = new AtomicLong();
        AtomicLong bets = new AtomicLong();

        List<Future<?>> writers = new ArrayList<>();
        for (String id : ids) {
            writers.add(pool.submit(() -> {
                int k = 0;
                while (!stop.get()) {
                    String stake = (k++ % 2 == 0) ? "100.000" : "101.000"; // a replacement is a refund and a new bet
                    gameService.placeBet(id, bet(betting, Enums.Direction.UP, 5, stake), betting.startMs() - 1);
                    bets.incrementAndGet();
                }
                return null;
            }));
        }
        List<Future<?>> readers = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            readers.add(pool.submit(() -> {
                long last = 0;
                while (!stop.get()) {
                    ReadViews.Snapshot s = gameService.snapshot();
                    if (s.version() < last) {
                        violations.add("the version went back from " + last + " to " + s.version());
                    }
                    last = s.version();
                    int seen = 0;
                    for (String id : ids) {
                        Optional<Bet> bet = s.find(betting.roundId(), id);
                        PlayAmount balance = s.player(id).orElseThrow().balance();
                        PlayAmount inBet = PlayAmount.ZERO;
                        if (bet.isPresent()) {
                            seen++;
                            Fees fees = bet.get().fees() == null ? Fees.ZERO : bet.get().fees();
                            inBet = bet.get().stake().plus(fees.total());
                        }
                        if (!balance.plus(inBet).equals(starter)) {
                            violations.add("v" + s.version() + " " + id + ": balance " + balance + " + in the bet " + inBet
                                    + " is not the starter " + starter);
                        }
                    }
                    if (seen != s.betCount(betting.roundId())) {
                        violations.add("v" + s.version() + ": " + seen + " bets by player, " + s.betCount(betting.roundId())
                                + " in the round");
                    }
                    reads.incrementAndGet();
                }
                return null;
            }));
        }

        Thread.sleep(3_000);
        stop.set(true);
        for (Future<?> f : writers) {
            f.get(30, TimeUnit.SECONDS);
        }
        for (Future<?> f : readers) {
            f.get(30, TimeUnit.SECONDS);
        }

        assertThat(violations).as("a read that saw a balance without its bet, or a bet without its balance").isEmpty();
        assertThat(bets.get()).isGreaterThan(players);
        assertThat(reads.get()).isGreaterThan(100);
        assertThat(gameService.snapshot().version()).isEqualTo(lastEventId());
        for (String id : ids) {
            assertThat(gameService.balanceOf(id)).contains(hub.ledger().balance(id));
        }
    }

    @Test
    void aRebuildPublishesTheJournalsStateInFullAndTheViewsEqualTheEngine() {
        String a = signUp("rebuilda");
        String b = signUp("rebuildb");
        gameService.placeBet(a, bet(betting, Enums.Direction.UP, 5, "300.000"), betting.startMs() - 1);
        gameService.placeBet(b, bet(betting, Enums.Direction.DOWN, 3, "200.000"), betting.startMs() - 1);
        ReadViews.Snapshot before = gameService.snapshot();

        rebuild.rebuild();

        ReadViews.Snapshot after = gameService.snapshot();
        assertThat(after).as("a fresh set").isNotSameAs(before);
        assertThat(after.version()).isEqualTo(lastEventId());
        assertThat(after.betsFor(betting.roundId())).isEqualTo(
                List.copyOf(hub.engine(market.id(), HOURLY).betBook().betsFor(betting.roundId())));
        assertThat(after.player(a).orElseThrow().balance()).isEqualTo(hub.ledger().balance(a));
        assertThat(after.player(b).orElseThrow().balance()).isEqualTo(hub.ledger().balance(b));
        assertThat(after.feed().latest(betting.roundId(), 10)).hasSize(2);
        assertThat(after.cursor(market.id(), HOURLY)).isEqualTo(roundProgress.findNext(market.id(), HOURLY));
        assertThat(gameService.account(a).inPlay()).isEqualTo("300.000");
    }

    @Test
    void theMovedReadsAnswerWhatTheLedgerComputesOnTheSameState() {
        String a = signUp("cmpa");
        String b = signUp("cmpb");
        gameService.placeBet(a, bet(betting, Enums.Direction.UP, 5, "300.000"), betting.startMs() - 1);
        gameService.placeBet(b, bet(betting, Enums.Direction.DOWN, 3, "200.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());
        prices.script(market, betting, "2500.000", "2400.000", "2400.000", "2510.000");
        scheduler.tick(betting.endMs());
        LedgerBook ledger = hub.ledger();
        long now = System.currentTimeMillis();

        for (String id : List.of(a, b)) {
            var expected = ledger.seasonRecord(id, crowdvsmarket.engine.season.Season.of(java.time.Instant.ofEpochMilli(now)));
            Ledger.SeasonRecordDto dto = gameService.seasonRecord(id, null);
            assertThat(expected).isPresent();
            assertThat(dto.baselineBalance()).isEqualTo(expected.get().baselineBalance().toMoney().toPlainString());
            assertThat(dto.deposited()).isEqualTo(expected.get().deposited().toMoney().toPlainString());
            assertThat(dto.withdrawn()).isEqualTo(expected.get().withdrawn().toMoney().toPlainString());
            assertThat(dto.roundsPlayed()).isEqualTo(expected.get().roundsPlayed());
            assertThat(dto.directionEligibleRounds()).isEqualTo(expected.get().directionEligibleRounds());
            assertThat(dto.directionCorrectRounds()).isEqualTo(expected.get().directionCorrectRounds());
            assertThat(gameService.publicProfile(id).seasonRecord()).isEqualTo(dto);
        }
        assertThat(gameService.seasonRecord("nobody", null).roundsPlayed()).isZero();

        Economy.ReconciliationDto books = gameService.reconciliation(now);
        assertThat(books.economyActive()).isEqualTo(ledger.economyActive());
        assertThat(gameService.economyActive()).isEqualTo(ledger.economyActive());
        assertThat(books.players()).isEqualTo(ledger.playersTotal().toMoney().toPlainString());
        assertThat(books.openStakes()).isEqualTo(ledger.openStakes().toMoney().toPlainString());
        assertThat(books.treasury()).isEqualTo(ledger.treasury().toMoney().toPlainString());
        assertThat(books.herd()).isEqualTo(ledger.herd().toMoney().toPlainString());
        assertThat(books.withdrawalsPending()).isEqualTo(ledger.withdrawalsPending().toMoney().toPlainString());
        assertThat(books.circuitBreakerOpen()).isEqualTo(ledger.circuitBreakerOpen(java.time.Instant.ofEpochMilli(now)));
        assertThat(gameService.circuitBreakerOpen(now)).isEqualTo(books.circuitBreakerOpen());
        assertThat(gameService.ledgerCorrections(new Paging.PageParams(20, null)).items())
                .hasSameSizeAs(ledger.corrections());

        var season = crowdvsmarket.engine.season.Season.of(java.time.Instant.ofEpochMilli(now));
        var book = new crowdvsmarket.engine.rating.RatingBook(ledger);
        var people = gameService.ratings(null);
        var bots = gameService.botRatings(null);
        assertThat(people.season()).isEqualTo(season.id());
        assertThat(people.rich().size() + bots.rich().size()).isEqualTo(book.richList(season, java.util.Map.of()).size());
        assertThat(people.prophets().size() + bots.prophets().size()).isEqualTo(book.prophetList(season, java.util.Map.of()).size());
        assertThat(gameService.home(a).topRich()).hasSizeLessThanOrEqualTo(5);
    }
}
