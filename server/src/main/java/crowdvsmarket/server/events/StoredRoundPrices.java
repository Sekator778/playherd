package crowdvsmarket.server.events;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.RoundPrices;
import crowdvsmarket.engine.round.RoundWindow;
import java.math.BigDecimal;
import java.util.List;

/**
 * A {@link RoundPrices} that ignores the {@link RoundWindow} argument entirely and always returns
 * the fixed open/close/path it was built with — used only to replay a {@code RoundResolved} event
 * through {@code GameEngine.runRound} exactly as it happened live, independent of whatever price
 * source (replay-clock or live-tick-buffer) originally produced that path.
 */
public final class StoredRoundPrices implements RoundPrices {

    private final BigDecimal openPrice;
    private final BigDecimal closePrice;
    private final List<Candle> path;

    public StoredRoundPrices(BigDecimal openPrice, BigDecimal closePrice, List<Candle> path) {
        this.openPrice = openPrice;
        this.closePrice = closePrice;
        this.path = path;
    }

    @Override
    public BigDecimal openPrice(RoundWindow window) {
        return openPrice;
    }

    @Override
    public BigDecimal closePrice(RoundWindow window) {
        return closePrice;
    }

    @Override
    public List<Candle> path(RoundWindow window) {
        return path;
    }
}
