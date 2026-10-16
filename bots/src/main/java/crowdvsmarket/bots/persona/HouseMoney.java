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

    static final double DEFAULT_STEP = 0.5;
    static final int DEFAULT_CAP = 3;
    static final double DEFAULT_STAKE_MULTIPLE = 1.5;

    private final double step;
    private final int cap;
    private final double stakeMultiple;

    public HouseMoney() {
        this(DEFAULT_STEP, DEFAULT_CAP, DEFAULT_STAKE_MULTIPLE);
    }

    /** Other steps, caps and stake multiples: the sweep of the math review and the catalog's spread (step 0.5..1.0, cap 3..5, stake 1.5..2.0). */
    HouseMoney(double step, int cap, double stakeMultiple) {
        super("house-money", 0.85, 2, NoSignalPolicy.FALLBACK);
        this.step = step;
        this.cap = cap;
        this.stakeMultiple = stakeMultiple;
    }

    @Override
    public PlayAmount stake(MarketSnapshot market, BotAccount account, RandomGenerator rng) {
        PlayAmount base = botStake(account);
        return won(account) ? new PlayAmount(Math.round(base.mojos() * stakeMultiple)) : base;
    }

    @Override
    public int leverage(MarketSnapshot market, PlayAmount stake, BotAccount account, RandomGenerator rng) {
        int allowed = market.maxLeverage(stake);
        int base = Leverages.realisticMix(rng, allowed);
        if (!won(account)) {
            return base;
        }
        return Leverages.clamp((int) Math.round(base * (1.0 + step * Math.min(account.streak(), cap))), allowed);
    }
}
