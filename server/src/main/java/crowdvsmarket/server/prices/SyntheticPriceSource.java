package crowdvsmarket.server.prices;

import com.fasterxml.jackson.databind.ObjectMapper;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The offline price source ({@code herd.prices.source=synthetic}, markets_architecture.md §2 Т-12):
 * a deterministic random walk per market for CI, e2e and work without a network. No HTTP at all.
 *
 * <p>The walk is a <b>Brownian bridge</b> seeded from the market id: a Gaussian random walk of
 * {@link #PERIOD_SECONDS} (2^17 s, about 36 hours) that starts and ends at the market's start price
 * and is repeated, computed by midpoint displacement with every Gaussian drawn from a hash of
 * (seed, interval) — so the price of a market at any second is a <i>pure function</i> of
 * (market id, start price, second): the same on every run and every machine, one tick per second
 * from {@link #poll}, and a restart or a gap costs nothing because {@link #history} simply evaluates
 * the same function for the missing seconds. PERP markets move as {@code start · exp(σ·B)} with
 * {@code σ = 3·10⁻⁴ per √s} (about 1.8% an hour at one standard deviation), ODDS markets as a
 * logistic walk around the start probability ({@code σ = 0.002} logit units per √s, held on a
 * 0.0005 grid like a real order book), both with a per-market volatility between 0.7x and 1.3x.
 * All of it uses {@link StrictMath}.
 *
 * <p><b>Test hooks</b> for an integration test running with {@code herd.mode=live,
 * herd.prices.source=synthetic}: {@link #push} stores an explicit tick (any time, any price) as if
 * the feed had delivered it, {@link #stopWalk} stops the generated ticks <i>and</i> the generated
 * history so that only pushed ticks exist, {@link #resumeWalk} undoes it.
 */
@ApplicationScoped
@Unremovable
public class SyntheticPriceSource implements PriceSource {

    public static final String NAME = "synthetic";
    public static final String FEED = "synthetic";

    /** Length of the repeating bridge; a power of two so that every interval has an exact midpoint. */
    static final long PERIOD_SECONDS = 1L << 17;
    static final double PERP_SIGMA = 0.0003;
    static final double ODDS_SIGMA = 0.002;
    static final BigDecimal ODDS_GRID = new BigDecimal("0.0005");
    /** History of a longer hole than this many seconds is cut to its newest part. */
    static final long MAX_HISTORY_SECONDS = 200_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TickStore store;
    private final PricesConfig config;
    private final LongSupplier clock;
    private volatile boolean walking = true;

    @Inject
    public SyntheticPriceSource(TickStore store, PricesConfig config) {
        this(store, config, System::currentTimeMillis);
    }

    SyntheticPriceSource(TickStore store, PricesConfig config, LongSupplier clock) {
        this.store = store;
        this.config = config;
        this.clock = clock;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** One feed that prices every market, whatever its {@code source} column says (BTC's row is {@code hyperliquid}). */
    @Override
    public List<Feed> feeds() {
        return List.of(new Feed(FEED, config.syntheticStaleMs(), config.syntheticMaxGapMs(), m -> true));
    }

    @Override
    public Map<String, Quote> poll(Feed feed, List<Market> markets) {
        Map<String, Quote> out = new LinkedHashMap<>();
        if (!walking) {
            return out;
        }
        long second = Math.floorDiv(clock.getAsLong(), 1000L);
        for (Market market : markets) {
            out.put(market.id(), new Quote(priceAt(market, second), null));
        }
        return out;
    }

    /** The walk's price at each whole second in {@code [fromMs, toMs]}. */
    @Override
    public List<PricePoint> history(Market market, long fromMs, long toMs) {
        List<PricePoint> out = new ArrayList<>();
        if (!walking) {
            return out;
        }
        long first = Math.floorDiv(fromMs + 999L, 1000L);
        long last = Math.floorDiv(toMs, 1000L);
        first = Math.max(first, last - MAX_HISTORY_SECONDS + 1);
        for (long second = first; second <= last; second++) {
            out.add(new PricePoint(second * 1000L, priceAt(market, second)));
        }
        return out;
    }

    /** Test hook: a tick of {@code marketId} at {@code atMs}, stored like a polled one. */
    public void push(String marketId, long atMs, BigDecimal raw) {
        store.append(FEED, atMs, Map.of(marketId, raw));
    }

    /** Test hook: no generated ticks and no generated history until {@link #resumeWalk}. */
    public void stopWalk() {
        walking = false;
    }

    public void resumeWalk() {
        walking = true;
    }

    public boolean isWalking() {
        return walking;
    }

    /** The synthetic price of {@code market} at the given second of the Unix epoch. */
    public static BigDecimal priceAt(Market market, long epochSecond) {
        long seed = seed(market.id());
        double b = bridge(seed, Math.floorMod(epochSecond, PERIOD_SECONDS));
        double volatility = 0.7 + 0.6 * unit(mix64(seed ^ 0x6A09E667F3BCC908L));
        double start = startOf(market).doubleValue();
        if (market.kind() == MarketKind.ODDS) {
            double logit = StrictMath.log(start / (1.0 - start)) + ODDS_SIGMA * volatility * b;
            double p = 1.0 / (1.0 + StrictMath.exp(-logit));
            BigDecimal onGrid = BigDecimal.valueOf(p).divide(ODDS_GRID, 0, RoundingMode.HALF_UP).multiply(ODDS_GRID);
            if (onGrid.compareTo(ODDS_GRID) < 0) {
                return ODDS_GRID;
            }
            BigDecimal top = BigDecimal.ONE.subtract(ODDS_GRID);
            return onGrid.compareTo(top) > 0 ? top : onGrid;
        }
        double price = start * StrictMath.exp(PERP_SIGMA * volatility * b);
        BigDecimal rounded = BigDecimal.valueOf(price).setScale(market.priceDecimals(), RoundingMode.HALF_UP);
        // a price that would round to zero cannot happen within ±10% of a start that lotExpFor sized
        return rounded.signum() > 0 ? rounded : BigDecimal.ONE.movePointLeft(market.priceDecimals());
    }

    /**
     * The price the walk of this market starts from: {@code "start"} in its {@code sourceMeta}
     * (what {@code SyntheticCatalog} writes), else the built-in table by symbol, else for an ODDS
     * market a probability between 0.3 and 0.7 drawn from its id.
     */
    public static BigDecimal startOf(Market market) {
        if (market.sourceMeta() != null) {
            try {
                BigDecimal start = Dec.of(JSON.readTree(market.sourceMeta()).path("start"));
                if (start != null && start.signum() > 0) {
                    return start;
                }
            } catch (Exception ignored) {
                // not JSON or no start: fall through to the built-in values
            }
        }
        if (market.kind() == MarketKind.ODDS) {
            return oddsStart(market.id());
        }
        return SyntheticParams.forSymbol(market.symbol()).start();
    }

    /** A start probability in [0.30, 0.70] on a 0.005 grid, from the market id. */
    public static BigDecimal oddsStart(String marketId) {
        double u = unit(mix64(seed(marketId) ^ 0x5DEECE66DL));
        return BigDecimal.valueOf(0.30 + 0.40 * u).divide(new BigDecimal("0.005"), 0, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("0.005"));
    }

    /** FNV-1a over the id's UTF-8 bytes. */
    static long seed(String marketId) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : marketId.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * A standard Brownian bridge over {@code [0, PERIOD_SECONDS]} (zero at both ends) at second
     * {@code k}, by descending the dyadic intervals: the midpoint of {@code [lo, hi]} is the mean of
     * the ends plus a Gaussian of standard deviation {@code sqrt(hi − lo) / 2}.
     */
    static double bridge(long seed, long k) {
        long lo = 0;
        long hi = PERIOD_SECONDS;
        double atLo = 0.0;
        double atHi = 0.0;
        while (hi - lo > 1) {
            long mid = lo + (hi - lo) / 2;
            double atMid = 0.5 * (atLo + atHi) + 0.5 * StrictMath.sqrt((double) (hi - lo)) * gaussian(seed, lo, hi);
            if (k == mid) {
                return atMid;
            }
            if (k < mid) {
                hi = mid;
                atHi = atMid;
            } else {
                lo = mid;
                atLo = atMid;
            }
        }
        return atLo; // k == lo == 0: the only second the descent does not meet as a midpoint
    }

    /** A standard normal drawn (Box–Muller) from a hash of the seed and the interval. */
    private static double gaussian(long seed, long lo, long hi) {
        long h1 = mix64(seed ^ (lo * 0x9E3779B97F4A7C15L) ^ (hi * 0xC2B2AE3D27D4EB4FL));
        long h2 = mix64(h1 + 0x9E3779B97F4A7C15L);
        double u1 = Math.max(unit(h1), 1e-300);
        double u2 = unit(h2);
        return StrictMath.sqrt(-2.0 * StrictMath.log(u1)) * StrictMath.cos(2.0 * StrictMath.PI * u2);
    }

    /** The finalizer of splitmix64: a well-mixed 64-bit hash of {@code z}. */
    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 53 bits of {@code bits} as a double in [0, 1). */
    private static double unit(long bits) {
        return (bits >>> 11) * 0x1.0p-53;
    }
}
