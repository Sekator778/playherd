package crowdvsmarket.server.bots;

import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.round.Direction;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Random;

/**
 * Р-46's "20 ботов с разными стратегиями" — five strategies, 4 bots each
 * ({@code BotService#ensureBotsExist}). All bots obey the same rules as any player (minimum
 * stake, one bet per round, leverage 2..40); only how they pick direction/leverage/stake differs.
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
    /** Direction, leverage and stake all uniformly random within the valid ranges. */
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

    public int chooseLeverage(Random random) {
        return switch (this) {
            case ALWAYS_MAX_LEVERAGE -> Leverage.MAX;
            case CAUTIOUS_LOW_LEVERAGE -> 2 + random.nextInt(4); // 2..5
            default -> Leverage.MIN + random.nextInt(Leverage.MAX - Leverage.MIN + 1);
        };
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
