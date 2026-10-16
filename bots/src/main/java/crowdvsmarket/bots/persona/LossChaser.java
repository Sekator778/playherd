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

    /** Leverage added per loss in a row, as a share of the usual (0.5 = half). */
    static final double DEFAULT_STEP = 0.5;
    /** Losses in a row after which the leverage stops rising. */
    static final int DEFAULT_CAP = 3;

    private final double step;
    private final int cap;

    public LossChaser() {
        this(DEFAULT_STEP, DEFAULT_CAP);
    }

    /** Other steps and caps, for the parameter sweep of the math review; the catalog uses the defaults. */
    LossChaser(double step, int cap) {
        super("loss-chaser", 0.85, 2, NoSignalPolicy.FALLBACK);
        this.step = step;
        this.cap = cap;
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
        return Leverages.clamp((int) Math.round(base * (1.0 + step * Math.min(account.streak(), cap))), allowed);
    }
}
