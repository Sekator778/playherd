package crowdvsmarket.bots.persona;

import crowdvsmarket.bots.api.BotAccount;
import crowdvsmarket.bots.api.LastOutcome;
import crowdvsmarket.bots.api.MarketSnapshot;
import crowdvsmarket.engine.economy.Fees;
import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The shared skeleton of the personas: a per-round betting probability, a number of markets per round, a no-signal
 * policy, market choice weighted by recent movement, and the rule-keeping {@link #size}. Subclasses supply the wanted
 * stake and leverage. All amounts are round: stakes are multiples of 10 PLAY (an assumption; no source, see
 * bot_behavior_sources.md B7, UNVERIFIED).
 */
abstract class AbstractPersona implements Persona {

    /** Stakes move in steps of 10 PLAY. */
    static final long STEP_PLAY = 10;

    private final String id;
    private final double betProbability;
    private final int marketsPerRound;
    private final NoSignalPolicy policy;

    AbstractPersona(String id, double betProbability, int marketsPerRound, NoSignalPolicy policy) {
        this.id = id;
        this.betProbability = betProbability;
        this.marketsPerRound = marketsPerRound;
        this.policy = policy;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final NoSignalPolicy noSignalPolicy() {
        return policy;
    }

    /** Probability of playing at all in a round. */
    public final double betProbability() {
        return betProbability;
    }

    /** Most markets played in one round. */
    public final int marketsPerRound() {
        return marketsPerRound;
    }

    @Override
    public final List<MarketCandidate> pickMarkets(List<MarketCandidate> candidates, BotAccount account, RandomGenerator rng) {
        if (candidates.isEmpty() || rng.nextDouble() >= betProbability) {
            return List.of();
        }
        List<MarketCandidate> pool = new ArrayList<>(candidates);
        List<MarketCandidate> picked = new ArrayList<>();
        int k = Math.min(marketsPerRound, pool.size());
        for (int i = 0; i < k; i++) {
            double maxMove = pool.stream().mapToDouble(MarketCandidate::recentMove).max().orElse(0);
            double[] weights = new double[pool.size()];
            double total = 0;
            for (int j = 0; j < weights.length; j++) {
                weights[j] = 1.0 + (maxMove > 0 ? 3.0 * pool.get(j).recentMove() / maxMove : 0.0);
                total += weights[j];
            }
            double r = rng.nextDouble() * total;
            int chosen = weights.length - 1;
            for (int j = 0; j < weights.length; j++) {
                r -= weights[j];
                if (r < 0) {
                    chosen = j;
                    break;
                }
            }
            picked.add(pool.remove(chosen));
        }
        return picked;
    }

    @Override
    public final Optional<Sizing> size(MarketSnapshot market, BotAccount account, RandomGenerator rng) {
        PlayAmount stake = roundToStep(stake(market, account, rng), market.minStake());
        while (true) {
            int leverage = Leverages.clamp(leverage(market, stake, account, rng), market.maxLeverage(stake));
            Fees fees = market.fees(leverage, stake);
            if (stake.plus(fees.total()).compareTo(account.balance()) <= 0) {
                return Optional.of(new Sizing(stake, leverage));
            }
            if (stake.compareTo(market.minStake()) <= 0) {
                // the herd fee is no longer capped at 1% of the stake (D-102): at the smallest stake a high leverage can cost more
                // than the balance leaves, so lower the leverage to the highest one that fits before giving up
                PlayAmount atMin = stake;
                return fittingLeverage(market, atMin, account, leverage).map(l -> new Sizing(atMin, l));
            }
            PlayAmount lower = stake.minus(PlayAmount.ofWholePlay(STEP_PLAY));
            stake = lower.compareTo(market.minStake()) < 0 ? market.minStake() : lower;
        }
    }

    /** The highest leverage in {@code [Leverage.MIN, below)} whose stake plus fees fit the balance (the fee only grows with leverage). */
    private static Optional<Integer> fittingLeverage(MarketSnapshot market, PlayAmount stake, BotAccount account, int below) {
        int lo = Leverage.MIN;
        int hi = below - 1;
        Integer best = null;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (stake.plus(market.fees(mid, stake).total()).compareTo(account.balance()) <= 0) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return Optional.ofNullable(best);
    }

    /** A stake in whole 10-PLAY steps, never below {@code min}. */
    static PlayAmount roundToStep(PlayAmount wanted, PlayAmount min) {
        long step = PlayAmount.ofWholePlay(STEP_PLAY).mojos();
        long steps = Math.max(1, Math.round((double) wanted.mojos() / step));
        PlayAmount r = new PlayAmount(steps * step);
        return r.compareTo(min) < 0 ? min : r;
    }

    /** The constant stake of a bot: 100, 150, 200 or 250 PLAY by its id (all within the full-leverage tier, up to 250). */
    static PlayAmount botStake(BotAccount account) {
        return PlayAmount.ofWholePlay(100 + 50L * Math.floorMod(account.botId().hashCode(), 4));
    }

    static boolean won(BotAccount account) {
        return account.lastOutcome() == LastOutcome.WIN;
    }

    static boolean lost(BotAccount account) {
        return account.lastOutcome() == LastOutcome.LOSS;
    }
}
