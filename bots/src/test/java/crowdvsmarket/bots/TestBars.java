package crowdvsmarket.bots;

import crowdvsmarket.bots.api.BarDto;
import java.util.ArrayList;
import java.util.List;

/** Crafted candle series for the tests. */
public final class TestBars {

    private TestBars() {
    }

    /** One candle per close, a minute apart from time 0; each opens at the previous close (the first at its own close). */
    public static List<BarDto> of(double... closes) {
        return from(0, closes);
    }

    /** Like {@link #of} but the first candle opens at minute {@code startMinute}. */
    public static List<BarDto> from(long startMinute, double... closes) {
        List<BarDto> out = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            double open = i == 0 ? closes[0] : closes[i - 1];
            double hi = Math.max(open, closes[i]);
            double lo = Math.min(open, closes[i]);
            out.add(new BarDto((startMinute + i) * BarDto.MINUTE_MS, open, hi, lo, closes[i]));
        }
        return out;
    }

    /** {@code n} closes starting at {@code start}, each {@code step} above the previous. */
    public static double[] ramp(int n, double start, double step) {
        double[] c = new double[n];
        for (int i = 0; i < n; i++) {
            c[i] = start + step * i;
        }
        return c;
    }

    /** {@code n} copies of {@code price}. */
    public static double[] flat(int n, double price) {
        return ramp(n, price, 0);
    }

    /** The concatenation of the parts. */
    public static double[] concat(double[]... parts) {
        int n = 0;
        for (double[] p : parts) {
            n += p.length;
        }
        double[] r = new double[n];
        int at = 0;
        for (double[] p : parts) {
            System.arraycopy(p, 0, r, at, p.length);
            at += p.length;
        }
        return r;
    }
}
