package crowdvsmarket.bots.indicator;

/**
 * Exponential moving average with {@code alpha = 2 / (n + 1)}, seeded with the SMA of the first {@code n} values (the
 * convention of TA-Lib and most charting packages), then {@code ema = alpha * x + (1 - alpha) * ema}.
 */
public final class Ema {

    private Ema() {
    }

    /** The EMA after the last value; needs at least {@code n} values. */
    public static double last(double[] values, int n) {
        Series.requireLength(values, n, "EMA");
        double alpha = 2.0 / (n + 1);
        double ema = 0;
        for (int i = 0; i < n; i++) {
            ema += values[i];
        }
        ema /= n;
        for (int i = n; i < values.length; i++) {
            ema = alpha * values[i] + (1 - alpha) * ema;
        }
        return ema;
    }
}
