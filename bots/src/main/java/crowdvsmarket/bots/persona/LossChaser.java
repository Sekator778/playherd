package crowdvsmarket.bots.persona;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.PlayAmount;
import java.util.random.RandomGenerator;

/**
 * Loss chasing (Coval and Shumway 2005, bot_behavior_sources.md B5, B6): after a loss the leverage is raised by half per
 * loss in a row (up to three) to win it back; after a win, or with no history, the usual leverage. The stake stays the
 * same. The half-per-loss step is our assumption; the source gives the direction only.
 */
public final class LossChaser extends AbstractPersona {

    public LossChaser() {
        super("loss-chaser", 0.85, 2, NoSignalPolicy.FALLBACK);
    }

    @Override
    public PlayAmount stake(MarketSnapshot market, BotAccount account, RandomGenerator rng) {
        return botStake(account);
    }

    @Override
    public int leverage(MarketSnapshot market, PlayAmount stake, BotAccount account, RandomGenerator rng) {
        int allowed = market.maxLeverage(stake);
        int base = Leverages.realisticMix(rng, allowed);
        if (!lost(account)) {
            return base;
        }
        return Leverages.clamp((int) Math.round(base * (1.0 + 0.5 * Math.min(account.streak(), 3))), allowed);
    }
}
