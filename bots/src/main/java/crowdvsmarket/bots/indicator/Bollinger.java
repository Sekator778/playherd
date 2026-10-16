package crowdvsmarket.bots.indicator;

/**
 * Bollinger Bands: middle = SMA(n), upper/lower = middle +/- k * sigma, sigma the population standard deviation of the
 * last {@code n} values (Bollinger's own definition divides by n, not n - 1).
 */
public final class Bollinger {

    private Bollinger() {
    }

    /** The three band values after the last value. */
    public record Bands(double lower, double middle, double upper) {
    }

    public static Bands last(double[] values, int n, double k) {
        Series.requireLength(values, n, "Bollinger");
        double mean = Sma.last(values, n);
        double ss = 0;
        for (int i = values.length - n; i < values.length; i++) {
            double d = values[i] - mean;
            ss += d * d;
        }
        double sigma = Math.sqrt(ss / n);
        return new Bands(mean - k * sigma, mean, mean + k * sigma);
    }
}
