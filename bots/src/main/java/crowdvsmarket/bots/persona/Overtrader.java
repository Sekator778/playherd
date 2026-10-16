package crowdvsmarket.bots.persona;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.PlayAmount;
import java.util.random.RandomGenerator;

/**
 * Overtrading (Barber and Odean 2000, bot_behavior_sources.md B1): plays almost every round on three markets, the
 * smallest stake, the usual leverage mix; bets on a fallback signal when its rule is silent.
 */
public final class Overtrader extends AbstractPersona {

    public Overtrader() {
        super("overtrader", 0.95, 3, NoSignalPolicy.FALLBACK);
    }

    @Override
    public PlayAmount stake(MarketSnapshot market, BotAccount account, RandomGenerator rng) {
        return market.minStake();
    }

    @Override
    public int leverage(MarketSnapshot market, PlayAmount stake, BotAccount account, RandomGenerator rng) {
        return Leverages.realisticMix(rng, market.maxLeverage(stake));
    }
}
