package crowdvsmarket.engine.round;

import crowdvsmarket.engine.margin.Leverage;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Derives a BTC position size from a PLAY margin so that the margin IS the position's initial
 * margin exactly: {@code round2(size * price / leverage) == margin} for any margin already at
 * money scale (2dp or coarser) — proved for realistic BTC prices/stakes/leverages by
 * {@code PositionSizingPropertyTest}. See docs/research/round_engine_design.md "Units" for the
 * precision argument this depends on.
 *
 * <p><b>Предложение:</b> {@link #SIZE_SCALE} = 8dp (satoshi precision, the standard BTC
 * subdivision). Rounding {@code size} to 8dp introduces at most {@code 0.5e-8 * price} of error in
 * {@code size * price}; dividing by leverage (>= 2) only shrinks it further, and the 2dp rounding
 * of the final {@code initialMargin} absorbs it — see the design doc for the bound.
 */
public final class PositionSizing {

    public static final int SIZE_SCALE = 8;

    private PositionSizing() {
    }

    public static BigDecimal sizeForMargin(BigDecimal margin, Leverage leverage, BigDecimal price) {
        BigDecimal raw = margin.multiply(leverage.asBigDecimal()).divide(price, MathContext.DECIMAL128);
        return raw.setScale(SIZE_SCALE, RoundingMode.HALF_UP);
    }
}
