package crowdvsmarket.engine.market;

/**
 * Round/candle timeframe. Matches {@code timeframe: daily|hourly} in the {@code Round} data
 * format (PLAN.md section 3) and the two candle intervals fetched into
 * {@code engine/src/test/resources/fixtures/} (PROVENANCE.md): {@code 1d}/{@code 1h}.
 */
public enum Interval {
    DAILY("1d"),
    HOURLY("1h");

    private final String code;

    Interval(String code) {
        this.code = code;
    }

    /** The Hyperliquid {@code candleSnapshot} interval code, e.g. {@code "1d"}. */
    public String code() {
        return code;
    }

    /** Inverse of {@link #code()}: {@code "1h"} is {@link #HOURLY}, {@code "1d"} is {@link #DAILY}. */
    public static Interval fromCode(String code) {
        for (Interval interval : values()) {
            if (interval.code.equals(code)) {
                return interval;
            }
        }
        throw new IllegalArgumentException("unknown interval code: " + code);
    }
}
