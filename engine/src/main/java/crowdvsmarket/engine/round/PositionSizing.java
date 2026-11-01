package crowdvsmarket.engine.round;

import crowdvsmarket.engine.margin.Leverage;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Derives a position size (BTC for the BTC market, the market's own lot otherwise — the price is
 * whatever scale-2 number the caller hands the engine, D-69/D-70) from a PLAY margin so that the
 * margin IS the position's initial margin exactly: {@code round2(size * price / leverage) ==
 * margin} for any margin already at money scale (2dp or coarser) — proved for realistic BTC
 * prices/stakes/leverages by {@code PositionSizingPropertyTest}, and for prices from 1.00 to
 * 200 000.00 under every market's leverage range by {@code MarketPositionPropertyTest}. See
 * docs/research/round_engine_design.md "Units" for the precision argument this depends on.
 *
 * <p><b>Proposal:</b> {@link #SIZE_SCALE} = 8dp (satoshi precision, the standard BTC
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
