package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
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
 * Letting a round go (Р-77) over the real database: a round of a market with a backup exchange that cannot be settled for want of
 * a price holds its series (the rounds of a series are settled in order), so two minutes after its end — while the market's
 * other exchange is alive — it is cancelled with a refund, and the series goes on. What is written is what any
 * cancellation writes, and the round keeps the exchange it was decided on: the reason players read, the refund, the
 * {@code round_results} row with its exchange, the canonical results JSON, the cancellation DTO, the event; no oracle
 * record. The timing and the conditions are pinned by {@code VenueReleaseSchedulerTest}, what "alive" means by
 * {@code LiveMarketPricesReleaseTest}; here the prices are scripted ({@code ScriptedMarketPrices}: the exchange of the round
 * gives no price, the other one is alive or not) and the rounds are real. The test profile's rounds are a minute long, so
 * the round that is let go carries a bet — an empty overdue window is jumped over by the catch-up rule before two minutes
 * are up, as it always was. Where the other exchange is silent too, and for a market with one exchange only, the two minutes
 * do not apply: the five minutes of the source do (Р-79, {@code SourceReleaseTest}).
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class VenueReleaseTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;
    private static final Interval DAILY = Interval.DAILY;
    private static final long TWO_MINUTES = 120_000L;
    private static final long FIVE_MINUTES = 300_000L;
    private static final String REASON = "The exchange of this round stopped sending prices; stakes returned";
    /** Р-79: where no exchange is alive to blame, or there is only one exchange: the minutes of the source. */
    private static final String SOURCE_REASON = "No price from the source; stakes returned";

    @Inject
    RoundVenues venues;

    private final List<Market> listed = new ArrayList<>();

    @AfterEach
    void closeWhatThisTestListedAndComeBackToTheRealClock() {
        for (Market market : listed) {
            if (registry.require(market.id()).isActive()) {
                registry.close(market.id(), "test over", System.currentTimeMillis());
            }
        }
        listed.clear();
        rebuild.rebuild(); // the cursors a simulated clock moved are put back where the real one is
    }

    private Market listBackupMarket(String id, String symbol) {
        Market market = listWithBackup(id, symbol, "kraken", symbol + "/USD");
        listed.add(market);
        prices.setPrice(market, "100.00");
        return market;
    }

    private void parkTheDailySeries(Market market) {
        roundProgress.seed(market.id(), DAILY,
                calendar.windowContaining(market.id(), DAILY, System.currentTimeMillis() + 10 * 86_400_000L));
    }

    /** A round the market's exchange cannot price, and a bet in it. {@code stuck} is the series' running round. */
    private record Fixture(Market market, RoundWindow stuck, String player) {
    }

    /**
     * The hourly series of {@code market} (its daily one parked) at a round with a bet of 300 PLAY, decided on
     * {@code venue} ({@code null}: none — a market without a backup) and unable to be priced: the empty round before it
     * has settled, and the round with the bet has begun.
     */
    private Fixture marketWithAStuckRound(Market market, String venue, String label) {
        parkTheDailySeries(market);
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow stuck = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp(label);
        gameService.placeBet(player, bet(stuck, Enums.Direction.UP, 2, "300.000"), stuck.startMs() - 1);
        if (venue != null) {
            venues.decide(stuck.roundId(), market.id(), stuck.startMs(), venue, stuck.startMs());
        }
        prices.failResolve(market, stuck, new PriceUnavailableException("no price from " + venue));
        scheduler.tick(running.endMs());
        assertThat(roundProgress.next(market.id(), HOURLY)).as("the round with the bet is the running one").isEqualTo(stuck);
        return new Fixture(market, stuck, player);
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

    private boolean listedInTheCancellationsLog(String roundId, String reason) {
        return gameService.cancellations(new Paging.PageParams(100, null)).items().stream()
                .anyMatch(c -> c.roundId().equals(roundId) && c.reason().equals(reason));
    }

    /** Everything a released round says about itself, wherever it says it. */
    private void assertReleasedOn(Fixture f, String venue, long atMs) {
        String roundId = f.stuck().roundId();
        Rounds.RoundCancelled round = cancelled(roundId);
        assertThat(round.cancellation().reason()).as("the words players read").isEqualTo(REASON);
        assertThat(round.cancellation().refundedBetCount()).isEqualTo(1);
        assertThat(round.cancellation().cancelledAtMs()).isEqualTo(atMs);
        assertThat(round.cancellation().roundId()).isEqualTo(roundId);
        assertThat(round.cancellation().marketId()).isEqualTo(f.market().id());
        assertThat(round.source()).as("the exchange the round was decided on is kept").isEqualTo(venue);
        assertThat(venueColumn(roundId)).isEqualTo(venue);
        assertThat(roundResults.canonicalJson(roundId).orElseThrow()).contains("\"positions\":[]")
                .endsWith(",\"status\":\"CANCELLED\",\"venue\":\"" + venue + "\"}");
        assertThat(eventReason(f.stuck())).isEqualTo(REASON);
        assertThat(listedInTheCancellationsLog(roundId, REASON)).isTrue();
        assertThat(gameService.account(f.player()).balance()).as("the stake is back").isEqualTo("10000.000");
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("0.000");
        assertThat(oracleRowCount(roundId)).as("a cancelled round has no oracle record").isZero();
        assertThat(roundProgress.next(f.market().id(), HOURLY).startMs()).as("the series goes on")
                .isGreaterThanOrEqualTo(f.stuck().endMs());
    }

    // ------------------------------------------------------------------ the release

    @Test
    void aRoundWhoseExchangeIsSilentIsCancelledWithARefundTwoMinutesAfterItsEndWhenTheOtherExchangeIsAlive() {
        Fixture f = marketWithAStuckRound(listBackupMarket("T_VR_GO", "VRG"), "coinbase", "vrgo");
        prices.setOtherVenueLive(f.market(), true);
        long end = f.stuck().endMs();

        scheduler.tick(end);
        scheduler.tick(end + 60_000L);
        scheduler.tick(end + TWO_MINUTES - 1);
        assertThat(roundResults.exists(f.stuck().roundId())).as("not before two minutes").isFalse();
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("300.000");
        assertThat(roundProgress.next(f.market().id(), HOURLY)).as("the series waits").isEqualTo(f.stuck());

        scheduler.tick(end + TWO_MINUTES);

        assertReleasedOn(f, "coinbase", end + TWO_MINUTES);
    }

    @Test
    void aRoundDecidedOnTheBackupWhoseBackupWentSilentIsLetGoWhileThePrimaryIsAlive() {
        Fixture f = marketWithAStuckRound(listBackupMarket("T_VR_BACKUP", "VRK"), "kraken", "vrbackup");
        prices.setOtherVenueLive(f.market(), true); // the primary, Coinbase, is alive
        long end = f.stuck().endMs();
        scheduler.tick(end);

        scheduler.tick(end + TWO_MINUTES - 1);
        assertThat(roundResults.exists(f.stuck().roundId())).isFalse();
        scheduler.tick(end + TWO_MINUTES);

        assertReleasedOn(f, "kraken", end + TWO_MINUTES);
    }

    @Test
    void theSeriesGoesOnAfterTheReleaseAndTheNextRoundIsOpenedByTheUsualRule() {
        Fixture f = marketWithAStuckRound(listBackupMarket("T_VR_NEXT", "VRN"), "coinbase", "vrnext");
        prices.setOtherVenueLive(f.market(), true);
        long end = f.stuck().endMs();
        scheduler.tick(end);
        scheduler.tick(end + TWO_MINUTES);
        assertThat(roundResults.exists(f.stuck().roundId())).isTrue();

        scheduler.tick(end + TWO_MINUTES + 1_000L);

        RoundWindow running = roundProgress.next(f.market().id(), HOURLY);
        assertThat(running.startMs()).isGreaterThanOrEqualTo(f.stuck().endMs());
        assertThat(prices.openedRounds()).as("the round the series is at now is opened — its exchange chosen — at the tick")
                .contains(running.roundId());
        // and the bets of the next round are taken and settled as always
        RoundWindow betting = roundProgress.bettingWindow(f.market().id(), HOURLY);
        String player = signUp("vrnextbet");
        gameService.placeBet(player, bet(betting, Enums.Direction.DOWN, 2, "100.000"), betting.startMs() - 1);
        prices.setVenue(f.market(), "kraken"); // what the rule gave it: Coinbase had no live tick at its start
        scheduler.tick(betting.endMs() + 1_000L);
        assertThat(roundResults.findRoundDto(betting.roundId()).orElseThrow()).isInstanceOf(Rounds.RoundSettled.class);
        assertThat(venueColumn(betting.roundId())).isEqualTo("kraken");
    }

    @Test
    void bothSeriesOfAMarketAreLetGoEachTwoMinutesAfterItsOwnEnd() {
        Market market = listBackupMarket("T_VR_BOTH", "VRB");
        RoundWindow runningH = roundProgress.next(market.id(), HOURLY);
        RoundWindow stuckH = roundProgress.bettingWindow(market.id(), HOURLY);
        RoundWindow runningD = roundProgress.next(market.id(), DAILY);
        RoundWindow stuckD = roundProgress.bettingWindow(market.id(), DAILY);
        String player = signUp("vrboth");
        gameService.placeBet(player, bet(stuckH, Enums.Direction.UP, 2, "300.000"), stuckH.startMs() - 1);
        gameService.placeBet(player, bet(stuckD, Enums.Direction.DOWN, 2, "200.000"), stuckD.startMs() - 1);
        venues.decide(stuckH.roundId(), market.id(), stuckH.startMs(), "coinbase", stuckH.startMs());
        venues.decide(stuckD.roundId(), market.id(), stuckD.startMs(), "coinbase", stuckD.startMs());
        prices.failResolve(market, stuckH, new PriceUnavailableException("no price from coinbase"));
        prices.failResolve(market, stuckD, new PriceUnavailableException("no price from coinbase"));
        prices.setOtherVenueLive(market, true);
        long releaseH = stuckH.endMs() + TWO_MINUTES;
        long releaseD = stuckD.endMs() + TWO_MINUTES;
        assertThat(releaseH).as("the hourly round ends first").isLessThan(releaseD);
        scheduler.tick(Math.max(runningH.endMs(), runningD.endMs())); // the empty rounds before them settle

        scheduler.tick(releaseH - 1);
        assertThat(roundResults.exists(stuckH.roundId())).isFalse();
        assertThat(roundResults.exists(stuckD.roundId())).isFalse();
        scheduler.tick(releaseH);

        assertThat(cancelled(stuckH.roundId()).cancellation().reason()).isEqualTo(REASON);
        assertThat(roundResults.exists(stuckD.roundId())).as("the daily round has its own two minutes").isFalse();
        assertThat(roundProgress.next(market.id(), HOURLY).startMs()).isGreaterThanOrEqualTo(stuckH.endMs());
        assertThat(roundProgress.next(market.id(), DAILY)).isEqualTo(stuckD);

        scheduler.tick(releaseD - 1);
        assertThat(roundResults.exists(stuckD.roundId())).isFalse();
        scheduler.tick(releaseD);

        Rounds.RoundCancelled daily = cancelled(stuckD.roundId());
        assertThat(daily.cancellation().reason()).isEqualTo(REASON);
        assertThat(daily.cancellation().cancelledAtMs()).isEqualTo(releaseD);
        assertThat(daily.source()).isEqualTo("coinbase");
        assertThat(cancelled(stuckH.roundId()).source()).isEqualTo("coinbase");
        assertThat(venueColumn(stuckH.roundId())).isEqualTo("coinbase");
        assertThat(venueColumn(stuckD.roundId())).isEqualTo("coinbase");
        assertThat(gameService.account(player).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(player).inPlay()).isEqualTo("0.000");
        assertThat(roundProgress.next(market.id(), DAILY).startMs()).isGreaterThanOrEqualTo(stuckD.endMs());
    }

    @Test
    void aRestartBetweenTheEndOfTheRoundAndItsReleaseDoesNotChangeWhatHappens() {
        Fixture f = marketWithAStuckRound(listBackupMarket("T_VR_RESTART", "VRR"), "coinbase", "vrrestart");
        prices.setOtherVenueLive(f.market(), true);
        long end = f.stuck().endMs();
        scheduler.tick(end);
        scheduler.tick(end + 30_000L);

        rebuild.rebuild(end + 40_000L);       // the server restarts: its memory is rebuilt from the database ...
        scheduler.forgetFailedAttempts();     // ... and what the scheduler remembered of the failed attempts is gone
        parkTheDailySeries(f.market());
        assertThat(roundProgress.next(f.market().id(), HOURLY)).as("the series is at the round with the bet again")
                .isEqualTo(f.stuck());
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("300.000");

        scheduler.tick(end + TWO_MINUTES - 1);
        assertThat(roundResults.exists(f.stuck().roundId())).as("still not before two minutes").isFalse();
        scheduler.tick(end + TWO_MINUTES);

        assertReleasedOn(f, "coinbase", end + TWO_MINUTES);
    }

    @Test
    void aRoundFirstTriedAfterARestartThatTookLongerThanTwoMinutesIsLetGoAtOnce() {
        Fixture f = marketWithAStuckRound(listBackupMarket("T_VR_LATE", "VRL"), "coinbase", "vrlate");
        prices.setOtherVenueLive(f.market(), true);
        long end = f.stuck().endMs();

        rebuild.rebuild(end + 10 * 60_000L);  // the server was down for ten minutes after the round ended
        parkTheDailySeries(f.market());
        scheduler.forgetFailedAttempts();
        scheduler.tick(end + 10 * 60_000L);

        assertReleasedOn(f, "coinbase", end + 10 * 60_000L);
    }

    // ------------------------------------------------------------------ what it is not

    @Test
    void withTheOtherExchangeSilentTooTheTwoMinutesDoNotApplyTheRoundWaitsAndIsLetGoAtTheFiveMinutesOfItsSource() {
        Fixture f = marketWithAStuckRound(listBackupMarket("T_VR_BOTHSILENT", "VRS"), "coinbase", "vrsilent");
        long end = f.stuck().endMs();

        scheduler.tick(end);
        scheduler.tick(end + TWO_MINUTES);
        scheduler.tick(end + FIVE_MINUTES - 1);

        assertThat(roundResults.exists(f.stuck().roundId())).isFalse();
        assertThat(roundProgress.next(f.market().id(), HOURLY)).as("the series stands still").isEqualTo(f.stuck());
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("300.000");
        assertThat(prices.otherVenueLiveAsked()).as("it was asked, and said no").contains(f.stuck().roundId());

        scheduler.tick(end + FIVE_MINUTES); // Р-79: the minutes of the source apply whatever the other exchange does

        Rounds.RoundCancelled round = cancelled(f.stuck().roundId());
        assertThat(round.cancellation().reason()).as("not the words of the exchange: the other one is silent too")
                .isEqualTo(SOURCE_REASON);
        assertThat(round.source()).as("it keeps its exchange all the same").isEqualTo("coinbase");
        assertThat(gameService.account(f.player()).balance()).isEqualTo("10000.000");
        assertThat(roundProgress.next(f.market().id(), HOURLY).startMs()).isGreaterThanOrEqualTo(f.stuck().endMs());
    }

    @Test
    void theOtherExchangeComingAliveLaterLetsTheWaitingRoundGoAtTheNextAttempt() {
        Fixture f = marketWithAStuckRound(listBackupMarket("T_VR_LATER", "VRT"), "coinbase", "vrlater");
        long end = f.stuck().endMs();
        for (long at = end; at < end + 4 * 60_000L; at += 10_000L) {
            scheduler.tick(at); // both exchanges silent for four minutes: short of the five of Р-79
        }
        assertThat(roundResults.exists(f.stuck().roundId())).isFalse();
        assertThat(roundProgress.next(f.market().id(), HOURLY)).isEqualTo(f.stuck());

        prices.setOtherVenueLive(f.market(), true);
        long alive = end + 4 * 60_000L;
        for (long at = alive; at <= alive + 60_000L && !roundResults.exists(f.stuck().roundId()); at += 1_000L) {
            scheduler.tick(at);
        }

        assertThat(roundResults.exists(f.stuck().roundId())).as("let go within a backoff of the other exchange coming alive")
                .isTrue();
        assertThat(cancelled(f.stuck().roundId()).cancellation().cancelledAtMs()).isBetween(alive, alive + 60_000L);
        assertThat(cancelled(f.stuck().roundId()).cancellation().reason()).isEqualTo(REASON);
        assertThat(venueColumn(f.stuck().roundId())).isEqualTo("coinbase");
        assertThat(gameService.account(f.player()).balance()).isEqualTo("10000.000");
    }

    @Test
    void aRoundThatHealsBeforeTheTwoMinutesAreUpIsSettledAsAlways() {
        Fixture f = marketWithAStuckRound(listBackupMarket("T_VR_HEAL", "VRH"), "coinbase", "vrheal");
        prices.setOtherVenueLive(f.market(), true);
        long end = f.stuck().endMs();
        scheduler.tick(end);
        scheduler.tick(end + 60_000L);

        prices.healResolves(f.market()); // the exchange is back, the candles heal the round
        scheduler.tick(end + 119_000L);

        assertThat(roundResults.findRoundDto(f.stuck().roundId()).orElseThrow()).isInstanceOf(Rounds.RoundSettled.class);
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("0.000");
        assertThat(listedInTheCancellationsLog(f.stuck().roundId(), REASON)).isFalse();
        assertThat(roundProgress.next(f.market().id(), HOURLY).startMs()).isGreaterThanOrEqualTo(f.stuck().endMs());
    }

    @Test
    void aMarketWithoutABackupIsNotTouchedByTheTwoMinutesWhateverTheScriptSaysOfAnotherExchange() {
        Market plain = list("T_VR_PLAIN", MarketKind.PERP, Category.CRYPTO, "VRP", 10, 0);
        listed.add(plain);
        prices.setPrice(plain, "100.00");
        Fixture f = marketWithAStuckRound(plain, null, "vrplain");
        prices.setOtherVenueLive(plain, true);
        long end = f.stuck().endMs();

        scheduler.tick(end);
        scheduler.tick(end + TWO_MINUTES);
        scheduler.tick(end + FIVE_MINUTES - 1);

        assertThat(roundResults.exists(f.stuck().roundId())).isFalse();
        assertThat(prices.otherVenueLiveAsked()).as("a market with one exchange has no other one to ask about").isEmpty();
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("300.000");

        scheduler.tick(end + FIVE_MINUTES); // its own five minutes (Р-79), as on every market

        assertThat(cancelled(f.stuck().roundId()).cancellation().reason()).isEqualTo(SOURCE_REASON);
        assertThat(venueColumn(f.stuck().roundId())).isNull();
        assertThat(prices.otherVenueLiveAsked()).isEmpty();
        assertThat(gameService.account(f.player()).balance()).isEqualTo("10000.000");
    }

    @Test
    void aQuestionThatCannotBeAnsweredLeavesTheRoundWaitingAndDoesNotStopTheTick() {
        Fixture f = marketWithAStuckRound(listBackupMarket("T_VR_BROKEN", "VRX"), "coinbase", "vrbroken");
        prices.failOtherVenueLive(f.market(), new IllegalStateException("the table of decisions does not answer"));
        long end = f.stuck().endMs();
        scheduler.tick(end);

        assertThatCode(() -> scheduler.tick(end + TWO_MINUTES)).doesNotThrowAnyException();
        assertThatCode(() -> scheduler.tick(end + FIVE_MINUTES - 1)).doesNotThrowAnyException();

        assertThat(prices.otherVenueLiveAsked()).contains(f.stuck().roundId());
        assertThat(roundResults.exists(f.stuck().roundId())).isFalse();
        assertThat(roundProgress.next(f.market().id(), HOURLY)).isEqualTo(f.stuck());

        scheduler.tick(end + FIVE_MINUTES);

        assertThat(cancelled(f.stuck().roundId()).cancellation().reason()).as("the minutes of the source do not depend on it")
                .isEqualTo(SOURCE_REASON);
    }
}
