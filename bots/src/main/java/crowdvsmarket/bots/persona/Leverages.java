package crowdvsmarket.bots.persona;

import crowdvsmarket.engine.margin.Leverage;
import java.util.random.RandomGenerator;

/** Leverage draws shared by the personas. */
final class Leverages {

    /** The share of bets that sit at a low leverage (same constants as the server's {@code BotStrategy}). */
    static final double LOW_SHARE = 0.70;
    /** The share that sit at a medium leverage; the rest go anywhere up to the allowed maximum. */
    static final double MEDIUM_SHARE = 0.25;

    private Leverages() {
    }

    /**
     * The "realistic mix" of the server's {@code BotStrategy#realisticMix} (economy stage B, plan B4): 70% of the bets take
     * a leverage of at most {@code allowed / 10}, 25% of at most {@code allowed / 4}, the last 5% anything up to
     * {@code allowed}; each uniform in {@code [Leverage.MIN, bound]}.
     */
    static int realisticMix(RandomGenerator rng, int allowed) {
        double u = rng.nextDouble();
        int bound = u < LOW_SHARE ? allowed / 10 : u < LOW_SHARE + MEDIUM_SHARE ? allowed / 4 : allowed;
        bound = Math.max(Leverage.MIN, Math.min(allowed, bound));
        return Leverage.MIN + rng.nextInt(bound - Leverage.MIN + 1);
    }

    static int clamp(int value, int allowed) {
        return Math.max(Leverage.MIN, Math.min(Math.max(Leverage.MIN, allowed), value));
    }
}
