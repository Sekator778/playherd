package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.scheduler.OraclePublisherService;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A settlement that keeps failing is retried with a backoff, and only a failure that may have touched the engine
 * rebuilds it (review of the core, finding 3; docs/research/markets_architecture.md §4 п.13). Before: every tick
 * tried again, every failure after the event was applied ran a full rebuild from the log under the global write
 * lock — 5.6 µs per event plus 25 ms, so a lobby poll got 503 for 83 % of its calls at 1.8 k events and for 99 %
 * at 91 k — and each of them logged a stack trace.
 *
 * <p>Time is the test's: the scheduler is off and {@code scheduler.tick(now)} is called with chosen instants.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class SettlementBackoffSchedulerTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;
    private static final long HOUR = 3_600_000L;

    /** Silenced: after every tick it reads the spied repository on a thread of its own, and a stubbing that thread
     *  hits in its midst is taken by the wrong thread (Mockito's UnfinishedStubbingException, rarely, and then in every
     *  test after it). Nothing here is about what it publishes. */
    @InjectMock
    OraclePublisherService oraclePublisher;
    @InjectSpy
    OracleRecordRepository oracleRecords;
    @InjectSpy
    EventStore eventStoreSpy;
    @InjectSpy
    Db dbSpy;

    private final List<Market> listed = new ArrayList<>();

    @AfterEach
    void healAndCloseWhatThisTestListed() {
        Mockito.reset(oracleRecords, eventStoreSpy, dbSpy);
        rebuild.rebuild();
        for (Market market : listed) {
            if (registry.require(market.id()).isActive()) {
                registry.close(market.id(), "test over", System.currentTimeMillis());
            }
        }
        listed.clear();
    }

    private record Fixture(Market market, RoundWindow running, RoundWindow betting, String player) {
    }

    private long count(String sql, Object... params) {
        return db.read(c -> db.queryOne(c, sql, rs -> rs.getLong("n"), params));
    }

    /** A market whose HOURLY series is at the window containing now, a 300 PLAY bet on the window that follows
     *  and the empty running window already settled. (Its DAILY series runs too: only the window with the bet is
     *  made to fail, below, and a rebuild would put a parked cursor back at "now" anyway.) */
    private Fixture marketWithABetAndTheRunningWindowSettled(String id, String symbol) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, symbol, 10, 0);
        listed.add(market);
        prices.setPrice(market, "100.00");
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp(symbol.toLowerCase());
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 2, "300.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());
        prices.script(market, betting, "100.00", "110.00", "100.00", "110.00");
        return new Fixture(market, running, betting, player);
    }

    private long insertsFor(RoundWindow window) {
        return mockingDetails(oracleRecords).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("insert"))
                .filter(i -> ((OracleRecordRepository.NewRecord) i.getArgument(1)).roundId().equals(window.roundId()))
                .count();
    }

    /** The settlement of the window with the bet fails after its event was applied (the oracle insert throws); every
     *  other window of the game settles as usual. */
    private void theSettlementOfTheBetWindowFailsAfterItsEventWasApplied(Fixture f) throws Exception {
        doAnswer(inv -> {
            OracleRecordRepository.NewRecord record = inv.getArgument(1);
            if (record.roundId().equals(f.betting().roundId())) {
                throw new IllegalStateException("injected failure after the event append");
            }
            return inv.callRealMethod();
        }).when(oracleRecords).insert(any(Connection.class), any(OracleRecordRepository.NewRecord.class));
    }

    @Test
    void aSettlementThatKeepsFailingIsTriedAtTwoFourEightSecondsAndSoOnNotEveryTick() throws Exception {
        Fixture f = marketWithABetAndTheRunningWindowSettled("T_BO_EVERY", "BOE");
        theSettlementOfTheBetWindowFailsAfterItsEventWasApplied(f);
        Mockito.clearInvocations(oracleRecords, eventStoreSpy);
        long t0 = f.betting().endMs() + 1;

        for (long dt = 0; dt <= 30_000L; dt += 1_000L) {
            scheduler.tick(t0 + dt);
        }

        assertThat(insertsFor(f.betting())).as("attempts at +0, +2, +6, +14 and +30 s, not 31 of them").isEqualTo(5);
        verify(eventStoreSpy, Mockito.times(5)).loadAll(); // one rebuild per failed attempt: the failures touched memory
        assertThat(rebuild.isReady()).as("every failure was recovered from").isTrue();
        assertThat(roundResults.exists(f.betting().roundId())).isFalse();
        assertThat(gameService.account(f.player()).balance()).isEqualTo("9699.840");
        assertThat(gameService.hasPendingBets(f.betting())).isTrue();
    }

    @Test
    void theNextAttemptIsMadeWhenItIsDueAndSettlesTheRoundOnceTheCauseIsGone() throws Exception {
        Fixture f = marketWithABetAndTheRunningWindowSettled("T_BO_DUE", "BOD");
        theSettlementOfTheBetWindowFailsAfterItsEventWasApplied(f);
        long t0 = f.betting().endMs() + 1;
        scheduler.tick(t0);            // fails: the next attempt is due at +2 s
        Mockito.reset(oracleRecords);  // the cause is gone

        scheduler.tick(t0 + 1_999L);
        assertThat(roundResults.exists(f.betting().roundId())).as("not due yet: nothing is tried").isFalse();

        scheduler.tick(t0 + 2_000L);
        assertThat(roundResults.exists(f.betting().roundId())).as("due: settled").isTrue();
        assertThat(roundResults.findRoundDto(f.betting().roundId()).orElseThrow()).isInstanceOf(Rounds.RoundSettled.class);
        assertThat(gameService.account(f.player()).balance()).isEqualTo("10059.840"); // +20 % of 300 on a 2x long... paid once
        assertThat(count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'RoundResolved' "
                + "AND payload->>'market' = ? AND (payload->>'windowStart')::bigint = ?", f.market().id(),
                f.betting().startMs())).isEqualTo(1);
    }

    @Test
    void whatTheSchedulerHasForgottenIsTriedAtOnce() throws Exception {
        Fixture f = marketWithABetAndTheRunningWindowSettled("T_BO_FORGET", "BOF");
        theSettlementOfTheBetWindowFailsAfterItsEventWasApplied(f);
        long t0 = f.betting().endMs() + 1;
        scheduler.tick(t0);            // fails: the next attempt is due at +2 s
        scheduler.tick(t0 + 1_000L);
        assertThat(insertsFor(f.betting())).as("held back by the backoff").isEqualTo(1);

        scheduler.forgetFailedAttempts();
        scheduler.tick(t0 + 1_000L);

        assertThat(insertsFor(f.betting())).as("forgotten: tried again at once").isEqualTo(2);
    }

    @Test
    void theOneHourRefundIsNotDelayedByTheBackoffOfTheAttemptsBeforeIt() throws Exception {
        Fixture f = marketWithABetAndTheRunningWindowSettled("T_BO_REFUND", "BOR");
        theSettlementOfTheBetWindowFailsAfterItsEventWasApplied(f);
        long end = f.betting().endMs();
        scheduler.tick(end + 1);
        // enough failed attempts for the next one to be a minute away, the last one a second before the hour
        for (long at = end + 3_000L; at < end + HOUR - 1_000L; at += 60_000L) {
            scheduler.tick(at);
        }
        scheduler.tick(end + HOUR - 1_000L);
        assertThat(roundResults.exists(f.betting().roundId())).as("the hour is not over").isFalse();

        scheduler.tick(end + HOUR);   // the backoff says "wait ~60 s": the refund does not

        Rounds.RoundDto dto = roundResults.findRoundDto(f.betting().roundId()).orElseThrow();
        assertThat(dto).isInstanceOf(Rounds.RoundCancelled.class);
        assertThat(((Rounds.RoundCancelled) dto).cancellation().reason())
                .isEqualTo("settlement failed within 1 hour of round close");
        assertThat(gameService.account(f.player()).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("0.000");
    }

    @Test
    void aFailureThatCannotHaveTouchedMemoryRebuildsNothingAndTheEngineStaysReady() throws Exception {
        Fixture f = marketWithABetAndTheRunningWindowSettled("T_BO_UNTOUCHED", "BOU");
        var ledgerBefore = hub.ledger();
        doThrow(new IllegalStateException("db flapping while writing the event")).when(eventStoreSpy)
                .append(any(Connection.class), any(GameEvent.RoundResolved.class), anyLong());
        Mockito.clearInvocations(eventStoreSpy);
        long t0 = f.betting().endMs() + 1;

        scheduler.tick(t0);
        scheduler.tick(t0 + 2_000L);
        scheduler.tick(t0 + 6_000L);

        verify(eventStoreSpy, never()).loadAll();
        assertThat(rebuild.isReady()).as("nothing was applied, so there is nothing to be unsure of").isTrue();
        assertThat(hub.ledger()).as("and the engine was not rebuilt").isSameAs(ledgerBefore);
        assertThat(gameService.account(f.player()).balance()).as("the game goes on serving").isEqualTo("9699.840");
        assertThat(gameService.hasPendingBets(f.betting())).isTrue();

        Mockito.reset(eventStoreSpy);   // the database answers again
        scheduler.tick(t0 + 14_000L);

        assertThat(roundResults.exists(f.betting().roundId())).isTrue();
        assertThat(gameService.account(f.player()).balance()).isEqualTo("10059.840");
    }

    @Test
    void aDatabaseThatDoesNotGiveAConnectionRebuildsNothingEither() throws Exception {
        Fixture f = marketWithABetAndTheRunningWindowSettled("T_BO_NOCONN", "BON");
        var ledgerBefore = hub.ledger();
        Mockito.clearInvocations(eventStoreSpy);
        // every transaction fails before its work runs — what a database that is down looks like to the settlement
        doThrow(new RuntimeException("db connection failure")).when(dbSpy).tx(any());
        long t0 = f.betting().endMs() + 1;

        scheduler.tick(t0);
        Mockito.reset(dbSpy);

        verify(eventStoreSpy, never()).loadAll();
        assertThat(rebuild.isReady()).isTrue();
        assertThat(hub.ledger()).isSameAs(ledgerBefore);
        assertThat(roundResults.exists(f.betting().roundId())).isFalse();
        assertThat(gameService.hasPendingBets(f.betting())).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // The log: one stack trace per window, then one line a minute
    // ------------------------------------------------------------------------------------------

    private static final class Records extends Handler {
        final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        long withTrace() {
            return records.stream().filter(r -> r.getThrown() != null).count();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    void theRecoveryAndTheFailureAreLoggedWithOneStackTracePerWindowThenALinePerMinute() throws Exception {
        Fixture f = marketWithABetAndTheRunningWindowSettled("T_BO_LOG", "BOL");
        theSettlementOfTheBetWindowFailsAfterItsEventWasApplied(f);
        java.util.logging.Logger rebuildLog = java.util.logging.Logger
                .getLogger("crowdvsmarket.server.scheduler.EngineRebuildService");
        java.util.logging.Logger schedulerLog = java.util.logging.Logger
                .getLogger("crowdvsmarket.server.scheduler.RoundSchedulerService");
        Records recovery = new Records();
        Records failures = new Records();
        rebuildLog.addHandler(recovery);
        schedulerLog.addHandler(failures);
        long t0 = f.betting().endMs() + 1;
        try {
            // five minutes, a tick a second
            for (long dt = 0; dt <= 300_000L; dt += 1_000L) {
                scheduler.tick(t0 + dt);
            }
        } finally {
            rebuildLog.removeHandler(recovery);
            schedulerLog.removeHandler(failures);
        }

        assertThat(insertsFor(f.betting())).as("attempts at +0, +2, +6, +14, +30, +62, +122, +182 and +242 s")
                .isEqualTo(9);
        assertThat(recovery.withTrace()).as("the recovery: one stack trace for the window").isEqualTo(1);
        assertThat(recovery.records.size()).as("and a line a minute after it, not one per attempt: %s",
                recovery.records.size()).isBetween(2, 7);
        assertThat(failures.withTrace()).as("the settlement failure: one stack trace for the window").isEqualTo(1);
        assertThat(failures.records.size()).isBetween(2, 7);
    }
}
