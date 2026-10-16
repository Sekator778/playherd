package crowdvsmarket.bots.port;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.round.Direction;
import java.util.List;
import java.util.Optional;

/**
 * What is on the markets, in terms of the public API. Today a server adapter over {@code GameService} implements it,
 * later a client of the public API. Implementations must pass {@code PortsContractTest}.
 */
public interface MarketDataPort {

    /** The current round of every market (open or not), one snapshot per market id. */
    List<MarketSnapshot> markets();

    /**
     * The last {@code minutes} finished 1-minute candles of a market, oldest first (at most {@code minutes}; fewer when
     * the history is short). An unknown market yields an empty list, never an exception.
     */
    List<BarDto> bars(String marketId, int minutes);

    /** The side the majority took in the previous round of the market; empty when unknown or tied. */
    Optional<Direction> previousMajority(String marketId);
}
