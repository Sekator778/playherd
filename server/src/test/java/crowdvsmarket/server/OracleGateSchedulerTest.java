package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotService;
import crowdvsmarket.server.chain.OracleSeqGate;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * The scheduler asks the {@link OracleSeqGate} around the settlement itself and nowhere else (docs/research/
 * markets_architecture.md §7 item 4, §4 item 11; review of the core, finding 5): a settlement reserves a {@code seq}, and
 * one handed out below what is already on chain is a record rejected for good, so while the oracle's sequence floor
 * has not been obtained no round is settled — however late. Everything that reserves no {@code seq} goes on: the
 * catch-up jump over empty overdue windows (no event, no record), the refund of a round that is an hour late (a
 * result row and an event, no record), the seeding of a series that has no cursor, the bots, the publishing of what
 * is queued. The whole tick used to wait for the gate: after a restart with some downtime no series had a round open
 * for betting until the chain service answered. The closed gate is said once a minute.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class OracleGateSchedulerTest extends LiveGameTestBase {

    private static final long HOUR = 3_600_000L;
    private static final long STEP = 60_000L;

    @InjectMock
    OracleSeqGate gate;
    @InjectSpy
    BotService botService;

    /** The last seq handed out; 0 while none ever was (the row does not exist yet). */
    private long oracleSeq() {
        String value = db.read(c -> db.queryOne(c, "SELECT value FROM server_meta WHERE key = 'oracle_seq'",
                rs -> rs.getString("value")));
        return value == null ? 0L : Long.parseLong(value);
    }

    /** Collects what the scheduler logs at WARN. */
    private static final class Warnings extends Handler {
        final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                messages.add(record.getMessage());
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Test
    void whileTheGateIsClosedNoRoundIsSettledAndNoSeqIsHandedOutAndOnceItIsOpenTheRoundsAreSettled() {
        Market market = list("T_GATE", MarketKind.PERP, Category.CRYPTO, "GAT", 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String p = signUp("gate");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);
        long seqBefore = oracleSeq();
        when(gate.open()).thenReturn(false);

        scheduler.tick(running.endMs());
        scheduler.tick(betting.endMs());

        verify(gate, atLeast(2)).open();
        assertThat(roundResults.exists(running.roundId())).isFalse();
        assertThat(roundResults.exists(betting.roundId())).isFalse();
        assertThat(roundProgress.next(market.id(), Interval.HOURLY)).isEqualTo(running);
        assertThat(gameService.account(p).inPlay()).isEqualTo("100.000");
        assertThat(oracleSeq()).as("no seq was handed out").isEqualTo(seqBefore);

        when(gate.open()).thenReturn(true);
        scheduler.tick(running.endMs());

        assertThat(roundResults.exists(running.roundId())).isTrue();
        assertThat(roundProgress.next(market.id(), Interval.HOURLY)).isEqualTo(betting);
    }

    @Test
    void aRoundThatIsAnHourLateIsRefundedEvenWithTheGateClosedAndWritesNoOracleRecord() {
        Market market = list("T_GATEREFUND", MarketKind.PERP, Category.CRYPTO, "GRF", 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String p = signUp("gaterefund");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);
        long seqBefore = oracleSeq();
        when(gate.open()).thenReturn(false);
        scheduler.tick(running.endMs());
        scheduler.tick(betting.endMs());
        assertThat(roundResults.exists(betting.roundId())).as("an hour has not passed: it waits for the gate").isFalse();

        scheduler.tick(betting.endMs() + HOUR - 1);
        assertThat(roundResults.exists(betting.roundId())).as("one millisecond short of the hour").isFalse();
        scheduler.tick(betting.endMs() + HOUR);   // an hour late

        Rounds.RoundDto dto = roundResults.findRoundDto(betting.roundId()).orElseThrow();
        assertThat(dto).isInstanceOf(Rounds.RoundCancelled.class);
        Rounds.CancellationDto cancellation = ((Rounds.RoundCancelled) dto).cancellation();
        assertThat(cancellation.reason()).isEqualTo("settlement failed within 1 hour of round close");
        assertThat(cancellation.refundedBetCount()).isEqualTo(1);
        assertThat(gameService.account(p).balance()).isEqualTo("10000.000");
        assertThat(gameService.account(p).inPlay()).isEqualTo("0.000");
        assertThat(oracleSeq()).as("a refund hands out no seq").isEqualTo(seqBefore);
        assertThat(oracleRowCount(betting.roundId())).as("and writes no oracle record").isZero();
    }

    @Test
    void theCatchUpJumpOverEmptyWindowsAndTheSeedingOfSeriesAndTheBotsGoOnWithTheGateClosed() {
        Market market = list("T_GATEJUMP", MarketKind.PERP, Category.CRYPTO, "GJP", 10, 0);
        prices.setPrice(market, "100.00");
        RoundWindow first = roundProgress.next(market.id(), Interval.HOURLY);
        String p = signUp("gatejump");
        long seqBefore = oracleSeq();
        when(gate.open()).thenReturn(false);
        long restartAt = first.endMs() + 6 * STEP;   // the series is six windows behind the clock, all of them empty

        scheduler.tick(restartAt);

        // it jumped over the empty windows that are overdue by more than a step (no event, no result, no seq); the
        // window that ended a step ago is a settlement like any other, and has to wait for the gate
        RoundWindow justEnded = calendar.windowContaining(market.id(), Interval.HOURLY, restartAt - STEP - 1);
        assertThat(roundProgress.next(market.id(), Interval.HOURLY)).as("six windows behind before, one now")
                .isEqualTo(justEnded);
        assertThat(roundResults.exists(first.roundId())).as("no result for the windows it skipped").isFalse();
        assertThat(roundResults.exists(justEnded.roundId())).as("and none for the one that waits for the gate").isFalse();
        assertThat(oracleSeq()).isEqualTo(seqBefore);
        verify(botService, atLeast(1)).tick(restartAt);
        // ... and betting in the series stays closed until the gate opens: the window that waits for it is one whose
        // betting window has started already (the known remainder, markets_architecture.md §4 item 11)
        assertThatThrownBy(() -> gameService.placeBet(p, bet(roundProgress.bettingWindow(market.id(), Interval.HOURLY),
                Enums.Direction.UP, 3, "100.000"), restartAt))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ROUND_NOT_OPEN_FOR_BETTING));

        // a market that lost its cursor gets one again, gate or not
        RoundWindow other = roundProgress.next(market.id(), Interval.DAILY);
        roundProgress.remove(market.id(), Interval.DAILY);
        scheduler.tick(restartAt);
        assertThat(roundProgress.isSeeded(market.id(), Interval.DAILY)).isTrue();
        assertThat(roundProgress.next(market.id(), Interval.DAILY).startMs()).isGreaterThanOrEqualTo(other.startMs());

        // the gate opens: the waiting window is settled, the series is at the window running now, betting is open
        when(gate.open()).thenReturn(true);
        scheduler.tick(restartAt);
        RoundWindow running = calendar.windowContaining(market.id(), Interval.HOURLY, restartAt);
        assertThat(roundResults.exists(justEnded.roundId())).isTrue();
        assertThat(roundProgress.next(market.id(), Interval.HOURLY)).isEqualTo(running);
        gameService.placeBet(p, bet(roundProgress.bettingWindow(market.id(), Interval.HOURLY), Enums.Direction.UP, 3,
                "100.000"), restartAt);
        assertThat(oracleSeq()).as("the settlements that waited reserved their seqs once it was open")
                .isGreaterThan(seqBefore);
    }

    @Test
    void theClosedGateIsLoggedOnceAMinute() {
        java.util.logging.Logger logger = java.util.logging.Logger
                .getLogger("crowdvsmarket.server.scheduler.RoundSchedulerService");
        Warnings warnings = new Warnings();
        logger.addHandler(warnings);
        try {
            when(gate.open()).thenReturn(false);
            long t = 10_000_000_000L;

            scheduler.tick(t);
            scheduler.tick(t + 1_000L);
            scheduler.tick(t + 59_999L);
            assertThat(warnings.messages).as("one line for the first minute").hasSize(1);
            assertThat(warnings.messages.get(0)).contains("sequence floor");

            scheduler.tick(t + 60_000L);
            scheduler.tick(t + 61_000L);
            assertThat(warnings.messages).as("and the next one a minute later").hasSize(2);

            when(gate.open()).thenReturn(true);   // once it is open there is nothing left to say
            scheduler.tick(t + 200_000L);
            assertThat(warnings.messages).hasSize(2);
        } finally {
            logger.removeHandler(warnings);
        }
    }
}
