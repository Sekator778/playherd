package crowdvsmarket.bots.persona;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.PlayAmount;
import java.util.random.RandomGenerator;

/**
 * Occasional player (Barber and Odean 2000, bot_behavior_sources.md B1, the light end): plays about a third of the
 * rounds, on one market, with the usual stake and leverage mix; silent rule means no bet. The 35% is our assumption.
 */
public final class Occasional extends AbstractPersona {

    public Occasional() {
        super("occasional", 0.35, 1, NoSignalPolicy.SKIP);
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
