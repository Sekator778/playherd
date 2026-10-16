package crowdvsmarket.bots.persona;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.ledger.PlayAmount;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Today's server bots ({@code server/.../bots/BotStrategy}) as personas, for comparing the old population with the new in
 * a backtest. {@link Personas#byId} knows them, {@link Personas#ids} does not, so the catalog never validates an entry
 * that names one. Every bot plays every round on three markets; the stake is uniform in 100..999 PLAY (the cautious one
 * 100..200), rounded to 10 as every persona's; the leverage is "always the maximum", "2..5" or the realistic mix.
 * Differences from the server: markets are drawn with the same lean to moving markets as every persona (the server draws
 * uniformly), and stakes are multiples of 10.
 */
public final class LegacyPersona extends AbstractPersona {

    /** The kinds of {@code BotStrategy}'s leverage and stake rules. */
    public enum Kind { MAXIMALIST, CAUTIOUS, MIXED }

    private final Kind kind;

    public LegacyPersona(Kind kind) {
        super("legacy-" + kind.name().toLowerCase(Locale.ROOT), 1.0, 3, NoSignalPolicy.FALLBACK);
        this.kind = kind;
    }

    @Override
    public PlayAmount stake(MarketSnapshot market, BotAccount account, RandomGenerator rng) {
        return PlayAmount.ofWholePlay(kind == Kind.CAUTIOUS ? 100 + rng.nextInt(101) : 100 + rng.nextInt(900));
    }

    @Override
    public int leverage(MarketSnapshot market, PlayAmount stake, BotAccount account, RandomGenerator rng) {
        int allowed = market.maxLeverage(stake);
        return switch (kind) {
            case MAXIMALIST -> allowed;
            case CAUTIOUS -> Math.min(allowed, 2 + rng.nextInt(4));
            case MIXED -> Leverages.realisticMix(rng, allowed);
        };
    }
}
