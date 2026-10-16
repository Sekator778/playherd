package crowdvsmarket.bots.persona;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.PlayAmount;
import java.util.random.RandomGenerator;

/**
 * Disciplined: plays most rounds on two markets, always the same stake, the "realistic mix" leverage of today's bots, and
 * sits out a market where its rule is silent. No change after wins or losses.
 */
public final class Disciplined extends AbstractPersona {

    public Disciplined() {
        super("disciplined", 0.85, 2, NoSignalPolicy.SKIP);
    }

    @Override
    public PlayAmount stake(MarketSnapshot market, BotAccount account, RandomGenerator rng) {
        return botStake(account);
    }

    @Override
    public int leverage(MarketSnapshot market, PlayAmount stake, BotAccount account, RandomGenerator rng) {
        return Leverages.realisticMix(rng, market.maxLeverage(stake));
    }
}
