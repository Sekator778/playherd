package crowdvsmarket.engine.economy;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.margin.Leverage;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.market.MarketSpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The stage-A economy rules of a bet (Р-92, docs/product/economy_implementation_plan.md A3-A5), pure functions of
 * a {@link MarketSpec}, the round length, the leverage and the stake:
 *
 * <ul>
 *   <li><b>Stake tiers (A3).</b> The bigger the stake the lower the highest leverage: {@code cap} for a stake of up to
 *       250 PLAY, {@code cap/2} up to 500, {@code cap/5} up to 1 200, {@code cap/10} above (integer division, at
 *       least {@link Leverage#MIN}, never above the cap). {@code cap} is {@link MarketSpec#capFor}.
 *   <li><b>Fees (A4).</b> {@code fee_herd = stake * min(20%, k * r_m * L)}, {@code fee_net = 0.1 PLAY}, both rounded up
 *       to 0.001 PLAY, {@code fee_herd} at least 0.001 PLAY. {@code k} is {@link MarketSpec#feeFactorFor} (1 in the
 *       short round; 2.2 price / 3.0 odds in the long one), the 20% is a guard no market reaches (Р-102).
 *   <li><b>Payout cap (A5).</b> A bet pays at most {@code min(20 * stake, 0.1% of the treasury)}. Until the treasury
 *       account exists (stage B) the treasury is the constant {@link #TREASURY_FALLBACK}, the treasury coin on chain
 *       at the time of the decision; stage B replaces it with the TREASURY balance.
 * </ul>
 */
public final class EconomyRules {

    /** The flat network fee per bet: 0.1 PLAY. */
    public static final PlayAmount NETWORK_FEE = new PlayAmount(100);
    /** The smallest herd fee: 0.001 PLAY. */
    public static final PlayAmount MIN_HERD_FEE = new PlayAmount(1);
    /** The herd fee never exceeds this share of the stake: a guard only, no market reaches it (Р-102; before it 1%). */
    public static final BigDecimal HERD_FEE_MAX_SHARE = new BigDecimal("0.20");

    /** Stake-tier upper bounds in whole PLAY: up to 250 -> cap, 500 -> cap/2, 1200 -> cap/5, above -> cap/10. */
    private static final long[] TIER_UPPER_PLAY = {250, 500, 1_200};
    private static final int[] TIER_DIVISOR = {1, 2, 5, 10};

    /** One stake tier: a stake up to {@code upToPlay} whole PLAY ({@code null}: any bigger stake) may take {@code cap / divisor}. */
    public record StakeTier(Long upToPlay, int divisor) {
    }

    /** The tiers in order, smallest stake first, the last one open-ended: what {@link #tierDivisor} applies, for the clients to show. */
    public static List<StakeTier> stakeTiers() {
        List<StakeTier> tiers = new ArrayList<>();
        for (int i = 0; i < TIER_DIVISOR.length; i++) {
            tiers.add(new StakeTier(i < TIER_UPPER_PLAY.length ? TIER_UPPER_PLAY[i] : null, TIER_DIVISOR[i]));
        }
        return List.copyOf(tiers);
    }

    /** A bet pays at most this many times its stake. */
    public static final int PAYOUT_MULTIPLE = 20;
    /**
     * The treasury of the payout cap before the economy cutover (stage B): the treasury coin on chain, 99 998 100 PLAY
     * (docs/product/economy_implementation_plan.md, "Факт для всех этапов", checked 2026-10-15). After it the cap follows the
     * TREASURY balance ({@code LedgerBook#payoutCapTreasury}).
     */
    public static final PlayAmount TREASURY_FALLBACK = PlayAmount.ofWholePlay(99_998_100);
    /** The share of the treasury one bet may pay out at most: 0.1%. */
    public static final long TREASURY_PAYOUT_PER_MILLE = 1;

    private EconomyRules() {
    }

    /** The highest leverage a bet of {@code stake} may take on {@code spec}'s market in {@code interval}'s round (A3). */
    public static int maxLeverage(MarketSpec spec, Interval interval, PlayAmount stake) {
        int cap = spec.capFor(interval);
        int divisor = tierDivisor(stake);
        return Math.min(cap, Math.max(Leverage.MIN, cap / divisor));
    }

    /** 1, 2, 5 or 10 by the size of the stake (A3). */
    public static int tierDivisor(PlayAmount stake) {
        for (int i = 0; i < TIER_UPPER_PLAY.length; i++) {
            if (stake.compareTo(PlayAmount.ofWholePlay(TIER_UPPER_PLAY[i])) <= 0) {
                return TIER_DIVISOR[i];
            }
        }
        return TIER_DIVISOR[TIER_DIVISOR.length - 1];
    }

    /**
     * @throws IllegalArgumentException if {@code leverage} is outside {@code [Leverage.MIN, maxLeverage(spec, interval, stake)]};
     *                                  the message names the allowed maximum for that stake
     */
    public static void requireLeverageAllowed(MarketSpec spec, Interval interval, int leverage, PlayAmount stake) {
        int allowed = maxLeverage(spec, interval, stake);
        if (leverage < Leverage.MIN || leverage > allowed) {
            throw new IllegalArgumentException("leverage must be in [" + Leverage.MIN + ", " + allowed + "] for a stake of "
                    + stake.toMoney().stripTrailingZeros().toPlainString() + " PLAY on " + spec.id() + " ("
                    + interval + " round, market cap " + spec.capFor(interval) + "), got " + leverage);
        }
    }

    /** The fees of a bet of {@code interval}'s round length (A4, Р-102). */
    public static Fees fees(MarketSpec spec, Interval interval, int leverage, PlayAmount stake) {
        BigDecimal share = spec.herdFeeRate().multiply(spec.feeFactorFor(interval)).multiply(BigDecimal.valueOf(leverage))
                .min(HERD_FEE_MAX_SHARE);
        BigDecimal herd = stake.toMoney().multiply(share).setScale(3, RoundingMode.CEILING);
        PlayAmount herdFee = PlayAmount.fromMoney(herd);
        if (herdFee.compareTo(MIN_HERD_FEE) < 0) {
            herdFee = MIN_HERD_FEE;
        }
        return new Fees(herdFee, NETWORK_FEE);
    }

    /** The platform fee of a fixed-payout bet (Р-106, docs/product/fixed_payout_rules.md point 3): 3% of the stake. */
    public static final BigDecimal FIXED_PLATFORM_FEE_RATE = new BigDecimal("0.03");

    /**
     * The fees of a fixed-payout bet (Р-106): the platform fee, 3% of the stake rounded up to 0.001 PLAY (kept in {@link Fees#herd()}, the
     * ledger account of the percentage mode's fee), and the same flat network fee of 0.1 PLAY. Charged on top of the stake, kept on every outcome.
     */
    public static Fees fixedFees(PlayAmount stake) {
        BigDecimal platform = stake.toMoney().multiply(FIXED_PLATFORM_FEE_RATE).setScale(3, RoundingMode.CEILING);
        return new Fees(PlayAmount.fromMoney(platform), NETWORK_FEE);
    }

    /** What a fixed-payout bet is paid back (Р-106): twice the stake for the right direction, nothing for the wrong one, the stake on a tie ({@code cmp}: close compared to open). */
    public static PlayAmount fixedPayout(PlayAmount stake, boolean up, int closeVsOpen) {
        if (closeVsOpen == 0) {
            return stake;
        }
        return (closeVsOpen > 0) == up ? new PlayAmount(Math.multiplyExact(stake.mojos(), 2L)) : PlayAmount.ZERO;
    }

    /** The most a bet of {@code stake} can be paid back in total (A5):{@code min(20 * stake, 0.1% of the treasury)}. */
    public static PlayAmount maxPayout(PlayAmount stake) {
        return maxPayout(stake, TREASURY_FALLBACK);
    }

    /** {@link #maxPayout(PlayAmount)} against the TREASURY balance (stage B; {@code LedgerBook#payoutCapTreasury}). */
    public static PlayAmount maxPayout(PlayAmount stake, PlayAmount treasury) {
        long byStake = Math.multiplyExact(stake.mojos(), (long) PAYOUT_MULTIPLE);
        long byTreasury = treasury.mojos() / 1000L * TREASURY_PAYOUT_PER_MILLE;
        return new PlayAmount(Math.min(byStake, byTreasury));
    }
}
