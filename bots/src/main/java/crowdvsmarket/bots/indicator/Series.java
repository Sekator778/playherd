package crowdvsmarket.bots.indicator;

/** Shared argument checks of the indicators. */
final class Series {

    private Series() {
    }

    static void requireLength(double[] values, int needed, String what) {
        if (needed < 1) {
            throw new IllegalArgumentException(what + " period must be positive: " + needed);
        }
        if (values.length < needed) {
            throw new IllegalArgumentException(what + " needs " + needed + " values, got " + values.length);
        }
    }
}
