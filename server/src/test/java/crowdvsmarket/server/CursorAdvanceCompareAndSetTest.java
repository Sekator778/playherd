package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The scheduler moves a series' cursor with a compare-and-set ({@code RoundProgress#advance(market, interval,
 * expected)}), not "one step, whatever it is now" (review of the core, finding 7b): the scheduler resolved {@code
 * expected} while a rebuild may have re-seeded the cursor meanwhile, and advancing a freshly re-seeded cursor skips
 * a window — one that may hold a bet, which is then neither settled nor refunded, ever. Nothing pinned it: replacing
 * the compare-and-set with the plain advance left every test green.
 *
 * <p>The rebuild lands exactly between the settlement of a window and the advance past it: it runs on the
 * scheduler's own thread, right after {@code resolveRound} returned.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class CursorAdvanceCompareAndSetTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;
    private static final long STEP = 60_000L;

    @InjectSpy
    GameService gameServiceSpy;

    @AfterEach
    void unhook() {
        Mockito.reset(gameServiceSpy);
    }

    @Test
    void aRebuildBetweenTheSettlementAndTheAdvanceDoesNotMakeTheSchedulerSkipTheNextWindow() {
        Market market = list("T_CAS", MarketKind.PERP, Category.CRYPTO, "CAS", 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow first = roundProgress.next(market.id(), HOURLY);
        RoundWindow w1 = roundProgress.bettingWindow(market.id(), HOURLY);
        String early = signUp("casearly");
        gameService.placeBet(early, bet(w1, Enums.Direction.UP, 2, "100.000"), w1.startMs() - 1);
        roundProgress.seed(market.id(), HOURLY, w1);             // w1 is the running window now ...
        RoundWindow w2 = roundProgress.bettingWindow(market.id(), HOURLY);
        String late = signUp("caslate");
        gameService.placeBet(late, bet(w2, Enums.Direction.UP, 5, "300.000"), w2.startMs() - 1);   // ... and w2 has a bet
        prices.script(market, w2, "100.00", "110.00", "100.00", "110.00");
        long now = first.endMs() + 8 * STEP;
        AtomicInteger rebuilds = new AtomicInteger();
        doAnswer(inv -> {
            Object report = inv.callRealMethod();
            RoundWindow settled = inv.getArgument(0);
            if (settled.equals(w1) && rebuilds.getAndIncrement() == 0) {
                rebuild.rebuild(now);   // a restart, after w1 was settled and before the scheduler moved its cursor
            }
            return report;
        }).when(gameServiceSpy).resolveRound(any(RoundWindow.class), anyLong());

        scheduler.tick(now);

        assertThat(rebuilds.get()).as("the rebuild did land between the settlement of w1 and the advance").isEqualTo(1);
        assertThat(roundResults.exists(w1.roundId())).isTrue();
        assertThat(roundResults.exists(w2.roundId())).as("w2 holds a bet: it was not skipped").isTrue();
        Rounds.RoundDto dto = roundResults.findRoundDto(w2.roundId()).orElseThrow();
        assertThat(dto).isInstanceOf(Rounds.RoundSettled.class);
        assertThat(gameService.account(late).balance()).as("paid: +10% on a 5x long of 300, less the fees 0.15 + 0.1").isEqualTo("10149.750");
        assertThat(gameService.account(late).inPlay()).isEqualTo("0.000");
        assertThat(gameService.hasPendingBets(w2)).isFalse();
        assertThat(roundProgress.next(market.id(), HOURLY)).as("and the series is at the window running now")
                .isEqualTo(calendar.windowContaining(market.id(), HOURLY, now));
    }
}
