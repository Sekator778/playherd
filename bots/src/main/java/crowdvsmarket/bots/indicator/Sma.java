package crowdvsmarket.bots.indicator;

/** Simple moving average: the arithmetic mean of the last {@code n} values. */
public final class Sma {

    private Sma() {
    }

    /** Mean of the last {@code n} values. */
    public static double last(double[] values, int n) {
        Series.requireLength(values, n, "SMA");
        double sum = 0;
        for (int i = values.length - n; i < values.length; i++) {
            sum += values[i];
        }
        return sum / n;
    }
}
