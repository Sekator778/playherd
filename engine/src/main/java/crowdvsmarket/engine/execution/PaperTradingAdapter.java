package crowdvsmarket.engine.execution;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Task item 7's default {@link ExecutionAdapter}: exactly what {@code CrowdAccount}/{@code
 * ComparisonAccounts} already did inline (before this task) to run the public "счёт толпы"/"анти-
 * толпа" paper trades — {@link Position#openFromMargin} + {@link PositionSimulator#simulate}, the
 * same two calls, now behind this interface instead of duplicated at each call site. Stateless and
 * side-effect-free: {@link #openPosition} and {@link #closePosition} are pure functions of their
 * arguments, so a single shared instance is safe to reuse across every caller (crowd account,
 * anti-crowd comparison line, tests, …).
 */
public final class PaperTradingAdapter implements ExecutionAdapter {

    @Override
    public Position openPosition(String userId, String roundId, Direction direction, Leverage leverage,
            BigDecimal margin, BigDecimal entryPrice) {
        return Position.openFromMargin(userId, roundId, direction, leverage, margin, entryPrice);
    }

    /** Always {@link Optional#empty()}: paper trading has no independent live price feed to poll
     *  between open and close — its only source of truth is the candle path
     *  {@link #closePosition} replays, exactly like {@code GameEngine}'s own player positions
     *  (Р-8: liquidation/settlement are both determined once, at round-resolution time, from the
     *  round's full path — never queried mid-round). A caller that wants an interim state has to
     *  simulate against the candles seen so far itself; this adapter does not cache that for them,
     *  same as before this task existed. */
    @Override
    public Optional<PositionSimulator.Outcome> getPosition(Position position) {
        return Optional.empty();
    }

    @Override
    public PositionSimulator.Outcome closePosition(Position position, List<Candle> priceHistorySinceOpen) {
        return PositionSimulator.simulate(position, priceHistorySinceOpen);
    }
}
