package crowdvsmarket.bots.persona;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import java.util.random.RandomGenerator;

/** Cautious (today's "Cautious"): leverage 2..5 (or the maximum if lower), stake 100..200 PLAY, three markets. */
public final class Cautious extends AbstractPersona {

    public Cautious() {
        super("cautious", 0.9, 3, NoSignalPolicy.SKIP);
    }

    @Override
    public PlayAmount stake(MarketSnapshot market, BotAccount account, RandomGenerator rng) {
        return PlayAmount.ofWholePlay(100 + 10L * rng.nextInt(11));
    }

    @Override
    public int leverage(MarketSnapshot market, PlayAmount stake, BotAccount account, RandomGenerator rng) {
        return Math.min(market.maxLeverage(stake), Leverage.MIN + rng.nextInt(4));
    }
}
