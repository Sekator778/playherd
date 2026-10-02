package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Markets;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.service.MarketViewService;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The read model under load: four readers poll {@code markets}, {@code myPositions} and {@code crowdOverview}
 * as fast as they can while the game goes on underneath — bets placed, rounds settled by the scheduler, a market
 * listed, a market closed (its rounds cancelled with a refund) — and none of them may fail (a bet book read while
 * it changes, a crowd history counted while it grows, a series read while its market closes) or see something
 * that cannot be: a negative stake, a position twice, a series that is neither of the two. At the end, with
 * everything quiet, what is in play is exactly what {@code myPositions} lists.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class MarketViewConcurrencyTest extends LiveGameTestBase {

    private static final int READERS = 4;
    private static final int ROUNDS = 14;

    @Inject
    MarketViewService marketView;

    private static void sane(Markets.MarketsDto lobby) {
        assertThat(lobby.markets()).isNotEmpty();
        Set<String> ids = new HashSet<>();
        for (Markets.MarketDto market : lobby.markets()) {
            assertThat(ids.add(market.id())).as("market %s listed once", market.id()).isTrue();
            assertThat(market.series().size()).isLessThanOrEqualTo(2);
            assertThat(market.crowd().roundsWon()).isLessThanOrEqualTo(market.crowd().roundsTraded());
            for (Markets.MarketSeriesDto series : market.series()) {
                assertThat(series.participantCount()).isGreaterThanOrEqualTo(0);
                assertThat(new BigDecimal(series.upStake()).signum()).isGreaterThanOrEqualTo(0);
                assertThat(new BigDecimal(series.downStake()).signum()).isGreaterThanOrEqualTo(0);
                assertThat(series.bettingClosesAtMs()).isEqualTo(series.runningEndsAtMs());
                if (series.participantCount() == 0) {
                    assertThat(series.majorityDirection()).isNull();
                }
            }
            if (market.status() == Enums.MarketStatus.CLOSED) {
                assertThat(market.closeReason()).isNotNull();
            }
        }
        for (Markets.CategoryDto category : lobby.categories()) {
            assertThat(category.marketCount()).isPositive();
        }
    }

    private static void sane(List<Markets.MyPositionDto> positions) {
        Set<String> seen = new HashSet<>();
        for (Markets.MyPositionDto position : positions) {
            assertThat(new BigDecimal(position.stake()).signum()).isPositive();
            assertThat(position.phase()).isIn(Enums.RoundPhase.BETTING, Enums.RoundPhase.RUNNING);
            assertThat(seen.add(position.marketId() + "/" + position.interval() + "/" + position.phase()))
                    .as("one bet per round of a market: %s", position).isTrue();
        }
    }

    @Test
    void readersNeverFailNorSeeATornStateWhileBetsSettlementsListingsAndClosuresGoOn() throws Exception {
        List<Market> markets = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Market market = list("T_MC_" + i, MarketKind.PERP, Category.CRYPTO, "MC" + i, 25, 1);
            prices.setPrice(market, "2500.000");
            markets.add(market);
        }
        for (Market market : markets) {
            for (Interval interval : Interval.values()) {
                alignTo(market, interval, roundProgress.next("BTC", interval));
            }
        }
        prices.setPrice(registry.require("BTC"), "84000.00");
        List<String> players = List.of(signUp("conc0"), signUp("conc1"), signUp("conc2"));

        AtomicBoolean stop = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong reads = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(READERS);
        List<Future<?>> readers = new ArrayList<>();
        for (int r = 0; r < READERS; r++) {
            int reader = r;
            readers.add(pool.submit(() -> {
                try {
                    long calls = 0;
                    while (!stop.get() && failure.get() == null) {
                        long now = System.currentTimeMillis();
                        if (calls++ % 3 == reader % 3) {
                            marketView.invalidate(); // force assemblies to overlap with the writer
                        }
                        sane(marketView.markets(now));
                        for (String player : players) {
                            sane(marketView.myPositions(player, now));
                        }
                        assertThat(marketView.crowdOverview().markets()).isNotEmpty();
                        reads.incrementAndGet();
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }));
        }

        try {
            // first a hammer: bets on one round placed again and again (each replaces the last: the bet book of
            // the round is changed as often as a thread can persist an event) for two seconds, under the readers
            RoundWindow hammered = roundProgress.bettingWindow(markets.get(0).id(), Interval.HOURLY);
            long until = System.nanoTime() + 2_000_000_000L;
            for (int n = 0; System.nanoTime() < until && failure.get() == null; n++) {
                for (String player : players) {
                    gameService.placeBet(player, bet(hammered, n % 2 == 0 ? Enums.Direction.UP : Enums.Direction.DOWN,
                            2 + n % 3, "100.000"), hammered.startMs() - 1);
                }
            }
            for (int round = 0; round < ROUNDS && failure.get() == null; round++) {
                List<Market> open = markets.stream().filter(m -> registry.byId(m.id()).orElseThrow().isActive()).toList();
                long settleAt = 0;
                for (int p = 0; p < players.size(); p++) {
                    Market market = open.get((round + p) % open.size());
                    RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
                    gameService.placeBet(players.get(p), bet(betting, p % 2 == 0 ? Enums.Direction.UP : Enums.Direction.DOWN,
                            2, "100.000"), betting.startMs() - 1);
                    settleAt = Math.max(settleAt, betting.endMs());
                }
                if (round == 4) {
                    Market late = list("T_MC_LATE", MarketKind.ODDS, Category.POLITICS, "LAT", 10, 4);
                    prices.setPrice(late, "0.5");
                    markets.add(late);
                }
                if (round == 9) {
                    registry.close("T_MC_1", "closed during the test", System.currentTimeMillis());
                }
                scheduler.tick(settleAt); // settles what ended: the crowd's history grows, bets leave the books
            }
        } finally {
            stop.set(true);
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        for (Future<?> reader : readers) {
            reader.get();
        }
        assertThat(failure.get()).as("a reader failed: %s", failure.get()).isNull();
        assertThat(reads.get()).as("the readers really ran").isGreaterThan(READERS);

        // quiet now: what is in play is what myPositions lists, for every player
        marketView.invalidate();
        for (String player : players) {
            BigDecimal listed = marketView.myPositions(player, System.currentTimeMillis()).stream()
                    .map(p -> new BigDecimal(p.stake())).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(new BigDecimal(gameService.account(player).inPlay())).isEqualByComparingTo(listed);
        }
        Markets.MarketsDto lobby = marketView.markets(System.currentTimeMillis());
        assertThat(lobby.markets()).extracting(Markets.MarketDto::id).contains("T_MC_0", "T_MC_1", "T_MC_2", "T_MC_LATE");
        Markets.MarketDto closed = lobby.markets().stream().filter(m -> m.id().equals("T_MC_1")).findFirst().orElseThrow();
        assertThat(closed.status()).isEqualTo(Enums.MarketStatus.CLOSED);
        assertThat(closed.series()).isEmpty();
        assertThat(lobby.markets().stream().filter(m -> m.id().equals("T_MC_0")).findFirst().orElseThrow()
                .crowd().roundsTraded()).as("the crowd of a market that was played traded").isPositive();
    }
}
