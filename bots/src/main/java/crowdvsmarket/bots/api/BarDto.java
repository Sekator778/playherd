package crowdvsmarket.bots.api;

/**
 * One finished 1-minute candle of a market, in public-API terms (the shape the server's candles read-model hands out).
 * Prices are plain doubles: the bots only compare and average them; money maths (stakes, payouts) stays in {@code engine}.
 *
 * @param openTimeMs start of the minute, epoch milliseconds
 */
public record BarDto(long openTimeMs, double open, double high, double low, double close) {

    /** One minute in milliseconds. */
    public static final long MINUTE_MS = 60_000L;

    public BarDto {
        if (!(low > 0) || high < low || open < low || open > high || close < low || close > high
                || Double.isNaN(open) || Double.isNaN(close) || Double.isInfinite(high)) {
            throw new IllegalArgumentException("inconsistent candle at " + openTimeMs + ": o=" + open + " h=" + high
                    + " l=" + low + " c=" + close);
        }
    }
}
