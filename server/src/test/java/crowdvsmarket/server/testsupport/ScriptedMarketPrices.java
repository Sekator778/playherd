package crowdvsmarket.server.testsupport;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.prices.MarketPrices;
import crowdvsmarket.server.prices.PricePoint;
import crowdvsmarket.server.prices.PriceQuote;
import crowdvsmarket.server.prices.PriceUnavailableException;
import crowdvsmarket.server.prices.RoundPriceData;
import io.quarkus.test.Mock;
import jakarta.enterprise.context.ApplicationScoped;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link MarketPrices} for tests: serves exactly the prices a test scripts and never touches a
 * network. Every {@code @QuarkusTest} in this module gets it instead of the production
 * implementation ({@link Mock} = an alternative with priority); it only matters in tests that run
 * {@code herd.mode=live}, the one mode that asks {@link MarketPrices} for anything.
 *
 * <p>Prices are scripted RAW (what a source quotes) and converted with the market's own lot
 * exponent, like the real pipeline does ({@link Market#toEngine}). A window with no script resolves
 * flat at the market's default price; a market with neither a script nor a default price is
 * unavailable ({@link PriceUnavailableException}), exactly like a market whose source is down.
 * {@link #reset} between tests.
 */
@Mock
@ApplicationScoped
public class ScriptedMarketPrices implements MarketPrices {

    private final Map<String, PriceQuote> latest = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> defaults = new ConcurrentHashMap<>();
    private final Map<String, RoundPriceData> scripts = new ConcurrentHashMap<>();
    private final Map<String, RuntimeException> resolveFailures = new ConcurrentHashMap<>();
    private final Map<String, RoundPriceData> soFar = new ConcurrentHashMap<>();

    public void reset() {
        latest.clear();
        defaults.clear();
        scripts.clear();
        resolveFailures.clear();
        soFar.clear();
    }

    /** A script belongs to one window of one series: the same start can be a window of both intervals. */
    private static String key(String marketId, crowdvsmarket.engine.market.Interval interval, long windowStartMs) {
        return marketId + "/" + interval + "@" + windowStartMs;
    }

    /** The market's latest tick (fresh) and the price an unscripted window resolves at. */
    public void setPrice(Market market, String raw) {
        defaults.put(market.id(), new BigDecimal(raw));
        latest.put(market.id(), new PriceQuote(new BigDecimal(raw), System.currentTimeMillis(), null, false));
    }

    /** The market's latest tick is older than the source's staleness limit. */
    public void setStale(Market market, String raw) {
        latest.put(market.id(), new PriceQuote(new BigDecimal(raw), System.currentTimeMillis() - 60_000L, null, true));
    }

    /** No tick at all for the market. */
    public void clearLatest(Market market) {
        latest.remove(market.id());
    }

    /** Scripts one window: open, close and a single candle spanning {@code rawLow}..{@code rawHigh}. */
    public void script(Market market, RoundWindow window, String rawOpen, String rawClose, String rawLow,
                       String rawHigh) {
        BigDecimal open = market.toEngine(new BigDecimal(rawOpen));
        BigDecimal close = market.toEngine(new BigDecimal(rawClose));
        Candle candle = new Candle(window.startMs(), window.endMs() - 1, open, market.toEngine(new BigDecimal(rawHigh)),
                market.toEngine(new BigDecimal(rawLow)), close, BigDecimal.ZERO, 0);
        scripts.put(key(market.id(), window.interval(), window.startMs()),
                new RoundPriceData(open, close, List.of(candle), window.endMs() - 1));
    }

    /** Resolving {@code window} of the market throws {@code failure}, until {@link #reset}. */
    public void failResolve(Market market, RoundWindow window, RuntimeException failure) {
        resolveFailures.put(key(market.id(), window.interval(), window.startMs()), failure);
    }

    /** Every resolve of the market throws {@link PriceUnavailableException}, until {@link #reset}. */
    public void failAllResolves(Market market, RuntimeException failure) {
        resolveFailures.put(market.id(), failure);
    }

    /** What {@code soFar} returns for the market; none scripted means "unknown yet" (empty). */
    public void setSoFar(Market market, String rawOpen, String rawNow) {
        BigDecimal open = market.toEngine(new BigDecimal(rawOpen));
        BigDecimal now = market.toEngine(new BigDecimal(rawNow));
        long at = System.currentTimeMillis();
        Candle candle = new Candle(at, at, open, open.max(now), open.min(now), now, BigDecimal.ZERO, 0);
        soFar.put(market.id(), new RoundPriceData(open, now, List.of(candle), at));
    }

    @Override
    public Optional<PriceQuote> latest(String marketId) {
        return Optional.ofNullable(latest.get(marketId));
    }

    @Override
    public List<PricePoint> series(String marketId, long fromMs, long toMs) {
        return List.of();
    }

    @Override
    public RoundPriceData resolve(Market market, RoundWindow window) throws PriceUnavailableException {
        RuntimeException failure = resolveFailures.getOrDefault(
                key(market.id(), window.interval(), window.startMs()), resolveFailures.get(market.id()));
        if (failure != null) {
            throw failure;
        }
        RoundPriceData scripted = scripts.get(key(market.id(), window.interval(), window.startMs()));
        if (scripted != null) {
            return scripted;
        }
        BigDecimal raw = defaults.get(market.id());
        if (raw == null) {
            throw new PriceUnavailableException("no scripted price for " + market.id());
        }
        BigDecimal flat = market.toEngine(raw);
        return new RoundPriceData(flat, flat,
                List.of(new Candle(window.startMs(), window.endMs() - 1, flat, flat, flat, flat, BigDecimal.ZERO, 0)),
                window.endMs() - 1);
    }

    @Override
    public Optional<RoundPriceData> soFar(Market market, RoundWindow window, long nowMs) {
        return Optional.ofNullable(soFar.get(market.id()));
    }
}
