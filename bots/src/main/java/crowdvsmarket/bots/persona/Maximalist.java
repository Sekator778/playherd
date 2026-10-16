package crowdvsmarket.bots.persona;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.PlayAmount;
import java.util.random.RandomGenerator;

/** Maximalist (today's "Maximalist"): always the highest leverage the stake's tier allows, on three markets. */
public final class Maximalist extends AbstractPersona {

    private final double share;

    public Maximalist() {
        this(1.0);
    }

    /** A share of the allowed maximum instead of all of it, for the parameter sweep of the math review. */
    Maximalist(double share) {
        super("maximalist", 0.9, 3, NoSignalPolicy.FALLBACK);
        this.share = share;
    }

    @Override
    public PlayAmount stake(MarketSnapshot market, BotAccount account, RandomGenerator rng) {
        return botStake(account);
    }

    @Override
    public int leverage(MarketSnapshot market, PlayAmount stake, BotAccount account, RandomGenerator rng) {
        int allowed = market.maxLeverage(stake);
        return share >= 1.0 ? allowed : Leverages.clamp((int) Math.round(allowed * share), allowed);
    }
}
