package crowdvsmarket.engine.margin;

import java.math.BigDecimal;

/**
 * Position side, matching the {@code side} variable of Hyperliquid's liquidation-price formula
 * (docs/research/hl_liquidation_math.md, section 1.6, quoting liquidations.md verbatim):
 * {@code side = 1 for long and -1 for short}.
 */
public enum Side {
    LONG(1),
    SHORT(-1);

    private final int multiplier;

    Side(int multiplier) {
        this.multiplier = multiplier;
    }

    /** The {@code side} term ({@code +1}/{@code -1}) as used directly in the spec's formulas. */
    public BigDecimal multiplier() {
        return BigDecimal.valueOf(multiplier);
    }
}
