package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.chain.OracleSeqGate;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

/**
 * The scheduler asks the {@link OracleSeqGate} before it settles or cancels anything (docs/research/
 * markets_architecture.md §7 п.4): a settlement reserves a {@code seq}, and one handed out below what is
 * already on chain is a record rejected for good, so while the oracle's sequence floor has not been
 * obtained a tick does nothing at all — however late the round is — and says so once a minute.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class OracleGateSchedulerTest extends LiveGameTestBase {

    private static final long HOUR = 3_600_000L;

    @InjectMock
    OracleSeqGate gate;

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
    void whileTheGateIsClosedATickSettlesAndCancelsNothingAndOnceItIsOpenItDoes() {
        Market market = list("T_GATE", MarketKind.PERP, Category.CRYPTO, "GAT", 10, 0);
        prices.setPrice(market, "100.00");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));
        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        String p = signUp("gate");
        gameService.placeBet(p, bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);
        long seqBefore = oracleSeq();
        when(gate.open()).thenReturn(false);

        scheduler.tick(running.endMs());
        scheduler.tick(betting.endMs());
        scheduler.tick(betting.endMs() + HOUR);   // an hour late: still nothing settled, nothing cancelled

        verify(gate, atLeast(3)).open();
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
