package crowdvsmarket.bots.persona;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.PlayAmount;
import java.util.random.RandomGenerator;

/** Maximalist (today's "Maximalist"): always the highest leverage the stake's tier allows, on three markets. */
public final class Maximalist extends AbstractPersona {

    public Maximalist() {
        super("maximalist", 0.9, 3, NoSignalPolicy.FALLBACK);
    }

    @Override
    public PlayAmount stake(MarketSnapshot market, BotAccount account, RandomGenerator rng) {
        return botStake(account);
    }

    @Override
    public int leverage(MarketSnapshot market, PlayAmount stake, BotAccount account, RandomGenerator rng) {
        return market.maxLeverage(stake);
    }
}
