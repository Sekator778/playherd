package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
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
 * D-40 in the core (docs/research/markets_architecture.md §4 item 14; {@code BoundaryPrices}): the close of a round is
 * the open of the next one, and both series of a market share their boundaries — whatever the price pipeline still
 * remembers. After a restart a later merge of history can give a boundary a price other than the one a round already
 * closed on, settled, and anchored on chain; the settled rows of {@code round_results} are the durable fact, so a
 * round of market M with window {@code [S, E)} opens at the close of the SETTLED row of M, of either series, that
 * ends at {@code S}. The pipeline's open is ignored and the first candle of the path opens there; the same open is
 * shown in every DTO of the running round — also where the pipeline knows nothing. A previous round that was
 * cancelled has no close: nothing overrides.
 *
 * <p>Time is the test's: the scheduler is off, windows are one minute (HOURLY) and two minutes (DAILY) long, and a
 * "restart" is {@code rebuild(now)}. Tests of one series park the other far in the future (and again after a
 * restart, which seeds a cursor at "now"): a window of the other series that ends at the same boundary would be
 * settled first, at the price the test's pipeline reports by default.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class BoundaryContinuityTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;
    private static final Interval DAILY = Interval.DAILY;
    private static final long STEP = 60_000L;

    @Inject
    MarketViewService marketView;

    private Market market(String id, String symbol) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, symbol, 10, 0);
        prices.setPrice(market, "100.00");
        parkTheDailySeries(market);
        return market;
    }

    /** The DAILY series is made to wait ten days: its windows must not end at a boundary the test looks at. */
    private void parkTheDailySeries(Market market) {
        roundProgress.seed(market.id(), DAILY,
                calendar.windowContaining(market.id(), DAILY, System.currentTimeMillis() + 10 * 86_400_000L));
    }

    private Rounds.RoundSettled settled(String roundId) {
        Rounds.RoundDto dto = roundResults.findRoundDto(roundId).orElseThrow();
        assertThat(dto).as(roundId).isInstanceOf(Rounds.RoundSettled.class);
        return (Rounds.RoundSettled) dto;
    }

    private long openCents(String roundId) {
        return db.read(c -> db.queryOne(c, "SELECT open_price_cents FROM round_results WHERE round_id = ?",
                rs -> rs.getLong("open_price_cents"), roundId));
    }

    private String eventOpenPrice(String roundId, Market market, RoundWindow window) {
        return db.read(c -> db.queryOne(c, "SELECT payload->>'openPrice' AS p FROM game_events WHERE "
                + "event_type = 'RoundResolved' AND payload->>'market' = ? AND payload->>'interval' = ? "
                + "AND (payload->>'windowStart')::bigint = ?", rs -> rs.getString("p"), market.id(),
                window.interval().name(), window.startMs()));
    }

    @Test
    void aRestartBetweenTwoSettlementsAndAPipelineThatNowReportsAnotherPriceAtTheBoundaryChangeNothing() {
        Market market = market("T_BC_RESTART", "BCR");
        RoundWindow w0 = roundProgress.next(market.id(), HOURLY);
        RoundWindow w1 = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp("bcrestart");
        gameService.placeBet(player, bet(w1, Enums.Direction.DOWN, 5, "200.000"), w1.startMs() - 1);
        prices.script(market, w0, "100.00", "105.00", "100.00", "105.00");   // w0 closes at 105.00
        scheduler.tick(w0.endMs());
        assertThat(settled(w0.roundId()).closePrice()).isEqualTo("105.00");

        rebuild.rebuild(w0.endMs());   // a restart: whatever the pipeline remembered is gone; the database remains
        parkTheDailySeries(market);
        // the pipeline now has history merged at the boundary and says the price there was 106.00
        prices.script(market, w1, "106.00", "104.00", "104.00", "106.50");
        scheduler.tick(w1.endMs());

        Rounds.RoundSettled round = settled(w1.roundId());
        assertThat(round.openPrice()).as("the close of the round before it, not the pipeline's new 106.00")
                .isEqualTo("105.00");
        assertThat(openCents(w1.roundId())).isEqualTo(10_500L);
        assertThat(eventOpenPrice(w1.roundId(), market, w1)).as("and so is the event the engine replays")
                .isEqualTo("105.00");
        assertThat(round.positions()).singleElement().satisfies(position -> {
            assertThat(position.entryPrice()).isEqualTo("105.00");
            assertThat(position.liquidated()).isFalse();
            // 5x short from 105.00 to 104.00: size 9.52380952, +9.52 on a 200.00 margin
            assertThat(position.pnl()).isEqualTo("9.520");
        });
        // the first candle opens where the round opened, widened to take the open in
        assertThat(roundResults.canonicalJson(w1.roundId()).orElseThrow()).contains("10500");
    }

    @Test
    void theRoundIsTheSameWithoutARestartWhenThePipelineMovedTheBoundaryPrice() {
        Market market = market("T_BC_NORESTART", "BCN");
        RoundWindow w0 = roundProgress.next(market.id(), HOURLY);
        RoundWindow w1 = roundProgress.bettingWindow(market.id(), HOURLY);
        prices.script(market, w0, "100.00", "105.00", "100.00", "105.00");
        prices.script(market, w1, "108.00", "104.00", "103.00", "109.00");   // the pipeline's open: 108.00, not 105.00
        scheduler.tick(w1.endMs());   // w0 and w1 in one tick

        assertThat(settled(w1.roundId()).openPrice()).isEqualTo("105.00");
        assertThat(openCents(w1.roundId())).isEqualTo(10_500L);
    }

    @Test
    void anHourOpensWhereTheDayThatEndedAtItsStartClosedAndTheDayDoesToo() {
        Market market = list("T_BC_BOTH", MarketKind.PERP, Category.CRYPTO, "BCB", 10, 0);
        prices.setPrice(market, "105.00"); // whatever is not scripted, is flat at the price both series close at
        long now = System.currentTimeMillis();
        RoundWindow day = calendar.windowContaining(market.id(), DAILY, now);
        long boundary = day.endMs();
        RoundWindow lastHour = new RoundWindow(market.id(), HOURLY, boundary - STEP, boundary);
        roundProgress.seed(market.id(), DAILY, day);
        roundProgress.seed(market.id(), HOURLY, lastHour);
        RoundWindow nextHour = roundProgress.bettingWindow(market.id(), HOURLY);   // [boundary, boundary + STEP)
        RoundWindow nextDay = roundProgress.bettingWindow(market.id(), DAILY);     // [boundary, boundary + 2 STEP)
        assertThat(nextHour.startMs()).isEqualTo(boundary).isEqualTo(nextDay.startMs());
        String player = signUp("bcboth");
        gameService.placeBet(player, bet(nextHour, Enums.Direction.UP, 4, "100.000"), boundary - 1);
        gameService.placeBet(player, bet(nextDay, Enums.Direction.UP, 4, "100.000"), boundary - 1);
        prices.script(market, day, "100.00", "105.00", "99.00", "106.00");          // the day closes at 105.00 ...
        prices.script(market, lastHour, "104.00", "105.00", "103.00", "105.50");    // ... where the hour closes
        scheduler.tick(boundary);
        assertThat(settled(day.roundId()).closePrice()).isEqualTo("105.00");
        assertThat(settled(lastHour.roundId()).closePrice()).isEqualTo("105.00");

        // the pipeline's idea of the boundary has moved: both new rounds are scripted to open at 107.00
        prices.script(market, nextHour, "107.00", "108.00", "106.00", "108.50");
        prices.script(market, nextDay, "107.00", "109.00", "106.00", "109.50");
        scheduler.tick(boundary + 2 * STEP);

        assertThat(settled(nextHour.roundId()).openPrice()).as("the hour opens where the hour and the day closed")
                .isEqualTo("105.00");
        assertThat(settled(nextDay.roundId()).openPrice()).as("and so does the day").isEqualTo("105.00");
        assertThat(openCents(nextHour.roundId())).isEqualTo(10_500L);
        assertThat(openCents(nextDay.roundId())).isEqualTo(10_500L);
    }

    @Test
    void aPreviousRoundThatWasCancelledHasNoCloseAndNothingOverridesTheNextOpen() {
        Market market = market("T_BC_CANCELLED", "BCC");
        RoundWindow w0 = roundProgress.next(market.id(), HOURLY);
        RoundWindow w1 = roundProgress.bettingWindow(market.id(), HOURLY);
        RoundWindow w2 = RoundClock.next(w1);
        prices.script(market, w0, "100.00", "105.00", "100.00", "105.00");
        gameService.cancelRound(w1, "operator: bad data", w0.startMs() + 1_000L);   // the round after w0 is cancelled
        prices.script(market, w2, "111.00", "112.00", "110.00", "113.00");
        scheduler.tick(w0.endMs());   // w0 settles
        scheduler.tick(w2.endMs());   // w1 is stepped over (it has its row), w2 settles

        assertThat(roundResults.findRoundDto(w1.roundId()).orElseThrow()).isInstanceOf(Rounds.RoundCancelled.class);
        assertThat(settled(w2.roundId()).openPrice()).as("w1 never closed: the pipeline's own open").isEqualTo("111.00");
        assertThat(openCents(w2.roundId())).isEqualTo(11_100L);
        assertThat(settled(w0.roundId()).closePrice()).isEqualTo("105.00");
    }

    @Test
    void theRunningRoundShowsTheSettledCloseAsItsOpenInEveryDtoAlsoWhereThePipelineKnowsNothing() {
        Market market = market("T_BC_DTO", "BCD");
        RoundWindow w0 = roundProgress.next(market.id(), HOURLY);
        RoundWindow w1 = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp("bcdto");
        gameService.placeBet(player, bet(w1, Enums.Direction.UP, 5, "100.000"), w1.startMs() - 1);
        long now = w1.startMs() + 5_000L;

        // before anything closed there: the pipeline knows nothing of the open, and so does everybody
        assertThat(((Rounds.RoundRunning) gameService.roundsPair(Enums.Interval.HOURLY, market.id(), player).running())
                .openPrice()).as("w0 has no open known: waiting for the opening price").isNull();

        prices.script(market, w0, "100.00", "105.00", "100.00", "105.00");
        scheduler.tick(w0.endMs());   // w1 is the running round now
        // the pipeline reports nothing for it yet (soFar unknown): the open is still known — it is w0's close
        Rounds.RoundsPairDto pair = gameService.roundsPair(Enums.Interval.HOURLY, market.id(), player);
        assertThat(pair.running().roundId()).isEqualTo(w1.roundId());
        Rounds.RoundRunning running = (Rounds.RoundRunning) pair.running();
        assertThat(running.openPrice()).isEqualTo("105.00");
        assertThat(running.positions()).singleElement().satisfies(position -> {
            assertThat(position.entryPrice()).isEqualTo("105.00");
            assertThat(position.liquidated()).isFalse();
        });
        assertThat(((Rounds.RoundRunning) gameService.round(w1.roundId(), player)).openPrice()).isEqualTo("105.00");
        List<Markets.MyPositionDto> mine = marketView.myPositions(player, now);
        assertThat(mine).singleElement().satisfies(position -> {
            assertThat(position.phase()).isEqualTo(Enums.RoundPhase.RUNNING);
            assertThat(position.entryPrice()).isEqualTo("105.00");
        });

        // and when the pipeline does know a price for the window, it is not the open (107.00 is where it says it opened)
        prices.setSoFar(market, "107.00", "108.50");
        assertThat(((Rounds.RoundRunning) gameService.roundsPair(Enums.Interval.HOURLY, market.id(), player).running())
                .openPrice()).isEqualTo("105.00");
        assertThat(marketView.myPositions(player, now).get(0).entryPrice()).isEqualTo("105.00");
    }

    @Test
    void theOpenSurvivesARestartBecauseItIsReadBackFromTheDatabase() {
        Market market = market("T_BC_REBUILD", "BCB2");
        RoundWindow w0 = roundProgress.next(market.id(), HOURLY);
        RoundWindow w1 = roundProgress.bettingWindow(market.id(), HOURLY);
        prices.script(market, w0, "100.00", "105.00", "100.00", "105.00");
        scheduler.tick(w0.endMs());
        prices.setSoFar(market, "109.00", "110.00");

        rebuild.rebuild(w0.endMs());
        parkTheDailySeries(market);

        Rounds.RoundRunning running = (Rounds.RoundRunning) gameService.roundsPair(Enums.Interval.HOURLY, market.id(),
                null).running();
        assertThat(running.roundId()).isEqualTo(w1.roundId());
        assertThat(running.openPrice()).as("w0's close, from round_results, not the pipeline's 109.00")
                .isEqualTo("105.00");
    }
}
