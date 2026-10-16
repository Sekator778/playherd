package crowdvsmarket.bots.signal;

import crowdvsmarket.bots.api.BarDto;
import crowdvsmarket.engine.round.Direction;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Everything a {@link SignalStrategy} may look at: the market's finished 1-minute candles (oldest first), the side the
 * majority took in the previous round, and the decision's seeded random generator. No clock, no network.
 *
 * <p>{@link #of} keeps only the trailing <i>contiguous</i> run of candles (each exactly one minute after the one before):
 * a hole in the history (a restart, a feed outage) makes everything before it unusable for a rule that assumes
 * consecutive minutes, so the strategy sees a shorter history and, if that is below its warm-up, stays silent.
 */
public final class MarketView {

    private final String marketId;
    private final double[] open;
    private final double[] high;
    private final double[] low;
    private final double[] close;
    private final Optional<Direction> previousMajority;
    private final RandomGenerator rng;

    private MarketView(String marketId, List<BarDto> run, Optional<Direction> previousMajority, RandomGenerator rng) {
        this.marketId = marketId;
        int n = run.size();
        open = new double[n];
        high = new double[n];
        low = new double[n];
        close = new double[n];
        for (int i = 0; i < n; i++) {
            BarDto b = run.get(i);
            open[i] = b.open();
            high[i] = b.high();
            low[i] = b.low();
            close[i] = b.close();
        }
        this.previousMajority = previousMajority;
        this.rng = rng;
    }

    /** @param bars candles oldest first; unsorted input is a caller error and yields the contiguous tail of what is given */
    public static MarketView of(String marketId, List<BarDto> bars, Optional<Direction> previousMajority,
                                RandomGenerator rng) {
        Objects.requireNonNull(marketId, "marketId");
        Objects.requireNonNull(bars, "bars");
        Objects.requireNonNull(previousMajority, "previousMajority");
        Objects.requireNonNull(rng, "rng");
        int start = bars.size();
        while (start > 0) {
            if (start < bars.size() && bars.get(start).openTimeMs() - bars.get(start - 1).openTimeMs() != BarDto.MINUTE_MS) {
                break;
            }
            start--;
        }
        return new MarketView(marketId, bars.subList(start, bars.size()), previousMajority, rng);
    }

    public String marketId() {
        return marketId;
    }

    /** Number of usable (contiguous) candles. */
    public int size() {
        return close.length;
    }

    public double[] opens() {
        return open.clone();
    }

    public double[] highs() {
        return high.clone();
    }

    public double[] lows() {
        return low.clone();
    }

    public double[] closes() {
        return close.clone();
    }

    public Optional<Direction> previousMajority() {
        return previousMajority;
    }

    public RandomGenerator rng() {
        return rng;
    }
}
