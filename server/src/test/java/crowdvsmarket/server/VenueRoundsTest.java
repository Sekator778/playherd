package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundClock;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.prices.RoundPriceData;
import crowdvsmarket.server.prices.RoundVenues;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.util.ResultsHasher;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * D-76 in the core — "a round runs entirely on one exchange" — as it is settled, stored and shown, over the real
 * database: a market that has a backup exchange settles a round from the exchange the round was decided on, and says which
 * in the result row ({@code round_results.venue}), in the canonical results JSON that is hashed and anchored on chain
 * (root key {@code "venue"}), in the {@code RoundResolved} event the engine replays, in the oracle journal and in the
 * {@code source} of the round's DTOs. Any other market is settled exactly as it always was. What decides the exchange
 * (the tick rings) is tested in {@code LiveMarketPricesVenuesTest}; here the prices are scripted with the venue the
 * decision gave them, and a "restart" is {@code rebuild(now)}.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class VenueRoundsTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;
    private static final Interval DAILY = Interval.DAILY;
    private static final long MINUTE = 60_000L;

    @Inject
    RoundVenues venues;
    @Inject
    OracleRecordRepository oracleRecords;

    /** A market that has Kraken as its backup, its daily series parked so that only the hourly one is in play. */
    private Market backupMarket(String id, String symbol) {
        Market market = listWithBackup(id, symbol, "kraken", symbol + "/USD");
        prices.setPrice(market, "100.00");
        parkTheDailySeries(market);
        return market;
    }

    private void parkTheDailySeries(Market market) {
        roundProgress.seed(market.id(), DAILY,
                calendar.windowContaining(market.id(), DAILY, System.currentTimeMillis() + 10 * 86_400_000L));
    }

    private static RoundPriceData data(Market market, RoundWindow window, String open, String close, String venue) {
        BigDecimal o = market.toEngine(new BigDecimal(open));
        BigDecimal c = market.toEngine(new BigDecimal(close));
        Candle candle = new Candle(window.startMs(), window.endMs() - 1, o, o.max(c), o.min(c), c, BigDecimal.ZERO, 0);
        return new RoundPriceData(o, c, List.of(candle), window.endMs() - 1, venue);
    }

    private Rounds.RoundSettled settled(String roundId) {
        Rounds.RoundDto dto = roundResults.findRoundDto(roundId).orElseThrow();
        assertThat(dto).as(roundId).isInstanceOf(Rounds.RoundSettled.class);
        return (Rounds.RoundSettled) dto;
    }

    private String venueColumn(String roundId) {
        return db.read(c -> db.queryOne(c, "SELECT venue FROM round_results WHERE round_id = ?",
                rs -> rs.getString("venue"), roundId));
    }

    private long openCents(String roundId) {
        return db.read(c -> db.queryOne(c, "SELECT open_price_cents FROM round_results WHERE round_id = ?",
                rs -> rs.getLong("open_price_cents"), roundId));
    }

    private String eventVenue(Market market, RoundWindow window) {
        return db.read(c -> db.queryOne(c, "SELECT payload->>'venue' AS v FROM game_events WHERE event_type = "
                + "'RoundResolved' AND payload->>'market' = ? AND payload->>'interval' = ? "
                + "AND (payload->>'windowStart')::bigint = ?", rs -> rs.getString("v"), market.id(),
                window.interval().name(), window.startMs()));
    }

    private boolean eventHasVenueKey(Market market, RoundWindow window) {
        return db.read(c -> db.queryOne(c, "SELECT jsonb_exists(payload, 'venue') AS has FROM game_events WHERE "
                + "event_type = 'RoundResolved' AND payload->>'market' = ? AND payload->>'interval' = ? "
                + "AND (payload->>'windowStart')::bigint = ?", rs -> rs.getBoolean("has"), market.id(),
                window.interval().name(), window.startMs()));
    }

    private OracleRecordRepository.JournalRow journalRow(String roundId) {
        return oracleRecords.journal(0, 100_000).rows().stream().filter(r -> r.roundId().equals(roundId)).findFirst()
                .orElseThrow();
    }

    // ------------------------------------------------------------------------------------------
    // A round that ran on an exchange says so, everywhere it is written
    // ------------------------------------------------------------------------------------------

    @Test
    void aSettledRoundSaysWhichExchangeItRanOnInEveryPlaceItIsWritten() {
        Market market = backupMarket("T_VN_SET", "VNS");
        prices.setVenue(market, "kraken");
        RoundWindow w0 = roundProgress.next(market.id(), HOURLY);
        RoundWindow w1 = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp("vnset");
        gameService.placeBet(player, bet(w1, Enums.Direction.UP, 5, "100.000"), w1.startMs() - 1);
        prices.script(market, w0, "100.00", "105.00", "100.00", "105.00");
        prices.script(market, w1, "105.00", "110.00", "105.00", "110.00");
        scheduler.tick(w0.endMs());
        scheduler.tick(w1.endMs());

        Rounds.RoundSettled round = settled(w1.roundId());
        assertThat(market.source()).as("the market's own exchange is Coinbase").isEqualTo("coinbase");
        assertThat(round.source()).as("the round's DTO names the exchange it ran on").isEqualTo("kraken");
        assertThat(venueColumn(w1.roundId())).isEqualTo("kraken");
        assertThat(eventVenue(market, w1)).as("the event the engine replays").isEqualTo("kraken");

        String json = roundResults.canonicalJson(w1.roundId()).orElseThrow();
        assertThat(json).as("the root key venue sorts after status").endsWith(",\"status\":\"SETTLED\",\"venue\":\"kraken\"}");
        assertThat(roundResults.findRoundDto(w1.roundId()).orElseThrow()).isInstanceOfSatisfying(Rounds.RoundSettled.class,
                r -> assertThat(r.resultsHash()).as("the hash covers the venue").isEqualTo(ResultsHasher.sha256Hex(json)));

        OracleRecordRepository.JournalRow record = journalRow(w1.roundId());
        assertThat(record.venue()).isEqualTo("kraken");
        assertThat(record.priceCents()).as("the close on that exchange").isEqualTo(11_000L);
        assertThat(record.resultsHash()).isEqualTo(ResultsHasher.sha256Hex(json));
        assertThat(venueColumn(w0.roundId())).isEqualTo("kraken");
    }

    @Test
    void aMarketWithoutABackupIsSettledExactlyAsBefore() {
        Market market = list("T_VN_PLAIN", MarketKind.PERP, Category.CRYPTO, "VNP", 10, 0);
        prices.setPrice(market, "100.00");
        parkTheDailySeries(market);
        RoundWindow w0 = roundProgress.next(market.id(), HOURLY);
        prices.script(market, w0, "100.00", "105.00", "100.00", "105.00");
        scheduler.tick(w0.endMs());

        assertThat(settled(w0.roundId()).source()).isEqualTo("synthetic");
        assertThat(venueColumn(w0.roundId())).isNull();
        assertThat(eventHasVenueKey(market, w0)).as("the event has no venue key at all").isFalse();
        String json = roundResults.canonicalJson(w0.roundId()).orElseThrow();
        assertThat(json).doesNotContain("venue").endsWith("\"status\":\"SETTLED\"}");
        assertThat(journalRow(w0.roundId()).venue()).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // What the DTOs of a round that is not settled say
    // ------------------------------------------------------------------------------------------

    @Test
    void theSourceOfARoundIsTheExchangeItRunsOnOnceDecidedAndTheMarketsOwnUntilThen() {
        Market market = backupMarket("T_VN_DTO", "VND");
        String player = signUp("vndto");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        gameService.placeBet(player, bet(betting, Enums.Direction.DOWN, 3, "100.000"), betting.startMs() - 1);

        Rounds.RoundsPairDto undecided = gameService.roundsPair(Enums.Interval.HOURLY, market.id(), player);
        assertThat(((Rounds.RoundBetting) undecided.betting()).source()).isEqualTo("coinbase");
        assertThat(((Rounds.RoundRunning) undecided.running()).source()).isEqualTo("coinbase");

        prices.setVenue(market, "kraken");

        Rounds.RoundsPairDto decided = gameService.roundsPair(Enums.Interval.HOURLY, market.id(), player);
        assertThat(((Rounds.RoundBetting) decided.betting()).source()).isEqualTo("kraken");
        assertThat(((Rounds.RoundRunning) decided.running()).source()).isEqualTo("kraken");
        assertThat(((Rounds.RoundBetting) gameService.round(betting.roundId(), player)).source()).isEqualTo("kraken");
    }

    // ------------------------------------------------------------------------------------------
    // The scheduler opens the rounds that have started
    // ------------------------------------------------------------------------------------------

    @Test
    void everySchedulerTickOpensTheRunningRoundsOfMarketsWithABackupAndOnlyThose() {
        Market backup = backupMarket("T_VN_TICK", "VNT");
        Market plain = list("T_VN_TICKPLAIN", MarketKind.PERP, Category.CRYPTO, "VTP", 10, 0);
        prices.setPrice(plain, "100.00");
        parkTheDailySeries(plain);
        RoundWindow running = roundProgress.next(backup.id(), HOURLY);

        scheduler.tick(running.startMs() + 1_000L);

        assertThat(prices.openedRounds()).contains(running.roundId()).noneMatch(id -> id.startsWith(plain.id()));
    }

    @Test
    void aMarketThatCannotBeOpenedDoesNotStopTheTickOrTheOtherMarkets() {
        Market broken = backupMarket("T_VN_BROKEN", "VNB");
        Market fine = backupMarket("T_VN_FINE", "VNF");
        RoundWindow brokenRunning = roundProgress.next(broken.id(), HOURLY);
        RoundWindow fineRunning = roundProgress.next(fine.id(), HOURLY);
        prices.failOpen(broken, new IllegalStateException("the database is down"));

        scheduler.tick(Math.max(brokenRunning.startMs(), fineRunning.startMs()) + 1_000L);

        assertThat(prices.openedRounds()).contains(brokenRunning.roundId(), fineRunning.roundId());
    }

    // ------------------------------------------------------------------------------------------
    // A cancelled round keeps the exchange it was decided on, if it was
    // ------------------------------------------------------------------------------------------

    @Test
    void aCancelledRoundThatHadAnExchangeKeepsItAndOneThatNeverGotOneHasNone() {
        Market market = backupMarket("T_VN_CANCEL", "VNC");
        RoundWindow undecided = roundProgress.next(market.id(), HOURLY);          // the running round, never decided
        RoundWindow decided = roundProgress.bettingWindow(market.id(), HOURLY);   // the next one, decided on Kraken
        venues.decide(decided.roundId(), market.id(), decided.startMs(), "kraken", decided.startMs());

        gameService.cancelRound(undecided, "operator: test", undecided.startMs() + 1_000L);
        gameService.cancelRound(decided, "operator: test", undecided.startMs() + 1_000L);

        assertThat(venueColumn(decided.roundId())).isEqualTo("kraken");
        assertThat(roundResults.canonicalJson(decided.roundId()).orElseThrow()).isEqualTo("{\"closePriceCents\":null,"
                + "\"crowd\":null,\"market\":\"T_VN_CANCEL\",\"openPriceCents\":null,\"positions\":[],\"roundId\":\""
                + decided.roundId() + "\",\"series\":\"HOURLY\",\"status\":\"CANCELLED\",\"venue\":\"kraken\"}");
        assertThat(((Rounds.RoundCancelled) roundResults.findRoundDto(decided.roundId()).orElseThrow()).source())
                .isEqualTo("kraken");

        assertThat(venueColumn(undecided.roundId())).isNull();
        assertThat(roundResults.canonicalJson(undecided.roundId()).orElseThrow()).doesNotContain("venue");
        assertThat(((Rounds.RoundCancelled) roundResults.findRoundDto(undecided.roundId()).orElseThrow()).source())
                .isEqualTo("coinbase");
    }

    @Test
    void aRoundThatCannotBePricedOnItsExchangeIsRefundedAfterTheMinutesOfThatExchangeAndKeepsIt() {
        Market market = backupMarket("T_VN_REFUND", "VNR");
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp("vnrefund");
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 2, "300.000"), betting.startMs() - 1);
        venues.decide(betting.roundId(), market.id(), betting.startMs(), "kraken", betting.startMs());
        prices.setVenue(market, "kraken"); // what the pipeline answers for a round decided on Kraken: its exchange, and so its minutes
        // Kraken cannot price it, and the round does not move to Coinbase: it waits for the five minutes of its exchange (D-79; the
        // other exchange is not alive here, so the two minutes of D-77 do not apply) and is then cancelled with a refund
        prices.failAllResolves(market, new crowdvsmarket.server.prices.PriceUnavailableException("no price on kraken"));
        scheduler.tick(running.endMs());
        scheduler.tick(betting.endMs() + 1);
        assertThat(roundResults.exists(betting.roundId())).isFalse();
        assertThat(gameService.account(player).inPlay()).isEqualTo("300.000");

        scheduler.tick(betting.endMs() + 5 * MINUTE - 1);
        assertThat(roundResults.exists(betting.roundId())).as("five minutes are not over a millisecond before").isFalse();
        assertThat(gameService.account(player).inPlay()).isEqualTo("300.000");
        scheduler.tick(betting.endMs() + 5 * MINUTE);

        Rounds.RoundCancelled cancelled = (Rounds.RoundCancelled) roundResults.findRoundDto(betting.roundId()).orElseThrow();
        assertThat(cancelled.cancellation().reason()).as("the words of the source's minutes, not of the hour")
                .isEqualTo("No price from the source; stakes returned");
        assertThat(cancelled.cancellation().cancelledAtMs()).isEqualTo(betting.endMs() + 5 * MINUTE);
        assertThat(cancelled.cancellation().refundedBetCount()).isEqualTo(1);
        assertThat(cancelled.source()).isEqualTo("kraken");
        assertThat(venueColumn(betting.roundId())).isEqualTo("kraken");
        assertThat(roundResults.canonicalJson(betting.roundId()).orElseThrow()).endsWith(",\"venue\":\"kraken\"}");
        assertThat(gameService.account(player).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(player).inPlay()).isEqualTo("0.000");
    }

    // ------------------------------------------------------------------------------------------
    // D-40 within an exchange, and across a switch
    // ------------------------------------------------------------------------------------------

    @Test
    void aRoundOpensAtTheClosedPriceOfItsOwnExchangeAcrossASwitchAndARestart() {
        Market market = backupMarket("T_VN_SWITCH", "VNW");
        RoundWindow w0 = roundProgress.next(market.id(), HOURLY);
        RoundWindow w1 = RoundClock.next(w0);
        RoundWindow w2 = RoundClock.next(w1);
        RoundWindow w3 = RoundClock.next(w2);
        // Coinbase closes w0 at 105.00; w1 runs on Kraken, whose price at that boundary was 105.30; w2 stays on Kraken
        // and the pipeline would now open it at 106.40; w3 is back on Coinbase at 107.20
        prices.scriptData(market, w0, data(market, w0, "100.00", "105.00", "coinbase"));
        prices.scriptData(market, w1, data(market, w1, "105.30", "106.00", "kraken"));
        prices.scriptData(market, w2, data(market, w2, "106.40", "107.00", "kraken"));
        prices.scriptData(market, w3, data(market, w3, "107.20", "108.00", "coinbase"));

        scheduler.tick(w0.endMs());
        scheduler.tick(w1.endMs());
        rebuild.rebuild(w1.endMs());   // a restart: the closes are read back from round_results, with their exchanges
        parkTheDailySeries(market);
        scheduler.tick(w2.endMs());
        scheduler.tick(w3.endMs());

        assertThat(settled(w0.roundId()).closePrice()).isEqualTo("105.00");
        assertThat(venueColumn(w0.roundId())).isEqualTo("coinbase");
        assertThat(openCents(w1.roundId())).as("Kraken's own price at the boundary, not Coinbase's close of 105.00")
                .isEqualTo(10_530L);
        assertThat(venueColumn(w1.roundId())).isEqualTo("kraken");
        assertThat(openCents(w2.roundId())).as("within Kraken: the close of w1, not the pipeline's 106.40 (D-40)")
                .isEqualTo(10_600L);
        assertThat(venueColumn(w2.roundId())).isEqualTo("kraken");
        assertThat(openCents(w3.roundId())).as("back on Coinbase, whose last close was not at this boundary")
                .isEqualTo(10_720L);
        assertThat(venueColumn(w3.roundId())).isEqualTo("coinbase");
        assertThat(eventVenue(market, w2)).isEqualTo("kraken");
        assertThat(journalRow(w3.roundId()).venue()).isEqualTo("coinbase");
    }

    @Test
    void theRunningRoundOfAMarketWithABackupShowsTheOpenOfItsOwnExchange() {
        Market market = backupMarket("T_VN_RUN", "VNN");
        RoundWindow w0 = roundProgress.next(market.id(), HOURLY);
        RoundWindow w1 = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp("vnrun");
        gameService.placeBet(player, bet(w1, Enums.Direction.UP, 5, "100.000"), w1.startMs() - 1);
        prices.scriptData(market, w0, data(market, w0, "100.00", "105.00", "coinbase"));
        prices.setVenue(market, "coinbase");
        scheduler.tick(w0.endMs());   // w1 is the running round now, and w0 closed on Coinbase at 105.00

        prices.setVenue(market, "kraken");
        prices.setSoFar(market, "105.30", "105.40");
        Rounds.RoundRunning onKraken = (Rounds.RoundRunning) gameService.round(w1.roundId(), player);
        assertThat(onKraken.source()).isEqualTo("kraken");
        assertThat(onKraken.openPrice()).as("Coinbase's close is not a Kraken round's open: the pipeline's own")
                .isEqualTo("105.30");

        prices.setVenue(market, "coinbase");
        prices.setSoFar(market, "105.20", "105.40");
        Rounds.RoundRunning onCoinbase = (Rounds.RoundRunning) gameService.round(w1.roundId(), player);
        assertThat(onCoinbase.source()).isEqualTo("coinbase");
        assertThat(onCoinbase.openPrice()).as("within Coinbase: the close before it").isEqualTo("105.00");
    }
}
