package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import crowdvsmarket.engine.ledger.LedgerBook;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.prices.RoundPriceData;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Р-23 — "если за 1 час цену получить не удалось — раунд отменяется целиком" — for every way a
 * settlement can fail, not only for {@code PriceUnavailableException} (review of the multi-market
 * core, finding 3). The scheduler used to retry any other exception every second for ever and never
 * refund the bets of that window: an HTTP error out of the price source, a price the engine cannot take
 * (a non-positive one), a settlement transaction that kept failing. Now every failed attempt counts;
 * an hour after the window's end the round is cancelled with a refund. And what the source hands over
 * is checked BEFORE the engine lock is taken and anything is written — an open or close that is not
 * positive, a path of more than 30 candles — and a violation is "no usable price": it used to reach the
 * settlement transaction, fail there and get the whole engine rebuilt from the log, once a second.
 *
 * <p>The hour is for the failures that are not "no price". A round without a usable price is let go after the
 * minutes of its source, with the source's words (Р-79) — five for the synthetic feed of these markets — so the
 * tests of "no usable price" below pin that: not a millisecond before the five minutes, the refund at them.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class SettlementFailureRefundTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;
    private static final long HOUR = 3_600_000L;
    /** The wait of the synthetic feed that prices the markets listed here (Р-79). */
    private static final long FIVE_MINUTES = 300_000L;
    private static final String SETTLEMENT_FAILED = "settlement failed within 1 hour of round close";
    private static final String NO_PRICE_FROM_THE_SOURCE = "No price from the source; stakes returned";

    @InjectSpy
    OracleRecordRepository oracleRecords;

    @AfterEach
    void healTheSpy() {
        Mockito.reset(oracleRecords);
    }

    private record Fixture(Market market, RoundWindow running, RoundWindow betting, String player) {
    }

    private long count(String sql, Object... params) {
        return db.read(c -> db.queryOne(c, sql, rs -> rs.getLong("n"), params));
    }

    /** A market, a 300 PLAY bet on the window open for betting, and the running window. */
    private Fixture marketWithABet(String id, String symbol) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, symbol, 10, 0);
        prices.setPrice(market, "100.00");
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp(symbol.toLowerCase());
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 2, "300.000"), betting.startMs() - 1);
        return new Fixture(market, running, betting, player);
    }

    private void assertRefundedOnceAnHourAfterTheEnd(Fixture f, String reason) {
        assertRefundedExactlyAt(f, f.betting().endMs() + HOUR, reason);
    }

    /** Not cancelled a millisecond before {@code atMs}, the stake still in play; cancelled with {@code reason} and refunded at it. */
    private void assertRefundedExactlyAt(Fixture f, long atMs, String reason) {
        scheduler.tick(atMs - 1);
        assertThat(roundResults.exists(f.betting().roundId())).as("not cancelled before the time is over").isFalse();
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("300.000");

        scheduler.tick(atMs);

        Rounds.RoundDto dto = roundResults.findRoundDto(f.betting().roundId()).orElseThrow();
        assertThat(dto).isInstanceOf(Rounds.RoundCancelled.class);
        assertThat(((Rounds.RoundCancelled) dto).cancellation().reason()).isEqualTo(reason);
        assertThat(((Rounds.RoundCancelled) dto).cancellation().refundedBetCount()).isEqualTo(1);
        assertThat(gameService.account(f.player()).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("0.000");
        assertThat(roundProgress.next(f.market().id(), HOURLY).startMs()).isGreaterThanOrEqualTo(f.betting().endMs());
        assertThat(rebuild.isReady()).isTrue();
    }

    @Test
    void anyFailureOfThePriceSourceIsRetriedAndRefundedAnHourAfterTheWindowEnds() {
        Fixture f = marketWithABet("T_HTTP502", "H502");
        prices.failAllResolves(f.market(), new IllegalStateException("HTTP 502 from the price source"));
        scheduler.tick(f.running().endMs());      // the running (empty) window ends: its price cannot be read either
        scheduler.tick(f.betting().endMs() + 1);  // the window with the bet ends

        assertThat(roundResults.exists(f.betting().roundId())).isFalse();
        assertThat(roundProgress.next(f.market().id(), HOURLY).startMs()).as("the series waits").isLessThanOrEqualTo(
                f.betting().startMs());

        scheduler.tick(f.betting().endMs() + FIVE_MINUTES);   // not "no price": the minutes of the source are not for it
        assertThat(roundResults.exists(f.betting().roundId())).as("an hour, not five minutes").isFalse();
        assertRefundedOnceAnHourAfterTheEnd(f, SETTLEMENT_FAILED);
    }

    @Test
    void aSettlementTransactionThatKeepsFailingIsRefundedAnHourAfterTheWindowEnds() throws Exception {
        Fixture f = marketWithABet("T_TXFAIL", "TXF");
        scheduler.tick(f.running().endMs());
        prices.script(f.market(), f.betting(), "100.00", "110.00", "100.00", "110.00");
        doThrow(new IllegalStateException("injected failure after the event append")).when(oracleRecords)
                .insert(any(Connection.class), any(OracleRecordRepository.NewRecord.class));

        scheduler.tick(f.betting().endMs() + 1);   // fails inside the transaction; the engine is rebuilt behind it
        scheduler.tick(f.betting().endMs() + 2);   // ... and again

        assertThat(rebuild.isReady()).as("every failure was recovered from").isTrue();
        assertThat(roundResults.exists(f.betting().roundId())).isFalse();
        assertThat(gameService.account(f.player()).balance()).isEqualTo("9700.000");
        assertThat(gameService.hasPendingBets(f.betting())).isTrue();
        assertRefundedOnceAnHourAfterTheEnd(f, SETTLEMENT_FAILED);
        assertThat(count("SELECT COUNT(*) AS n FROM oracle_records WHERE round_id = ?", f.betting().roundId()))
                .isZero();
    }

    // ------------------------------------------------------------------------------------------
    // What the source hands over is checked before anything is locked or written
    // ------------------------------------------------------------------------------------------

    private static Candle candle(RoundWindow window, int i, String price) {
        BigDecimal p = new BigDecimal(price);
        return new Candle(window.startMs() + i, window.startMs() + i, p, p, p, p, BigDecimal.ZERO, 0);
    }

    /**
     * Data of the source that cannot be used is "no price": nothing is written for the window and the engine is not rebuilt behind
     * it, the round waits, and when the minutes of its source are over — to the millisecond — it is cancelled with the source's
     * words and every stake is refunded (Р-79).
     */
    private void assertUnusableDataIsNoPriceAndLetGoAfterTheMinutes(String id, String symbol,
                                                                    Function<RoundWindow, RoundPriceData> odd) {
        Fixture f = marketWithABet(id, symbol);
        scheduler.tick(f.running().endMs());
        prices.scriptData(f.market(), f.betting(), odd.apply(f.betting()));
        LedgerBook ledgerBefore = hub.ledger();

        scheduler.tick(f.betting().endMs() + 1);
        scheduler.tick(f.betting().endMs() + 2);

        assertThat(roundResults.exists(f.betting().roundId())).isFalse();
        assertThat(count("SELECT COUNT(*) AS n FROM game_events WHERE payload->>'market' = ? "
                + "AND payload->>'interval' = 'HOURLY' AND (payload->>'windowStart')::bigint = ? "
                + "AND event_type IN ('RoundResolved', 'RoundCancelled')", f.market().id(), f.betting().startMs()))
                .as("nothing was written for the window: no event for the unusable data").isZero();
        assertThat(hub.ledger()).as("and the engine was not rebuilt behind a failed transaction")
                .isSameAs(ledgerBefore);
        assertThat(gameService.hasPendingBets(f.betting())).isTrue();
        // it is "no price": the minutes of its source with the source's words, not the hour
        assertRefundedExactlyAt(f, f.betting().endMs() + FIVE_MINUTES, NO_PRICE_FROM_THE_SOURCE);
    }

    @Test
    void anOpenThatIsNotPositiveIsNoPrice() {
        assertUnusableDataIsNoPriceAndLetGoAfterTheMinutes("T_BADOPEN", "BOP", w -> new RoundPriceData(new BigDecimal("0.00"),
                new BigDecimal("101.00"), List.of(candle(w, 0, "101.00")), w.endMs() - 1));
    }

    @Test
    void aCloseThatRoundsToZeroAtTheEnginesScaleIsNoPrice() {
        assertUnusableDataIsNoPriceAndLetGoAfterTheMinutes("T_BADCLOSE", "BCL", w -> new RoundPriceData(new BigDecimal("100.00"),
                new BigDecimal("0.004"), List.of(candle(w, 0, "100.00")), w.endMs() - 1));
    }

    @Test
    void aPathOfMoreThanThirtyCandlesIsNoPrice() {
        assertUnusableDataIsNoPriceAndLetGoAfterTheMinutes("T_LONGPATH", "LNG", w -> {
            List<Candle> path = new ArrayList<>();
            for (int i = 0; i < 31; i++) {
                path.add(candle(w, i, "100.00"));
            }
            return new RoundPriceData(new BigDecimal("100.00"), new BigDecimal("100.00"), path, w.endMs() - 1);
        });
    }

    @Test
    void aPathOfExactlyThirtyCandlesIsSettled() {
        Fixture f = marketWithABet("T_THIRTY", "THI");
        scheduler.tick(f.running().endMs());
        List<Candle> path = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            path.add(candle(f.betting(), i, "100.00"));
        }
        prices.scriptData(f.market(), f.betting(), new RoundPriceData(new BigDecimal("100.00"),
                new BigDecimal("100.00"), path, f.betting().endMs() - 1));

        scheduler.tick(f.betting().endMs() + 1);

        assertThat(roundResults.findRoundDto(f.betting().roundId()).orElseThrow())
                .isInstanceOf(Rounds.RoundSettled.class);
        assertThat(gameService.account(f.player()).balance()).isEqualTo("10000.000");
    }
}
