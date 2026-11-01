package crowdvsmarket.engine.execution;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Task item 7 (the project plan §3, README's Hyperliquid-adapter claim): {@link PaperTradingAdapter} must
 * be exactly {@link Position#openFromMargin} + {@link PositionSimulator#simulate} — the same two
 * calls {@code CrowdAccount}/{@code ComparisonAccounts} made directly before this task — so wiring
 * them through {@link ExecutionAdapter} is a byte-identical behavior change, not a new one.
 */
class PaperTradingAdapterTest {

    private static Candle candle(String open, String high, String low, String close) {
        return new Candle(0L, 3_600_000L - 1, new BigDecimal(open), new BigDecimal(high), new BigDecimal(low),
                new BigDecimal(close), BigDecimal.ZERO, 0L);
    }

    @Test
    void openPositionMatchesPositionOpenFromMarginExactly() {
        PaperTradingAdapter adapter = new PaperTradingAdapter();
        Position viaAdapter = adapter.openPosition("alice", "r1", Direction.UP, new Leverage(10),
                new BigDecimal("500.00"), new BigDecimal("50000.00"));
        Position direct = Position.openFromMargin("alice", "r1", Direction.UP, new Leverage(10),
                new BigDecimal("500.00"), new BigDecimal("50000.00"));

        assertThat(viaAdapter).isEqualTo(direct);
    }

    @Test
    void closePositionMatchesPositionSimulatorSimulateExactly() {
        PaperTradingAdapter adapter = new PaperTradingAdapter();
        Position position = Position.openFromMargin("alice", "r1", Direction.UP, new Leverage(10),
                new BigDecimal("500.00"), new BigDecimal("50000.00"));
        List<Candle> path = List.of(candle("50000.00", "50100.00", "49900.00", "50080.00"));

        PositionSimulator.Outcome viaAdapter = adapter.closePosition(position, path);
        PositionSimulator.Outcome direct = PositionSimulator.simulate(position, path);

        assertThat(viaAdapter).isEqualTo(direct);
    }

    @Test
    void getPositionIsAlwaysEmptyByDesign() {
        PaperTradingAdapter adapter = new PaperTradingAdapter();
        Position position = adapter.openPosition("alice", "r1", Direction.UP, new Leverage(10),
                new BigDecimal("500.00"), new BigDecimal("50000.00"));

        Optional<PositionSimulator.Outcome> status = adapter.getPosition(position);

        assertThat(status).isEmpty();
    }
}
