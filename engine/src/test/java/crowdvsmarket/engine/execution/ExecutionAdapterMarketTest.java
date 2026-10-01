package crowdvsmarket.engine.execution;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The market-aware {@code openPosition(spec, …)} of {@link ExecutionAdapter} (Р-69) and its BTC
 * default: {@link PaperTradingAdapter} must still be exactly {@link Position#openFromMargin}.
 */
class ExecutionAdapterMarketTest {

    private static final MarketSpec ETH = MarketSpec.of("ETH", 25);
    private static final BigDecimal MARGIN = new BigDecimal("500.00");
    private static final BigDecimal ENTRY = new BigDecimal("2694.40");

    @Test
    void paperTradingOpensInTheGivenMarketExactlyLikeOpenFromMargin() {
        PaperTradingAdapter adapter = new PaperTradingAdapter();
        for (MarketSpec spec : List.of(MarketSpec.BTC, ETH, MarketSpec.of("SOL", 10))) {
            Leverage leverage = spec.leverage(10);

            Position viaAdapter = adapter.openPosition(spec, "alice", "r1", Direction.DOWN, leverage, MARGIN, ENTRY);

            assertThat(viaAdapter).as(spec.id()).isEqualTo(
                    Position.openFromMargin("alice", "r1", Direction.DOWN, leverage, MARGIN, ENTRY, spec));
        }
    }

    @Test
    void differentMarketsGiveDifferentLiquidationPricesForTheSameInputs() {
        PaperTradingAdapter adapter = new PaperTradingAdapter();
        Leverage leverage = new Leverage(10);

        Position btc = adapter.openPosition(MarketSpec.BTC, "alice", "r1", Direction.UP, leverage, MARGIN, ENTRY);
        Position eth = adapter.openPosition(ETH, "alice", "r1", Direction.UP, leverage, MARGIN, ENTRY);

        assertThat(eth.size()).isEqualTo(btc.size());
        assertThat(eth.liquidationPrice()).isGreaterThan(btc.liquidationPrice()); // l = 0.02 vs 0.0125, long
    }

    @Test
    void theSixArgumentOpenPositionIsTheBtcMarket() {
        ExecutionAdapter adapter = new PaperTradingAdapter();

        Position legacy = adapter.openPosition("alice", "r1", Direction.UP, new Leverage(10), MARGIN, ENTRY);

        assertThat(legacy).isEqualTo(adapter.openPosition(MarketSpec.BTC, "alice", "r1", Direction.UP, new Leverage(10), MARGIN, ENTRY));
        assertThat(legacy).isEqualTo(Position.openFromMargin("alice", "r1", Direction.UP, new Leverage(10), MARGIN, ENTRY));
    }

    @Test
    void anAdapterThatImplementsOnlyTheMarketMethodGetsTheBtcDefault() {
        List<MarketSpec> seen = new ArrayList<>();
        ExecutionAdapter adapter = new ExecutionAdapter() {
            @Override
            public Position openPosition(MarketSpec spec, String userId, String roundId, Direction direction,
                    Leverage leverage, BigDecimal margin, BigDecimal entryPrice) {
                seen.add(spec);
                return Position.openFromMargin(userId, roundId, direction, leverage, margin, entryPrice, spec);
            }

            @Override
            public Optional<PositionSimulator.Outcome> getPosition(Position position) {
                return Optional.empty();
            }

            @Override
            public PositionSimulator.Outcome closePosition(Position position, List<Candle> priceHistorySinceOpen) {
                return PositionSimulator.simulate(position, priceHistorySinceOpen);
            }
        };

        adapter.openPosition("alice", "r1", Direction.UP, new Leverage(10), MARGIN, ENTRY);
        adapter.openPosition(ETH, "alice", "r1", Direction.UP, new Leverage(10), MARGIN, ENTRY);

        assertThat(seen).containsExactly(MarketSpec.BTC, ETH);
    }
}
