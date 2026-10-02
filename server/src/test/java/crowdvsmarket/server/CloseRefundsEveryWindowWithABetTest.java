package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * Closing a market refunds EVERY window of it that holds a bet — as a rebuild does — not only the running window and
 * the one open for betting (review of the core, finding 8; docs/research/markets_architecture.md §4 п.15). In a game
 * that runs forward the bets are in those two, but a stake must not depend on that: a bet left in a window the cursor
 * does not point at is debited, never settled, never refunded, and not even counted as "in play". The review met it
 * in a test whose series cursor had been seeded hours ahead and re-seeded at "now" by a rebuild, leaving the bet far
 * beyond the cursor: {@code closeSeries} cancelled the two windows around the cursor and the stake stayed debited
 * (balance 9 600, in play 0, the bet in the book).
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class CloseRefundsEveryWindowWithABetTest extends LiveGameTestBase {

    private static final long HOURS_2 = 7_200_000L;

    private Rounds.RoundCancelled cancelled(String roundId) {
        Rounds.RoundDto dto = roundResults.findRoundDto(roundId).orElseThrow();
        assertThat(dto).as(roundId).isInstanceOf(Rounds.RoundCancelled.class);
        return (Rounds.RoundCancelled) dto;
    }

    @Test
    void aBetFarBeyondTheCursorIsRefundedWhenTheMarketCloses() {
        Market market = list("T_CR_FAR", MarketKind.PERP, Category.CRYPTO, "CRF", 10, 0);
        prices.setPrice(market, "100.00");
        long now = System.currentTimeMillis();
        // the series was seeded hours ahead (as a test that ran the clock ahead leaves it), a bet taken on the window
        // after that, and a restart at the real "now" brought the cursor back: the bet is ~120 windows ahead of it
        RoundWindow far = calendar.windowContaining(market.id(), Interval.HOURLY, now + HOURS_2);
        roundProgress.seed(market.id(), Interval.HOURLY, far);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String player = signUp("crfar");
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 5, "400.000"), now);
        rebuild.rebuild();
        assertThat(roundProgress.next(market.id(), Interval.HOURLY).startMs()).isLessThan(betting.startMs() - 2 * 60_000L);
        assertThat(gameService.hasPendingBets(betting)).isTrue();

        registry.close(market.id(), "closed", System.currentTimeMillis());

        assertThat(cancelled(betting.roundId()).cancellation().reason()).isEqualTo("market closed: closed");
        assertThat(cancelled(betting.roundId()).cancellation().refundedBetCount()).isEqualTo(1);
        assertThat(gameService.account(player).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(player).inPlay()).isEqualTo("0.000");
        assertThat(gameService.hasPendingBets(betting)).isFalse();
        assertThat(roundProgress.isSeeded(market.id(), Interval.HOURLY)).isFalse();
    }

    @Test
    void everyWindowWithABetOfBothSeriesIsRefundedEarliestFirstAndTheOnesWithoutAreLeftAlone() {
        Market market = list("T_CR_MANY", MarketKind.PERP, Category.CRYPTO, "CRM", 10, 0);
        prices.setPrice(market, "100.00");
        long now = System.currentTimeMillis();
        String player = signUp("crmany");
        // two far windows of the HOURLY series and one of the DAILY series, each with a bet of the same player
        RoundWindow hourlyA = calendar.windowContaining(market.id(), Interval.HOURLY, now + HOURS_2);
        RoundWindow hourlyB = calendar.windowContaining(market.id(), Interval.HOURLY, now + 2 * HOURS_2);
        RoundWindow daily = calendar.windowContaining(market.id(), Interval.DAILY, now + HOURS_2);
        roundProgress.seed(market.id(), Interval.HOURLY, hourlyA);
        gameService.placeBet(player, bet(roundProgress.bettingWindow(market.id(), Interval.HOURLY), Enums.Direction.UP, 3,
                "100.000"), now);
        roundProgress.seed(market.id(), Interval.HOURLY, hourlyB);
        gameService.placeBet(player, bet(roundProgress.bettingWindow(market.id(), Interval.HOURLY), Enums.Direction.DOWN, 3,
                "150.000"), now);
        roundProgress.seed(market.id(), Interval.DAILY, daily);
        gameService.placeBet(player, bet(roundProgress.bettingWindow(market.id(), Interval.DAILY), Enums.Direction.UP, 2,
                "200.000"), now);
        rebuild.rebuild();
        assertThat(gameService.hasPendingBets(RoundWindowOf.next(hourlyA))).isTrue();

        registry.close(market.id(), "operator closed it", System.currentTimeMillis());

        for (RoundWindow window : new RoundWindow[] {RoundWindowOf.next(hourlyA), RoundWindowOf.next(hourlyB),
                RoundWindowOf.next(daily)}) {
            assertThat(cancelled(window.roundId()).cancellation().refundedBetCount()).as(window.roundId()).isEqualTo(1);
        }
        assertThat(gameService.account(player).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(player).inPlay()).isEqualTo("0.000");
        // what the rebuild gives is what the close gave: nothing left to refund, nothing refunded twice
        rebuild.rebuild();
        assertThat(gameService.account(player).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(player).inPlay()).isEqualTo("0.000");
    }

    @Test
    void aWindowWhoseOnlyBetWasCancelledByItsPlayerLeavesTheIndexWhenTheSeriesIsLookedAt() {
        Market market = list("T_CR_EMPTIED", MarketKind.PERP, Category.CRYPTO, "CRE", 10, 0);
        prices.setPrice(market, "100.00");
        long now = System.currentTimeMillis();
        RoundWindow far = calendar.windowContaining(market.id(), Interval.HOURLY, now + HOURS_2);
        roundProgress.seed(market.id(), Interval.HOURLY, far);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String player = signUp("cremptied");
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 3, "100.000"), now);
        gameService.cancelBet(player, betting.roundId(), now);
        // the cursor is back at "now": the window is far from it, so closing the market does not cancel it as one of
        // the two at the cursor — it is only a candidate the index still lists (a cancelled bet leaves its window in)
        roundProgress.seed(market.id(), Interval.HOURLY, calendar.windowContaining(market.id(), Interval.HOURLY, now));
        assertThat(hub.betWindows().of(market.id(), Interval.HOURLY)).extracting(RoundWindow::startMs)
                .as("the index lists candidates").containsExactly(betting.startMs());

        registry.close(market.id(), "closed", System.currentTimeMillis());

        assertThat(hub.betWindows().of(market.id(), Interval.HOURLY)).as("nothing is held in it: dropped").isEmpty();
        assertThat(roundResults.exists(betting.roundId())).as("and it was not cancelled: nobody had a bet there")
                .isFalse();
        assertThat(gameService.account(player).balance()).isEqualTo("10000.000");
    }

    /** {@code RoundClock.next} under a name that reads in the assertions above. */
    private static final class RoundWindowOf {
        static RoundWindow next(RoundWindow window) {
            return crowdvsmarket.engine.round.RoundClock.next(window);
        }
    }
}
