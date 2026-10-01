package crowdvsmarket.server.prices;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How a raw price sits in a tick ring: a {@code long} holding {@code raw × 10^10}. Exact for every
 * price the markets can have — the lot exponent is at most 8 ({@code Market.MAX_LOT_EXP}), so a
 * raw price needs at most 2 + 8 = 10 decimals to show a lot price at the engine's two decimals —
 * and 16 bytes per tick (with the time) instead of a {@link BigDecimal} object each, which matters
 * at 3 s ticks over 48 h on a dozen markets. Order and equality of ticks are those of the longs,
 * so the folding of a round's path never touches a {@code BigDecimal}.
 */
final class Px {

    static final int SCALE = 10;

    /** Anything above this is no price of any listed market (and would overflow the scaled long,
     *  whose limit divided by 10^10 is about 9.2 × 10^8). */
    static final BigDecimal MAX_RAW = new BigDecimal("900000000");

    private Px() {
    }

    /** A price a ring can hold: positive, below {@link #MAX_RAW}, and not rounded to zero. */
    static boolean isStorable(BigDecimal raw) {
        return raw != null && raw.signum() > 0 && raw.compareTo(MAX_RAW) <= 0
                && raw.setScale(SCALE, RoundingMode.HALF_UP).signum() > 0;
    }

    /** Only for {@link #isStorable} prices. */
    static long encode(BigDecimal raw) {
        return raw.setScale(SCALE, RoundingMode.HALF_UP).unscaledValue().longValueExact();
    }

    /** Trailing zeros stripped, never a negative scale ({@code 84647} not {@code 8.4647E+4}). */
    static BigDecimal decode(long px) {
        BigDecimal d = BigDecimal.valueOf(px, SCALE).stripTrailingZeros();
        return d.scale() < 0 ? d.setScale(0) : d;
    }
}
