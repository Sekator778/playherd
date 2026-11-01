package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Markets;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.service.MarketViewService;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A series is seeded where its last result row says, but never ahead of the clock (review of the core, finding 4;
 * docs/research/markets_architecture.md §4 item 12). The latest {@code round_results} row can be a window that has
 * not started yet — an operator cancels the betting round (a row for the window after the running one) or the
 * running one while a bet waits on the next — and a restart inside the same window then seeded the cursor at
 * "the window after that row": ahead of "now", showing a running round that had not started and taking bets for
 * two round lengths on a round that was not the one open for betting. The seed is the earlier of the window
 * after the last row and the window that contains "now" (a restart after downtime still catches up from the last
 * row), and then the earliest window that holds a bet.
 *
 * <p>Time is the test's: the scheduler is off, a "restart" is {@code rebuild(now)}, windows are one minute long.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class SeriesCursorNeverAheadOfTheClockTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;
    private static final long STEP = 60_000L;

    @Inject
    MarketViewService marketView;

    /** A market that has run for a window: it has a result row (the window it started in, settled empty). */
    private Market market(String id, String symbol) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, symbol, 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow first = roundProgress.next(market.id(), HOURLY);
        scheduler.tick(first.endMs() + 1);
        assertThat(roundResults.exists(first.roundId())).isTrue();
        return market;
    }

    @Test
    void anOperatorCancelOfTheBettingRoundAndARestartInTheSameWindowLeaveTheCursorAtTheRunningWindow() {
        Market market = market("T_SC_BETTING", "SCB");
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        long now = running.startMs() + 10_000L; // inside the running window
        gameService.cancelRound(betting, "operator: bad data", now);

        rebuild.rebuild(now);   // a restart a moment later, inside the same window

        assertThat(roundProgress.next(market.id(), HOURLY)).as("the window that is running now, not the one after the "
                + "cancelled one").isEqualTo(running).isEqualTo(calendar.windowContaining(market.id(), HOURLY, now));
        Rounds.RoundsPairDto pair = gameService.roundsPair(Enums.Interval.HOURLY, market.id(), null);
        assertThat(pair.running().roundId()).isEqualTo(running.roundId());
        assertThat(pair.running()).as("a round that has started").isInstanceOf(Rounds.RoundRunning.class);
        assertThat(pair.betting().roundId()).isEqualTo(betting.roundId());
        assertThat(pair.betting()).as("the cancelled one, as it was").isInstanceOf(Rounds.RoundCancelled.class);
        // betting is not open for a round that is two windows away
        RoundWindow far = RoundClock.next(betting);
        String player = signUp("scb");
        assertThatThrownBy(() -> gameService.placeBet(player, bet(far, Enums.Direction.UP, 3, "100.000"), now))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ROUND_NOT_OPEN_FOR_BETTING));
    }

    @Test
    void anOperatorCancelOfTheRunningRoundWithABetOnTheNextOneAndARestartKeepTheBetCancellable() {
        Market market = market("T_SC_RUNNING", "SCR");
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp("scr");
        long now = running.startMs() + 10_000L;
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 3, "200.000"), now);
        gameService.cancelRound(running, "operator: bad data", now);

        rebuild.rebuild(now);

        assertThat(roundProgress.next(market.id(), HOURLY)).as("the cancelled window is still the one running now")
                .isEqualTo(running);
        List<Markets.MyPositionDto> positions = marketView.myPositions(player, now);
        assertThat(positions).singleElement().satisfies(position -> {
            assertThat(position.phase()).as("the bet is on the round open for betting").isEqualTo(Enums.RoundPhase.BETTING);
            assertThat(position.roundId()).isEqualTo(betting.roundId());
            assertThat(position.entryPrice()).isNull();
        });
        gameService.cancelBet(player, betting.roundId(), now);   // before: refused, 50 s before the round started
        assertThat(gameService.account(player).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(player).inPlay()).isEqualTo("0.000");
    }

    @Test
    void aRestartAfterDowntimeStillCatchesUpFromTheLastRow() {
        Market market = market("T_SC_DOWNTIME", "SCD");
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        long restartAt = running.endMs() + 6 * STEP;   // six windows of downtime

        rebuild.rebuild(restartAt);

        assertThat(roundProgress.next(market.id(), HOURLY)).as("the window after the last row: the scheduler catches up "
                + "from there").isEqualTo(running);
        assertThat(roundProgress.next(market.id(), HOURLY).startMs()).isLessThan(
                calendar.windowContaining(market.id(), HOURLY, restartAt).startMs());
    }

    @Test
    void aSeriesWhoseLastRowIsFarInTheFutureOfTheClockIsSeededAtTheClock() {
        Market market = market("T_SC_FUTURE", "SCF");
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        scheduler.tick(running.endMs() + 30 * STEP);   // a test (or an operator) ran the clock ahead; rows up to there
        long wallClock = running.startMs() + 5_000L;

        rebuild.rebuild(wallClock);

        assertThat(roundProgress.next(market.id(), HOURLY)).isEqualTo(calendar.windowContaining(market.id(), HOURLY,
                wallClock));
    }
}
