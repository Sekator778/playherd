package crowdvsmarket.engine.margin;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * An asset's ordered margin tiers, built from the recursive {@code maintenance_deduction} formula
 * quoted in {@link MarginTier} (docs/research/hl_liquidation_math.md section 1.4).
 *
 * <p>{@link #BTC} is Hyperliquid's live BTC margin table (mainnet), confirmed both from
 * margin-tiers.md and from a live {@code POST /info {"type":"meta"}} call, 2026-09-25 (doc section
 * 1.4/1.10): {@code marginTableId=56}, tiers {@code [0, 150_000_000)} USDC notional at 40x max
 * leverage, {@code [150_000_000, +inf)} at 20x.
 */
public final class MarginTable {

    /** BTC (mainnet) margin tiers — see class-level note for the source. */
    public static final MarginTable BTC = MarginTable.build(List.of(
            new TierSpec(new BigDecimal("0.0"), 40),
            new TierSpec(new BigDecimal("150000000.0"), 20)));

    private final List<MarginTier> tiers;

    private MarginTable(List<MarginTier> tiers) {
        this.tiers = List.copyOf(tiers);
    }

    /** A tier's lower bound and max leverage, the two facts the spec's tier table documents. */
    public record TierSpec(BigDecimal lowerBoundNotional, int maxLeverage) {
    }

    /**
     * Builds a margin table from ordered tier specs (ascending {@code lowerBoundNotional},
     * starting at tier 0 with lower bound 0), applying the doc's recursive
     * {@code maintenance_margin_rate}/{@code maintenance_deduction} formulas verbatim.
     */
    public static MarginTable build(List<TierSpec> specs) {
        if (specs.isEmpty()) {
            throw new IllegalArgumentException("at least one margin tier is required");
        }
        List<MarginTier> built = new ArrayList<>(specs.size());
        BigDecimal previousRate = null;
        BigDecimal previousDeduction = null;
        for (int i = 0; i < specs.size(); i++) {
            TierSpec spec = specs.get(i);
            // maintenance_margin_rate(tier = n) = (Initial Margin Rate at Maximum leverage at tier n) / 2
            //   Initial Margin Rate at Maximum leverage = 1 / maxLeverage
            BigDecimal rate = BigDecimal.ONE.divide(
                    BigDecimal.valueOf(2L * spec.maxLeverage()), MathContext.DECIMAL128);
            BigDecimal deduction;
            if (i == 0) {
                deduction = BigDecimal.ZERO;
            } else {
                deduction = previousDeduction.add(
                        spec.lowerBoundNotional().multiply(rate.subtract(previousRate)));
            }
            built.add(new MarginTier(i, spec.lowerBoundNotional(), spec.maxLeverage(), rate, deduction));
            previousRate = rate;
            previousDeduction = deduction;
        }
        return new MarginTable(built);
    }

    public List<MarginTier> tiers() {
        return tiers;
    }

    public MarginTier tier(int index) {
        return tiers.get(index);
    }

    /** The tier whose {@code lowerBoundNotional} is the greatest one {@code <= notional}. */
    public MarginTier tierFor(BigDecimal notional) {
        MarginTier applicable = tiers.get(0);
        for (MarginTier tier : tiers) {
            if (notional.compareTo(tier.lowerBoundNotional()) >= 0) {
                applicable = tier;
            } else {
                break;
            }
        }
        return applicable;
    }

    /** Max leverage allowed for a position of the given notional value (USD). */
    public int maxLeverageFor(BigDecimal notional) {
        return tierFor(notional).maxLeverage();
    }
}
