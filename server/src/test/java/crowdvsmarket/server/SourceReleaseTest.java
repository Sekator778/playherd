package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotStrategy;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.prices.PriceUnavailableException;
import crowdvsmarket.server.prices.RoundVenues;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Letting a round go after the minutes of its source (Р-79) over the real database: a round that cannot be priced holds its series
 * (the rounds of a series are settled in order), on every market, so five minutes after its end — fifteen for Polymarket, and
 * counted from the start of the server when that is later — it is cancelled with a refund, with the words "No price from the
 * source; stakes returned", and the series goes on. What is written is what any cancellation writes: the refund, the {@code
 * round_results} row, the canonical results JSON, the event; no oracle record. And a round that nobody bet on, which cannot be priced,
 * is stepped over at once — no row, no event, no oracle record — while one that can be priced is settled as ever, and a round
 * with only the bets of bots is not "nobody bet on". The timing and the conditions are pinned by {@code
 * SourceReleaseSchedulerTest}, the settings by {@code PricesConfigTest}; here the prices are scripted ({@code ScriptedMarketPrices})
 * and the rounds are real. The test profile's rounds are a minute long, so the round that is let go carries a bet — an empty overdue
 * window is jumped over by the catch-up rule before five minutes are up, as it always was.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class SourceReleaseTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;
    private static final Interval DAILY = Interval.DAILY;
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final String REASON = "No price from the source; stakes returned";
    private static final String VENUE_REASON = "The exchange of this round stopped sending prices; stakes returned";
    private static final String NO_PRICE = "no oracle price available within 1 hour of round close";

    @Inject
    RoundVenues venues;

    private final List<Market> listed = new ArrayList<>();

    @AfterEach
    void closeWhatThisTestListedAndComeBackToTheRealClock() {
        scheduler.startedAt(0L); // no restart to remember
        for (Market market : listed) {
            if (registry.require(market.id()).isActive()) {
                registry.close(market.id(), "test over", System.currentTimeMillis());
            }
        }
        listed.clear();
        rebuild.rebuild(); // the cursors a simulated clock moved are put back where the real one is
    }

    /** A market priced by the synthetic walk (what the test profile's {@code list} lists): five minutes. */
    private Market listPlain(String id, String symbol) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, symbol, 10, 0);
        listed.add(market);
        prices.setPrice(market, "100.00");
        return market;
    }

    /** A Polymarket odds market: its source says so, and with it its fifteen minutes. */
    private Market listPolymarket(String id, String symbol) {
        Market market = registry.list(new NewMarket(id, MarketKind.ODDS, Category.POLITICS, symbol, symbol + " test market",
                "Yes", null, "polymarket", "token-" + id, null, 10, 4, null), System.currentTimeMillis());
        listed.add(market);
        prices.setPrice(market, "0.5000");
        return market;
    }

    /** A market with Kraken behind Coinbase, as the spot catalog lists the coins. */
    private Market listBackup(String id, String symbol) {
        Market market = listWithBackup(id, symbol, "kraken", symbol + "/USD");
        listed.add(market);
        prices.setPrice(market, "100.00");
        return market;
    }

    private void parkTheDailySeries(Market market) {
        roundProgress.seed(market.id(), DAILY,
                calendar.windowContaining(market.id(), DAILY, System.currentTimeMillis() + 10 * 86_400_000L));
    }

    /** A round the market's source cannot price, and a bet in it. {@code stuck} is the series' running round. */
    private record Fixture(Market market, RoundWindow stuck, String player) {
    }

    /**
     * The hourly series of {@code market} (its daily one parked) at a round with a bet of 300 PLAY that cannot be priced: the empty round
     * before it has settled, and the round with the bet has begun. {@code venue}: the exchange the round was decided on, for a market
     * with a backup; {@code null} for a market without one.
     */
    private Fixture marketWithAStuckRound(Market market, String venue, String label) {
        parkTheDailySeries(market);
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow stuck = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp(label);
        gameService.placeBet(player, bet(stuck, Enums.Direction.UP, 2, "300.000"), stuck.startMs() - 1);
        decide(market, stuck, venue);
        prices.failResolve(market, stuck, new PriceUnavailableException("no price from the source"));
        scheduler.tick(running.endMs());
        assertThat(roundProgress.next(market.id(), HOURLY)).as("the round with the bet is the running one").isEqualTo(stuck);
        return new Fixture(market, stuck, player);
    }

    private void decide(Market market, RoundWindow window, String venue) {
        if (venue != null) {
            venues.decide(window.roundId(), market.id(), window.startMs(), venue, window.startMs());
            prices.setVenue(market, venue);
        }
    }

    private Rounds.RoundCancelled cancelled(String roundId) {
        Rounds.RoundDto dto = roundResults.findRoundDto(roundId).orElseThrow();
        assertThat(dto).as(roundId).isInstanceOf(Rounds.RoundCancelled.class);
        return (Rounds.RoundCancelled) dto;
    }

    private String venueColumn(String roundId) {
        return db.read(c -> db.queryOne(c, "SELECT venue FROM round_results WHERE round_id = ?",
                rs -> rs.getString("venue"), roundId));
    }

    private String eventReason(RoundWindow window) {
        return db.read(c -> db.queryOne(c, "SELECT payload->>'reason' AS r FROM game_events WHERE event_type = "
                + "'RoundCancelled' AND payload->>'market' = ? AND payload->>'interval' = ? "
                + "AND (payload->>'windowStart')::bigint = ?", rs -> rs.getString("r"), window.market(),
                window.interval().name(), window.startMs()));
    }

    /** How many events say a round was settled or cancelled: none for a round that was stepped over. */
    private long settlementEvents(RoundWindow window) {
        return db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events WHERE payload->>'market' = ? "
                + "AND payload->>'interval' = ? AND (payload->>'windowStart')::bigint = ? "
                + "AND event_type IN ('RoundResolved', 'RoundCancelled')", rs -> rs.getLong("n"), window.market(),
                window.interval().name(), window.startMs()));
    }

    private boolean listedInTheCancellationsLog(String roundId, String reason) {
        return gameService.cancellations(new Paging.PageParams(100, null)).items().stream()
                .anyMatch(c -> c.roundId().equals(roundId) && c.reason().equals(reason));
    }

    /** Everything a released round says about itself, wherever it says it. */
    private void assertReleased(Fixture f, String reason, String venue, long atMs) {
        String roundId = f.stuck().roundId();
        Rounds.RoundCancelled round = cancelled(roundId);
        assertThat(round.cancellation().reason()).as("the words players read").isEqualTo(reason);
        assertThat(round.cancellation().refundedBetCount()).isEqualTo(1);
        assertThat(round.cancellation().cancelledAtMs()).isEqualTo(atMs);
        assertThat(round.cancellation().roundId()).isEqualTo(roundId);
        assertThat(round.cancellation().marketId()).isEqualTo(f.market().id());
        assertThat(venueColumn(roundId)).as("the exchange the round was decided on is kept, if it had one").isEqualTo(venue);
        assertThat(roundResults.canonicalJson(roundId).orElseThrow()).contains("\"positions\":[]")
                .contains("\"status\":\"CANCELLED\"");
        assertThat(eventReason(f.stuck())).isEqualTo(reason);
        assertThat(listedInTheCancellationsLog(roundId, reason)).isTrue();
        assertThat(gameService.account(f.player()).balance()).as("the stake is back").isEqualTo("10000.000");
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("0.000");
        assertThat(oracleRowCount(roundId)).as("a cancelled round has no oracle record").isZero();
        assertThat(roundProgress.next(f.market().id(), HOURLY).startMs()).as("the series goes on")
                .isGreaterThanOrEqualTo(f.stuck().endMs());
    }

    // ------------------------------------------------------------------ the release

    @Test
    void aRoundOfAMarketWithoutABackupIsCancelledWithARefundFiveMinutesAfterItsEnd() {
        Fixture f = marketWithAStuckRound(listPlain("T_SR_PLAIN", "SRP"), null, "srplain");
        long end = f.stuck().endMs();

        scheduler.tick(end);
        scheduler.tick(end + 2 * MINUTE);
        scheduler.tick(end + 5 * MINUTE - 1);
        assertThat(roundResults.exists(f.stuck().roundId())).as("not before five minutes").isFalse();
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("300.000");
        assertThat(roundProgress.next(f.market().id(), HOURLY)).as("the series waits").isEqualTo(f.stuck());

        scheduler.tick(end + 5 * MINUTE);

        assertReleased(f, REASON, null, end + 5 * MINUTE);
        assertThat(cancelled(f.stuck().roundId()).source()).as("the market's own source").isEqualTo("synthetic");
    }

    @Test
    void aPolymarketRoundWaitsAQuarterOfAnHour() {
        Fixture f = marketWithAStuckRound(listPolymarket("T_SR_ODDS", "SRO"), null, "srodds");
        long end = f.stuck().endMs();
        scheduler.tick(end);

        scheduler.tick(end + 5 * MINUTE);
        scheduler.tick(end + 15 * MINUTE - 1);
        assertThat(roundResults.exists(f.stuck().roundId())).as("neither at five minutes nor a moment before fifteen").isFalse();
        assertThat(roundProgress.next(f.market().id(), HOURLY)).isEqualTo(f.stuck());

        scheduler.tick(end + 15 * MINUTE);

        assertReleased(f, REASON, null, end + 15 * MINUTE);
        assertThat(cancelled(f.stuck().roundId()).source()).isEqualTo("polymarket");
    }

    @Test
    void theSeriesGoesOnAfterTheReleaseAndTheNextRoundIsSettledAsAlways() {
        Fixture f = marketWithAStuckRound(listPlain("T_SR_NEXT", "SRN"), null, "srnext");
        long end = f.stuck().endMs();
        scheduler.tick(end);
        scheduler.tick(end + 5 * MINUTE);
        assertThat(roundResults.exists(f.stuck().roundId())).isTrue();
        RoundWindow betting = roundProgress.bettingWindow(f.market().id(), HOURLY);
        String player = signUp("srnextbet");
        gameService.placeBet(player, bet(betting, Enums.Direction.DOWN, 2, "100.000"), betting.startMs() - 1);

        scheduler.tick(betting.endMs() + 1_000L);

        assertThat(roundResults.findRoundDto(betting.roundId()).orElseThrow()).isInstanceOf(Rounds.RoundSettled.class);
        assertThat(oracleRowCount(betting.roundId())).isEqualTo(1);
    }

    @Test
    void bothSeriesOfAMarketAreLetGoEachFiveMinutesAfterItsOwnEnd() {
        Market market = listPlain("T_SR_BOTH", "SRB");
        RoundWindow runningH = roundProgress.next(market.id(), HOURLY);
        RoundWindow stuckH = roundProgress.bettingWindow(market.id(), HOURLY);
        RoundWindow runningD = roundProgress.next(market.id(), DAILY);
        RoundWindow stuckD = roundProgress.bettingWindow(market.id(), DAILY);
        String player = signUp("srboth");
        gameService.placeBet(player, bet(stuckH, Enums.Direction.UP, 2, "300.000"), stuckH.startMs() - 1);
        gameService.placeBet(player, bet(stuckD, Enums.Direction.DOWN, 2, "200.000"), stuckD.startMs() - 1);
        prices.failResolve(market, stuckH, new PriceUnavailableException("no price from the source"));
        prices.failResolve(market, stuckD, new PriceUnavailableException("no price from the source"));
        long releaseH = stuckH.endMs() + 5 * MINUTE;
        long releaseD = stuckD.endMs() + 5 * MINUTE;
        assertThat(releaseH).as("the hourly round ends first").isLessThan(releaseD);
        scheduler.tick(Math.max(runningH.endMs(), runningD.endMs())); // the empty rounds before them settle

        scheduler.tick(releaseH - 1);
        assertThat(roundResults.exists(stuckH.roundId())).isFalse();
        assertThat(roundResults.exists(stuckD.roundId())).isFalse();
        scheduler.tick(releaseH);

        assertThat(cancelled(stuckH.roundId()).cancellation().reason()).isEqualTo(REASON);
        assertThat(roundResults.exists(stuckD.roundId())).as("the daily round has its own five minutes").isFalse();
        assertThat(roundProgress.next(market.id(), HOURLY).startMs()).isGreaterThanOrEqualTo(stuckH.endMs());
        assertThat(roundProgress.next(market.id(), DAILY)).isEqualTo(stuckD);

        scheduler.tick(releaseD - 1);
        assertThat(roundResults.exists(stuckD.roundId())).isFalse();
        scheduler.tick(releaseD);

        Rounds.RoundCancelled daily = cancelled(stuckD.roundId());
        assertThat(daily.cancellation().reason()).isEqualTo(REASON);
        assertThat(daily.cancellation().cancelledAtMs()).isEqualTo(releaseD);
        assertThat(gameService.account(player).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(player).inPlay()).isEqualTo("0.000");
        assertThat(roundProgress.next(market.id(), DAILY).startMs()).isGreaterThanOrEqualTo(stuckD.endMs());
    }

    // ------------------------------------------------------------------ a market with a backup exchange

    @Test
    void withBothExchangesSilentTheRoundIsLetGoAtTheMinutesOfItsExchangeAndKeepsItsExchange() {
        Fixture f = marketWithAStuckRound(listBackup("T_SR_BOTHOUT", "SRX"), "coinbase", "srbothout");
        long end = f.stuck().endMs();
        scheduler.tick(end);

        scheduler.tick(end + 2 * MINUTE); // the other exchange is silent too: the two minutes of Р-77 do not apply
        scheduler.tick(end + 5 * MINUTE - 1);
        assertThat(roundResults.exists(f.stuck().roundId())).isFalse();
        scheduler.tick(end + 5 * MINUTE);

        assertReleased(f, REASON, "coinbase", end + 5 * MINUTE);
        assertThat(cancelled(f.stuck().roundId()).source()).isEqualTo("coinbase");
    }

    @Test
    void withTheOtherExchangeAliveTheTwoMinutesOfTheVenueReleaseComeFirstWithTheirOwnWords() {
        Fixture f = marketWithAStuckRound(listBackup("T_SR_FIRST", "SRF"), "coinbase", "srfirst");
        prices.setOtherVenueLive(f.market(), true);
        long end = f.stuck().endMs();
        scheduler.tick(end);

        scheduler.tick(end + 2 * MINUTE - 1);
        assertThat(roundResults.exists(f.stuck().roundId())).isFalse();
        scheduler.tick(end + 2 * MINUTE);

        assertReleased(f, VENUE_REASON, "coinbase", end + 2 * MINUTE);
    }

    // ------------------------------------------------------------------ a restart, and the hour

    @Test
    void aRestartInTheMiddleGivesTheRoundItsFiveMinutesAgain() {
        Fixture f = marketWithAStuckRound(listPlain("T_SR_RESTART", "SRR"), null, "srrestart");
        long end = f.stuck().endMs();
        long restart = end + 20 * MINUTE; // the round ended twenty minutes ago: its five minutes are long over

        rebuild.rebuild(restart);          // the server restarts: its memory is rebuilt from the database ...
        scheduler.forgetFailedAttempts();  // ... what the scheduler remembered of the failed attempts is gone ...
        scheduler.startedAt(restart);      // ... and it started just now
        parkTheDailySeries(f.market());
        assertThat(roundProgress.next(f.market().id(), HOURLY)).as("the series is at the round with the bet again")
                .isEqualTo(f.stuck());

        scheduler.tick(restart);
        scheduler.tick(restart + 5 * MINUTE - 1);

        assertThat(roundResults.exists(f.stuck().roundId())).as("not at once: the candles have five minutes to heal the gap").isFalse();
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("300.000");

        scheduler.tick(restart + 5 * MINUTE);

        assertReleased(f, REASON, null, restart + 5 * MINUTE);
    }

    @Test
    void aRoundWhoseEndIsMoreThanAnHourBehindIsRefundedAtOnceAsToday() {
        Fixture f = marketWithAStuckRound(listPlain("T_SR_LONG", "SRL"), null, "srlong");
        long restart = f.stuck().endMs() + 2 * HOUR;

        rebuild.rebuild(restart);
        scheduler.forgetFailedAttempts();
        scheduler.startedAt(restart);
        parkTheDailySeries(f.market());
        scheduler.tick(restart);

        assertThat(cancelled(f.stuck().roundId()).cancellation().reason()).as("the hour's own words").isEqualTo(NO_PRICE);
        assertThat(cancelled(f.stuck().roundId()).cancellation().cancelledAtMs()).isEqualTo(restart);
        assertThat(gameService.account(f.player()).balance()).isEqualTo("10000.000");
    }

    @Test
    void aRoundThatHealsBeforeTheFiveMinutesAreUpIsSettledAsAlways() {
        Fixture f = marketWithAStuckRound(listPlain("T_SR_HEAL", "SRH"), null, "srheal");
        long end = f.stuck().endMs();
        scheduler.tick(end);
        scheduler.tick(end + 3 * MINUTE);

        prices.healResolves(f.market()); // the source is back
        scheduler.tick(end + 4 * MINUTE + 55_000L);

        assertThat(roundResults.findRoundDto(f.stuck().roundId()).orElseThrow()).isInstanceOf(Rounds.RoundSettled.class);
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("0.000");
        assertThat(listedInTheCancellationsLog(f.stuck().roundId(), REASON)).isFalse();
    }

    @Test
    void aFailureThatIsNotNoPriceStillGetsTheHourNotTheFiveMinutes() {
        Market market = listPlain("T_SR_OTHER", "SRT");
        parkTheDailySeries(market);
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow stuck = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp("srother");
        gameService.placeBet(player, bet(stuck, Enums.Direction.UP, 2, "300.000"), stuck.startMs() - 1);
        prices.failResolve(market, stuck, new IllegalStateException("HTTP 502 from the price source"));
        scheduler.tick(running.endMs());
        long end = stuck.endMs();

        scheduler.tick(end);
        scheduler.tick(end + 5 * MINUTE);
        scheduler.tick(end + HOUR - 1);
        assertThat(roundResults.exists(stuck.roundId())).isFalse();
        assertThat(gameService.account(player).inPlay()).isEqualTo("300.000");

        scheduler.tick(end + HOUR);

        assertThat(cancelled(stuck.roundId()).cancellation().reason()).isEqualTo("settlement failed within 1 hour of round close");
        assertThat(gameService.account(player).balance()).isEqualTo("10000.000");
    }

    // ------------------------------------------------------------------ a round nobody bet on

    @Test
    void anEmptyRoundThatCannotBePricedIsSkippedAtOnceWithNoRowNoEventNoOracleRecord() {
        Market market = listPlain("T_SR_EMPTY", "SRE");
        parkTheDailySeries(market);
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow next = roundProgress.bettingWindow(market.id(), HOURLY);
        prices.failAllResolves(market, new PriceUnavailableException("no price from the source"));

        scheduler.tick(running.endMs());

        assertThat(roundProgress.next(market.id(), HOURLY)).as("the series goes on at once, not after five minutes").isEqualTo(next);
        assertThat(roundResults.exists(running.roundId())).as("no result row").isFalse();
        assertThat(settlementEvents(running)).as("no event").isZero();
        assertThat(oracleRowCount(running.roundId())).as("no oracle record").isZero();
        assertThat(listedInTheCancellationsLog(running.roundId(), REASON)).as("and no cancellation to show").isFalse();

        prices.healResolves(market); // the price is back: the next round is settled as always
        scheduler.tick(next.endMs());

        assertThat(roundResults.findRoundDto(next.roundId()).orElseThrow()).isInstanceOf(Rounds.RoundSettled.class);
        assertThat(settlementEvents(next)).isEqualTo(1);
        assertThat(oracleRowCount(next.roundId())).isEqualTo(1);
    }

    @Test
    void anEmptyRoundThatCanBePricedIsSettledAsBefore() {
        Market market = listPlain("T_SR_PRICED", "SRD");
        parkTheDailySeries(market);
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow next = roundProgress.bettingWindow(market.id(), HOURLY);

        scheduler.tick(running.endMs());

        assertThat(roundResults.findRoundDto(running.roundId()).orElseThrow()).isInstanceOf(Rounds.RoundSettled.class);
        assertThat(settlementEvents(running)).as("the event of a settlement").isEqualTo(1);
        assertThat(oracleRowCount(running.roundId())).as("and its oracle record").isEqualTo(1);
        assertThat(roundProgress.next(market.id(), HOURLY)).isEqualTo(next);
    }

    @Test
    void aRoundWithOnlyTheBetsOfBotsIsNotEmptyItWaitsItsFiveMinutesAndIsRefunded() {
        Market market = listPlain("T_SR_BOT", "SRO2");
        parkTheDailySeries(market);
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow stuck = roundProgress.bettingWindow(market.id(), HOURLY);
        String bot = gameService.createBotPlayer("Bot SR (test)", BotStrategy.values()[0].name(), System.currentTimeMillis())
                .playerId();
        gameService.placeBet(bot, bet(stuck, Enums.Direction.UP, 2, "300.000"), stuck.startMs() - 1);
        prices.failResolve(market, stuck, new PriceUnavailableException("no price from the source"));
        scheduler.tick(running.endMs());
        long end = stuck.endMs();

        scheduler.tick(end);
        scheduler.tick(end + 5 * MINUTE - 1);

        assertThat(roundProgress.next(market.id(), HOURLY)).as("a bot's bet is a bet: not skipped").isEqualTo(stuck);
        assertThat(gameService.hasPendingBets(stuck)).isTrue();
        assertThat(roundResults.exists(stuck.roundId())).isFalse();

        scheduler.tick(end + 5 * MINUTE);

        assertThat(cancelled(stuck.roundId()).cancellation().reason()).isEqualTo(REASON);
        assertThat(cancelled(stuck.roundId()).cancellation().refundedBetCount()).isEqualTo(1);
        assertThat(gameService.account(bot).inPlay()).as("the bot's stake is back").isEqualTo("0.000");
    }

    @Test
    void anEmptyRoundOfAMarketWithABackupIsSkippedTooWithoutAskingTheOtherExchange() {
        Market market = listBackup("T_SR_EMPTYB", "SRY");
        parkTheDailySeries(market);
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow next = roundProgress.bettingWindow(market.id(), HOURLY);
        prices.setOtherVenueLive(market, true);
        prices.failAllResolves(market, new PriceUnavailableException("no price from the exchange"));

        scheduler.tick(running.endMs());

        assertThat(roundProgress.next(market.id(), HOURLY)).isEqualTo(next);
        assertThat(roundResults.exists(running.roundId())).isFalse();
        assertThat(settlementEvents(running)).isZero();
        assertThat(prices.otherVenueLiveAsked()).as("nothing to give back, so nothing to ask").isEmpty();
    }
}
