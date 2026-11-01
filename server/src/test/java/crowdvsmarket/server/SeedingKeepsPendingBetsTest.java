package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.prices.PriceUnavailableException;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

/**
 * A stake is never left behind a cursor (docs/research/markets_architecture.md §4 item 2, item 5; review of
 * the multi-market core, finding 1). A series used to be seeded at the window containing "now" when it
 * had no result row yet, without looking for bets: a market listed (its series seeded at W0), a bet on
 * the window open for betting (W1, which a new market cannot resolve before the next row exists), the
 * process down across the end of W1 — and the restart seeded the series PAST W1. The bet was neither
 * resolved nor refunded, ever: the stake stayed debited, was not even counted as "in play" (only the
 * cursor and the window after it are), and every later restart seeded the same way. Now no series is
 * seeded later than the earliest window that still holds a bet and has no result row, and the
 * scheduler resolves — or, without a price, refunds — it: five minutes after the restart, the minutes of
 * the source of the market (D-79).
 *
 * <p>Time is the test's: the scheduler is off, a "restart" is {@code rebuild(now)} with a chosen
 * {@code now} (and {@code startedAt(now)}, for the scheduler's own memory of it), and the windows are
 * one minute long.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class SeedingKeepsPendingBetsTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;
    private static final long STEP = 60_000L;
    /** The wait of the synthetic feed that prices the markets listed here (D-79). */
    private static final long FIVE_MINUTES = 300_000L;

    private Rounds.RoundSettled settled(String roundId) {
        Rounds.RoundDto dto = roundResults.findRoundDto(roundId).orElseThrow();
        assertThat(dto).as(roundId).isInstanceOf(Rounds.RoundSettled.class);
        return (Rounds.RoundSettled) dto;
    }

    @Test
    void aBetOnTheSecondWindowOfAFreshMarketSurvivesARestartAfterItsBettingWindowEnded() {
        Market market = list("T_STRAND", MarketKind.PERP, Category.CRYPTO, "STR", 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow w0 = roundProgress.next(market.id(), HOURLY);          // seeded at the window containing now
        RoundWindow w1 = roundProgress.bettingWindow(market.id(), HOURLY); // open for betting; w0 has no result row
        String p = signUp("strand");
        gameService.placeBet(p, bet(w1, Enums.Direction.UP, 5, "100.000"), w1.startMs() - 1);
        prices.script(market, w1, "100.00", "110.00", "100.00", "110.00");
        assertThat(gameService.account(p).balance()).isEqualTo("9899.850");

        // the process was down across the end of w1 and restarts a moment after it
        long restartAt = w1.endMs() + 5_000L;
        rebuild.rebuild(restartAt);

        assertThat(calendar.windowContaining(market.id(), HOURLY, restartAt))
                .as("the window containing 'now' is past the bet").isEqualTo(new RoundWindow(market.id(), HOURLY,
                        w1.endMs(), w1.endMs() + STEP));
        assertThat(roundProgress.next(market.id(), HOURLY)).as("seeded at the window that holds the bet").isEqualTo(w1);
        assertThat(gameService.hasPendingBets(w1)).isTrue();
        assertThat(gameService.account(p).inPlay()).as("and the stake is shown as in play").isEqualTo("100.000");
        assertThat(w0.startMs()).isLessThan(w1.startMs());

        scheduler.tick(restartAt);

        // resolved as any other round: +10% on a 5x long
        assertThat(roundResults.exists(w1.roundId())).isTrue();
        assertThat(settled(w1.roundId()).positions()).singleElement().satisfies(pos -> {
            assertThat(pos.playerId()).isEqualTo(p);
            assertThat(pos.payout()).isEqualTo("150.000");
        });
        assertThat(gameService.account(p).balance()).isEqualTo("10049.850");
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
        assertThat(gameService.hasPendingBets(w1)).isFalse();
        assertThat(roundProgress.next(market.id(), HOURLY).startMs()).isGreaterThanOrEqualTo(w1.endMs());
    }

    @Test
    void aWindowWithABetIsResolvedEvenWhenItIsFarMoreThanOneStepOverdueAtRestart() {
        Market market = list("T_FAROVERDUE", MarketKind.PERP, Category.CRYPTO, "FOD", 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow w1 = roundProgress.bettingWindow(market.id(), HOURLY);
        String p = signUp("faroverdue");
        gameService.placeBet(p, bet(w1, Enums.Direction.DOWN, 10, "200.000"), w1.startMs() - 1);
        prices.script(market, w1, "100.00", "99.00", "99.00", "100.50");   // -1% on a 10x short: +10% of 200

        long restartAt = w1.endMs() + 10 * STEP;
        rebuild.rebuild(restartAt);
        assertThat(roundProgress.next(market.id(), HOURLY)).isEqualTo(w1);
        scheduler.tick(restartAt);

        // the empty windows after it are skipped, this one — it has a bet — is not
        assertThat(roundResults.exists(w1.roundId())).isTrue();
        assertThat(settled(w1.roundId()).positions()).singleElement()
                .satisfies(pos -> assertThat(pos.payout()).isEqualTo("220.000"));
        assertThat(gameService.account(p).balance()).isEqualTo("10019.700");
        assertThat(roundProgress.next(market.id(), HOURLY))
                .isEqualTo(calendar.windowContaining(market.id(), HOURLY, restartAt));
    }

    @Test
    void withoutAPriceTheStrandedBetIsRefundedFiveMinutesAfterTheRestart() {
        Market market = list("T_STRANDNOPRICE", MarketKind.PERP, Category.CRYPTO, "SNP", 10, 0);
        prices.setPrice(market, "100.00");
        prices.failAllResolves(market, new PriceUnavailableException("source is down"));
        RoundWindow w1 = roundProgress.bettingWindow(market.id(), HOURLY);
        String p = signUp("strandnoprice");
        gameService.placeBet(p, bet(w1, Enums.Direction.UP, 5, "300.000"), w1.startMs() - 1);

        long restartAt = w1.endMs() + 5_000L;
        rebuild.rebuild(restartAt);
        scheduler.startedAt(restartAt); // the server has started just now: the minutes of a round without a price count from it (D-79)
        scheduler.tick(restartAt);

        assertThat(roundResults.exists(w1.roundId())).as("no price yet: retried, not cancelled").isFalse();
        assertThat(gameService.account(p).inPlay()).isEqualTo("300.000");

        scheduler.tick(restartAt + FIVE_MINUTES - 1);
        assertThat(roundResults.exists(w1.roundId())).as("five minutes after the restart are not over a millisecond before").isFalse();
        assertThat(gameService.account(p).inPlay()).isEqualTo("300.000");

        scheduler.tick(restartAt + FIVE_MINUTES);

        Rounds.RoundDto dto = roundResults.findRoundDto(w1.roundId()).orElseThrow();
        assertThat(dto).isInstanceOf(Rounds.RoundCancelled.class);
        assertThat(((Rounds.RoundCancelled) dto).cancellation().reason()).isEqualTo("No price from the source; stakes returned");
        assertThat(((Rounds.RoundCancelled) dto).cancellation().cancelledAtMs()).isEqualTo(restartAt + FIVE_MINUTES);
        assertThat(((Rounds.RoundCancelled) dto).cancellation().refundedBetCount()).isEqualTo(1);
        assertThat(gameService.account(p).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
    }

    @Test
    void aSeriesThatLostItsCursorIsNotSeededBehindAPendingBetByTheSchedulersSafetyNet() {
        Market market = list("T_LOSTCURSOR", MarketKind.PERP, Category.CRYPTO, "LCR", 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow bettingWindow = roundProgress.bettingWindow(market.id(), HOURLY);
        String p = signUp("lostcursor");
        gameService.placeBet(p, bet(bettingWindow, Enums.Direction.UP, 5, "100.000"), bettingWindow.startMs() - 1);
        prices.script(market, bettingWindow, "100.00", "105.00", "100.00", "105.00");

        // the cursor is gone (a listing notification that never came, a state that was cleared): the
        // scheduler gives a running market without a series one — at the pending window, not at "now",
        // which by the time of this tick is past the window the bet is in
        roundProgress.remove(market.id(), HOURLY);
        long later = bettingWindow.endMs() + 5 * STEP;
        scheduler.tick(later);

        assertThat(roundResults.exists(bettingWindow.roundId())).as("resolved, not skipped as an empty window")
                .isTrue();
        assertThat(settled(bettingWindow.roundId()).positions()).singleElement()
                .satisfies(pos -> assertThat(pos.payout()).isEqualTo("125.000"));
        assertThat(gameService.account(p).balance()).isEqualTo("10024.850");
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
        assertThat(roundProgress.next(market.id(), HOURLY))
                .isEqualTo(calendar.windowContaining(market.id(), HOURLY, later));
    }

    @Test
    void everyOpenRoundOfAMarketClosedInTheDatabaseIsRefundedByARebuildHoweverOldItsBetIs() {
        Market market = list("T_OLDCLOSE", MarketKind.PERP, Category.CRYPTO, "OCL", 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String p = signUp("oldclose");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 5, "300.000"), betting.startMs() - 1);
        assertThat(gameService.account(p).inPlay()).isEqualTo("300.000");
        // closed in the database ten steps after that window, and the process stopped before the
        // listener cancelled anything: the windows around the closing instant are not the ones with bets
        long closedAt = betting.endMs() + 10 * STEP;
        db.tx(c -> db.update(c, "UPDATE markets SET status = 'CLOSED', closed_at_ms = ?, close_reason = 'old close' "
                + "WHERE id = ?", closedAt, market.id()));

        rebuild.rebuild(closedAt + STEP);

        Rounds.RoundDto dto = roundResults.findRoundDto(betting.roundId()).orElseThrow();
        assertThat(dto).isInstanceOf(Rounds.RoundCancelled.class);
        assertThat(((Rounds.RoundCancelled) dto).cancellation().reason()).isEqualTo("market closed: old close");
        assertThat(gameService.account(p).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
        assertThat(roundProgress.isSeeded(market.id(), HOURLY)).isFalse();
    }

    @Test
    void aBetOnTheDailySeriesOfAMarketClosedInTheDatabaseIsRefundedByARebuildToo() {
        // review of the core, finding 7c: nothing placed a bet on the DAILY series of a market that is closed in the
        // database before a rebuild, so a refund that looked at the HOURLY series only passed every test
        Market market = list("T_DAILYCLOSE", MarketKind.PERP, Category.CRYPTO, "DCL", 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow hourlyBetting = roundProgress.bettingWindow(market.id(), HOURLY);
        RoundWindow dailyBetting = roundProgress.bettingWindow(market.id(), Interval.DAILY);
        String p = signUp("dailyclose");
        gameService.placeBet(p, bet(hourlyBetting, Enums.Direction.UP, 5, "300.000"), hourlyBetting.startMs() - 1);
        gameService.placeBet(p, bet(dailyBetting, Enums.Direction.DOWN, 4, "200.000"), dailyBetting.startMs() - 1);
        assertThat(gameService.account(p).inPlay()).isEqualTo("500.000");
        long closedAt = dailyBetting.endMs() + 10 * STEP;
        db.tx(c -> db.update(c, "UPDATE markets SET status = 'CLOSED', closed_at_ms = ?, close_reason = 'old close' "
                + "WHERE id = ?", closedAt, market.id()));

        rebuild.rebuild(closedAt + STEP);

        for (RoundWindow window : new RoundWindow[] {hourlyBetting, dailyBetting}) {
            Rounds.RoundDto dto = roundResults.findRoundDto(window.roundId()).orElseThrow();
            assertThat(dto).as(window.roundId()).isInstanceOf(Rounds.RoundCancelled.class);
            assertThat(((Rounds.RoundCancelled) dto).cancellation().reason()).isEqualTo("market closed: old close");
        }
        assertThat(gameService.account(p).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
        assertThat(roundProgress.isSeeded(market.id(), HOURLY)).isFalse();
        assertThat(roundProgress.isSeeded(market.id(), Interval.DAILY)).isFalse();
    }
}
