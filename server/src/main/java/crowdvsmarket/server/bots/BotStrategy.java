package crowdvsmarket.server.bots;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.round.Direction;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Random;

/**
 * D-46's "20 ботов с разными стратегиями" — five strategies, 4 bots each
 * ({@code BotService#ensureBotsExist}). All bots obey the same rules as any player (minimum
 * stake, one bet per round per market, leverage 2..the market's cap); only how they pick
 * direction/leverage/stake differs.
 * Предложение: the exact five named here and their parameters are this server's own choice — the
 * decision only names the five kinds by description, not exact numbers.
 */
public enum BotStrategy {

    /** Always bets the maximum leverage. */
    ALWAYS_MAX_LEVERAGE("Maximalist"),
    /** Bets against the previous round's crowd majority, if known. */
    CONTRARIAN("Contrarian"),
    /** Bets with the previous round's crowd majority, if known. */
    FOLLOW_MAJORITY("Trend Follower"),
    /** Direction and stake uniformly random; the leverage follows the realistic mix of {@link #realisticMix}. */
    RANDOM("Wildcard"),
    /** Always bets a low leverage (2..5) and the minimum-ish stake. */
    CAUTIOUS_LOW_LEVERAGE("Cautious");

    private final String label;

    BotStrategy(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public Direction chooseDirection(Random random, Optional<Direction> previousMajority) {
        return switch (this) {
            case CONTRARIAN -> previousMajority.map(BotStrategy::opposite).orElseGet(() -> randomDirection(random));
            case FOLLOW_MAJORITY -> previousMajority.orElseGet(() -> randomDirection(random));
            default -> randomDirection(random);
        };
    }

    /** BTC's original range, 2..40 — the pre-markets shape. */
    public int chooseLeverage(Random random) {
        return chooseLeverage(random, 40);
    }

    /** Leverage within {@code [Leverage.MIN, maxLeverage]} — the highest leverage allowed for the bot's stake on its market
     *  in its round length (the cap lowered by the stake tier, {@code EconomyRules#maxLeverage}; Т-4, Т-8, D-92): "always
     *  maximum" bets that maximum itself, the cautious bot stays within 2..5 (or the maximum if lower). */
    public int chooseLeverage(Random random, int maxLeverage) {
        int cap = Math.max(Leverage.MIN, Math.min(Leverage.MAX, maxLeverage));
        return switch (this) {
            case ALWAYS_MAX_LEVERAGE -> cap;
            case CAUTIOUS_LOW_LEVERAGE -> Math.min(cap, 2 + random.nextInt(4)); // 2..5
            default -> realisticMix(random, cap);
        };
    }

    /** Economy stage B (plan B4, the fix of stage A): the share of bets that sit at a low, a medium and a high leverage. */
    public static final double LOW_SHARE = 0.70;
    public static final double MEDIUM_SHARE = 0.25;

    /**
     * A realistic spread instead of a uniform draw up to {@code allowed} (which put most bets at an extreme leverage): 70% of the bets take
     * a leverage of at most {@code allowed / 10}, 25% of at most {@code allowed / 4}, the last 5% anything up to {@code allowed}; each
     * uniform in {@code [Leverage.MIN, bound]}.
     */
    static int realisticMix(Random random, int allowed) {
        double u = random.nextDouble();
        int bound = u < LOW_SHARE ? allowed / 10 : u < LOW_SHARE + MEDIUM_SHARE ? allowed / 4 : allowed;
        bound = Math.max(Leverage.MIN, Math.min(allowed, bound));
        return Leverage.MIN + random.nextInt(bound - Leverage.MIN + 1);
    }

    public BigDecimal chooseStake(Random random) {
        int wholePlay = switch (this) {
            case CAUTIOUS_LOW_LEVERAGE -> 100 + random.nextInt(101); // 100..200
            default -> 100 + random.nextInt(900); // 100..999
        };
        return new BigDecimal(wholePlay).setScale(3);
    }

    private static Direction randomDirection(Random random) {
        return random.nextBoolean() ? Direction.UP : Direction.DOWN;
    }

    private static Direction opposite(Direction d) {
        return d == Direction.UP ? Direction.DOWN : Direction.UP;
    }
}
