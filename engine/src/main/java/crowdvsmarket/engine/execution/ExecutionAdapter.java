package crowdvsmarket.engine.execution;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.MarketSpec;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.Position;
import crowdvsmarket.engine.round.PositionSimulator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Task item 7 (PLAN.md §3, README's "паблик «счёт толпы» — по умолчанию paper-trading по реальным
 * ценам, адаптер на реальную биржу (Hyperliquid) есть в коде" claim): the pluggable execution seam
 * PLAN.md §3 specifies ("Модуль исполнения — подключаемые адаптеры: paper-trading по реальным
 * ценам (по умолчанию) + адаптер Hyperliquid в коде") — {@code open_position}/{@code
 * get_position}/{@code close_position} in PLAN's own naming, {@link #openPosition}/
 * {@link #getPosition}/{@link #closePosition} here. Same "pure port interface in engine/" pattern
 * {@link crowdvsmarket.engine.ledger.OnChainWalletPort} already establishes for the withdraw/
 * deposit seam — no framework dependency, just domain types.
 *
 * <p>{@link PaperTradingAdapter} (this package) is what {@code CrowdAccount}/{@code
 * ComparisonAccounts} already did inline before this task, now behind this interface. A real
 * exchange adapter ({@code crowdvsmarket.server.execution.HyperliquidAdapter}, server/, since it
 * needs an HTTP client and signing keys — infrastructure this pure module deliberately does not
 * depend on) has a genuinely different operational shape: it sends a real order and later polls or
 * receives a real fill/liquidation, rather than replaying an already-known candle path. This
 * interface is deliberately shaped around what a PAPER-TRADING caller (this codebase's only actual
 * caller today, Р-5/Р-14/Р-17/Р-26) needs — {@link #closePosition} takes the price history a
 * deterministic replay needs as its source of truth; a live adapter is free to largely ignore that
 * parameter in favor of a real closing order and a real fill price (see
 * {@code HyperliquidAdapter#closePosition}'s own javadoc for exactly how it does that today).
 * <b>предложение</b>: PLAN.md fixes the three method names, not their exact signatures.
 */
public interface ExecutionAdapter {

    /** Opens a new position in {@code spec}'s market at {@code entryPrice}, sized from
     *  {@code margin} (PLAY/paper-USD) — mirrors {@link Position#openFromMargin} exactly, since
     *  every adapter derives {@code liquidationPrice} the same way (Р-7's Hyperliquid-formula
     *  liquidation price is a property of the position itself, not of which venue executes it);
     *  the market only contributes its maintenance margin rate (Р-69). */
    Position openPosition(MarketSpec spec, String userId, String roundId, Direction direction, Leverage leverage,
            BigDecimal margin, BigDecimal entryPrice);

    /**
     * {@link #openPosition(MarketSpec, String, String, Direction, Leverage, BigDecimal, BigDecimal)}
     * for the BTC market — the shape this seam had before markets. Existing <i>callers</i> of this
     * form keep working unchanged; <i>implementers</i> do not get the same courtesy: the market
     * method above is the abstract one, so an adapter written against the old interface has to
     * implement it. It is also the only one the engine calls — {@code CrowdAccount} opens its
     * position through it, always with the market it trades — so a venue-specific override of this
     * BTC overload is never reached by {@code CrowdAccount} or {@code GameEngine}; that logic
     * belongs in the market method.
     */
    default Position openPosition(String userId, String roundId, Direction direction, Leverage leverage,
            BigDecimal margin, BigDecimal entryPrice) {
        return openPosition(MarketSpec.BTC, userId, roundId, direction, leverage, margin, entryPrice);
    }

    /** Best-effort current status without closing the position — {@link Optional#empty()} when
     *  this adapter's venue does not expose an intra-round status independent of
     *  {@link #closePosition} (see {@link PaperTradingAdapter#getPosition}, whose paper-trading
     *  model only ever learns a position's outcome by replaying the price history it is given). */
    Optional<PositionSimulator.Outcome> getPosition(Position position);

    /** Closes {@code position} and returns its outcome. {@code priceHistorySinceOpen} is the
     *  round's own candle path since the position was opened — the deterministic source of truth
     *  {@link PaperTradingAdapter} needs (Р-8: a round replays byte-identically on restart); a
     *  live-exchange adapter may ignore it in favor of a real order fill (documented per
     *  implementation). */
    PositionSimulator.Outcome closePosition(Position position, List<Candle> priceHistorySinceOpen);
}
