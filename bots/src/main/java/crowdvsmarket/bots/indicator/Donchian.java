package crowdvsmarket.bots.indicator;

/** Donchian channel: the highest high and the lowest low of a stretch of candles. */
public final class Donchian {

    private Donchian() {
    }

    /** Highest of {@code highs[from .. toExclusive-1]}. */
    public static double highest(double[] highs, int from, int toExclusive) {
        range(highs, from, toExclusive);
        double max = Double.NEGATIVE_INFINITY;
        for (int i = from; i < toExclusive; i++) {
            max = Math.max(max, highs[i]);
        }
        return max;
    }

    /** Lowest of {@code lows[from .. toExclusive-1]}. */
    public static double lowest(double[] lows, int from, int toExclusive) {
        range(lows, from, toExclusive);
        double min = Double.POSITIVE_INFINITY;
        for (int i = from; i < toExclusive; i++) {
            min = Math.min(min, lows[i]);
        }
        return min;
    }

    private static void range(double[] v, int from, int to) {
        if (from < 0 || to > v.length || from >= to) {
            throw new IllegalArgumentException("bad range " + from + ".." + to + " for length " + v.length);
        }
    }
}
