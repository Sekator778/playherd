package crowdvsmarket.bots.api;

import crowdvsmarket.engine.economy.EconomyRules;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import java.util.Objects;

/**
 * What a bot sees of one market's current round: the market's terms ({@link MarketSpec}: caps, fee rate), the round
 * length, the round id and window, whether bets are still accepted, and the smallest stake. The leverage ceiling and the
 * fees are derived from the terms by {@link EconomyRules}, never copied, so a bot cannot drift from the game's rules.
 *
 * @param open          bets are accepted in this round right now
 * @param roundId       the id a bet on this round is placed with
 * @param windowStartMs start of the round's window, epoch milliseconds
 * @param windowEndMs   end of the round's window
 */
public record MarketSnapshot(MarketSpec spec, Interval interval, String roundId, long windowStartMs, long windowEndMs,
                             boolean open, PlayAmount minStake) {

    public MarketSnapshot {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(roundId, "roundId");
        Objects.requireNonNull(minStake, "minStake");
        if (windowEndMs <= windowStartMs) {
            throw new IllegalArgumentException("window must have a positive length: " + windowStartMs + ".." + windowEndMs);
        }
    }

    /** The market id. */
    public String marketId() {
        return spec.id();
    }

    /** The highest leverage a bet of {@code stake} may take here (stake tiers, {@link EconomyRules#maxLeverage}). */
    public int maxLeverage(PlayAmount stake) {
        return EconomyRules.maxLeverage(spec, interval, stake);
    }

    /** The fees charged on top of a bet of {@code stake} at {@code leverage} here. */
    public Fees fees(int leverage, PlayAmount stake) {
        return EconomyRules.fees(spec, leverage, stake);
    }
}
