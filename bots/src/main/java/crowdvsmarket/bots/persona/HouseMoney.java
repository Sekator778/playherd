package crowdvsmarket.bots.persona;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.PlayAmount;
import java.util.random.RandomGenerator;

/**
 * House money effect (Thaler and Johnson 1990, bot_behavior_sources.md B5): after a win the stake is 1.5 times the usual
 * and the leverage is raised by half per win in a row (up to three); after a loss, or with no history, everything is back
 * to the usual. The 1.5 and the half-per-win step are our assumptions; the source gives the direction only.
 */
public final class HouseMoney extends AbstractPersona {

    public HouseMoney() {
        super("house-money", 0.85, 2, NoSignalPolicy.FALLBACK);
    }

    @Override
    public PlayAmount stake(MarketSnapshot market, BotAccount account, RandomGenerator rng) {
        PlayAmount base = botStake(account);
        return won(account) ? new PlayAmount(base.mojos() * 3 / 2) : base;
    }

    @Override
    public int leverage(MarketSnapshot market, PlayAmount stake, BotAccount account, RandomGenerator rng) {
        int allowed = market.maxLeverage(stake);
        int base = Leverages.realisticMix(rng, allowed);
        if (!won(account)) {
            return base;
        }
        return Leverages.clamp((int) Math.round(base * (1.0 + 0.5 * Math.min(account.streak(), 3))), allowed);
    }
}
