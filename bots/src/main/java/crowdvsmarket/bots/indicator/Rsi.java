package crowdvsmarket.bots.indicator;

/**
 * Relative Strength Index with Wilder's smoothing: the first average gain/loss is the plain mean of the first {@code n}
 * changes, then {@code avg = (avg * (n - 1) + x) / n}; {@code RSI = 100 - 100 / (1 + avgGain / avgLoss)}. Two edge cases
 * by convention: no loss at all gives 100 (50 when there is no gain either, a flat series).
 */
public final class Rsi {

    private Rsi() {
    }

    /** RSI after the last value; needs {@code n + 1} values (n changes). */
    public static double last(double[] values, int n) {
        Series.requireLength(values, n + 1, "RSI");
        double gain = 0;
        double loss = 0;
        for (int i = 1; i <= n; i++) {
            double d = values[i] - values[i - 1];
            if (d > 0) {
                gain += d;
            } else {
                loss -= d;
            }
        }
        gain /= n;
        loss /= n;
        for (int i = n + 1; i < values.length; i++) {
            double d = values[i] - values[i - 1];
            gain = (gain * (n - 1) + Math.max(d, 0)) / n;
            loss = (loss * (n - 1) + Math.max(-d, 0)) / n;
        }
        if (loss == 0) {
            return gain == 0 ? 50.0 : 100.0;
        }
        return 100.0 - 100.0 / (1.0 + gain / loss);
    }
}
