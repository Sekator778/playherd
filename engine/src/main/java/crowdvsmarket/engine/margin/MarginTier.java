package crowdvsmarket.engine.margin;

import java.math.BigDecimal;

/**
 * One margin tier, exactly as defined in docs/research/hl_liquidation_math.md section 1.4
 * (margin-tiers.md, quoted verbatim there):
 *
 * <pre>
 * maintenance_margin = notional_position_value * maintenance_margin_rate - maintenance_deduction
 * maintenance_margin_rate(tier = n) = (Initial Margin Rate at Maximum leverage at tier n) / 2
 * maintenance_deduction(tier = 0) = 0
 * maintenance_deduction(tier = n) = maintenance_deduction(tier = n - 1)
 *     + notional_position_lower_bound(tier = n) * (maintenance_margin_rate(tier = n) - maintenance_margin_rate(tier = n - 1))
 * </pre>
 *
 * <p>{@code maintenanceMarginRate} and {@code maintenanceDeduction} are carried here for data
 * fidelity to the spec (and so {@link MarginTable} can look up the correct max-leverage cap for a
 * given notional), but {@link LiquidationCalculator} deliberately does <b>not</b> use tier1's rate
 * or deduction: the engine implements tier0 only (BTC by default, any other market through an
 * explicit rate), see the class-level note on {@link LiquidationCalculator} and doc section 2.1.
 */
public record MarginTier(
        int tierIndex,
        BigDecimal lowerBoundNotional,
        int maxLeverage,
        BigDecimal maintenanceMarginRate,
        BigDecimal maintenanceDeduction) {
}
