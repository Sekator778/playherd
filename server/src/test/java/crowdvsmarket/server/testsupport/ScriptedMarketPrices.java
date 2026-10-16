package crowdvsmarket.server.testsupport;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.prices.MarketPrices;
import crowdvsmarket.server.prices.PricePoint;
import crowdvsmarket.server.prices.PriceQuote;
import crowdvsmarket.server.prices.PriceUnavailableException;
import crowdvsmarket.server.prices.RoundPriceData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link MarketPrices} for tests: serves exactly the prices a test scripts and never touches a
 * network. It is an {@link Alternative} that a test PROFILE opts into ({@code
 * QuarkusTestProfile#getEnabledAlternatives}, as {@link LiveMarketsProfile} does) — it used to be a
 * global {@code @Mock}, which replaced the production implementation in EVERY {@code @QuarkusTest}
 * and so hid the real {@code LiveMarketPrices} from the pipeline's own wiring test. A test that does
 * not ask for it gets the real bean. It only matters in tests that run {@code herd.mode=live}, the
 * one mode that asks {@link MarketPrices} for anything.
 *
 * <p>Prices are scripted RAW (what a source quotes) and converted with the market's own lot
 * exponent, like the real pipeline does ({@link Market#toEngine}). A window with no script resolves
 * flat at the market's default price; a market with neither a script nor a default price is
 * unavailable ({@link PriceUnavailableException}), exactly like a market whose source is down.
 * {@link #reset} between tests.
 */
@Alternative
@ApplicationScoped
public class ScriptedMarketPrices implements MarketPrices {

    private final Map<String, PriceQuote> latest = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> defaults = new ConcurrentHashMap<>();
    private final Map<String, RoundPriceData> scripts = new ConcurrentHashMap<>();
    private final Map<String, RuntimeException> resolveFailures = new ConcurrentHashMap<>();
    private final Map<String, RoundPriceData> soFar = new ConcurrentHashMap<>();
    private final Map<String, List<PricePoint>> series = new ConcurrentHashMap<>();
    private final Map<String, List<crowdvsmarket.server.prices.PriceBar>> bars = new ConcurrentHashMap<>();
    private final Map<String, String> venues = new ConcurrentHashMap<>();
    private final List<String> opened = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, RuntimeException> openFailures = new ConcurrentHashMap<>();
    private final java.util.Set<String> otherVenueLive = ConcurrentHashMap.newKeySet();
    private final Map<String, RuntimeException> otherVenueFailures = new ConcurrentHashMap<>();
    private final List<String> otherVenueAsked = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void reset() {
        opened.clear();
        openFailures.clear();
        otherVenueLive.clear();
        otherVenueFailures.clear();
        otherVenueAsked.clear();
        venues.clear();
        series.clear();
        bars.clear();
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

    /** The market's latest tick exactly as a test describes it — price, time, 24-hour change, staleness. */
    public void setQuote(Market market, String raw, long asOfMs, Double change24h, boolean stale) {
        defaults.put(market.id(), new BigDecimal(raw));
        latest.put(market.id(), new PriceQuote(new BigDecimal(raw), asOfMs, change24h, stale));
    }

    /** The market's latest tick as {@link #setQuote} has it, and from {@code venue}: the backup exchange's, shown because the primary has none fresh (Р-76). */
    public void setQuote(Market market, String raw, long asOfMs, Double change24h, boolean stale, String venue) {
        defaults.put(market.id(), new BigDecimal(raw));
        latest.put(market.id(), new PriceQuote(new BigDecimal(raw), asOfMs, change24h, stale, venue));
    }

    /**
     * Every round of the market runs on {@code venue} (Р-76): {@link #decidedVenue} says so, and what {@link #resolve} and
     * {@link #soFar} hand over says it too. {@code null} takes it back: a market of one exchange, whose rounds have none.
     */
    public void setVenue(Market market, String venue) {
        if (venue == null) {
            venues.remove(market.id());
        } else {
            venues.put(market.id(), venue);
        }
    }

    /** The ticks {@link #series} serves for the market, oldest first (what the price buffer would hold). */
    public void setSeries(Market market, List<PricePoint> points) {
        series.put(market.id(), List.copyOf(points));
    }

    /** The finished candles {@link #bars} serves for the market, oldest first (what the candle read-model would hold). */
    public void setBars(Market market, List<crowdvsmarket.server.prices.PriceBar> candles) {
        bars.put(market.id(), List.copyOf(candles));
    }

    @Override
    public List<crowdvsmarket.server.prices.PriceBar> bars(String marketId, int minutes, long nowMs) {
        long current = Math.floorDiv(nowMs, 60_000L);
        return bars.getOrDefault(marketId, List.of()).stream()
                .filter(b -> b.openTimeMs() / 60_000L < current && b.openTimeMs() / 60_000L >= current - minutes).toList();
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

    /** Scripts one window with exactly {@code data} — for a source that hands over something odd (an
     *  open that is not positive, a path of too many candles). */
    public void scriptData(Market market, RoundWindow window, RoundPriceData data) {
        scripts.put(key(market.id(), window.interval(), window.startMs()), data);
    }

    /** Resolving {@code window} of the market throws {@code failure}, until {@link #reset}. */
    public void failResolve(Market market, RoundWindow window, RuntimeException failure) {
        resolveFailures.put(key(market.id(), window.interval(), window.startMs()), failure);
    }

    /** Every resolve of the market throws {@link PriceUnavailableException}, until {@link #reset}. */
    public void failAllResolves(Market market, RuntimeException failure) {
        resolveFailures.put(market.id(), failure);
    }

    /** The market's source is back: no resolve of it fails any more, whether one window's or every window's. */
    public void healResolves(Market market) {
        resolveFailures.keySet().removeIf(key -> key.equals(market.id()) || key.startsWith(market.id() + "/"));
    }

    /**
     * What {@link #otherVenueLive} answers for the market, until {@link #reset}: whether the exchange its round was not
     * decided on has a live tick now (Р-77). Not scripted means it has none.
     */
    public void setOtherVenueLive(Market market, boolean live) {
        if (live) {
            otherVenueLive.add(market.id());
        } else {
            otherVenueLive.remove(market.id());
        }
    }

    /** {@link #otherVenueLive} of the market throws {@code failure}, until {@link #reset}: a table that does not answer. */
    public void failOtherVenueLive(Market market, RuntimeException failure) {
        otherVenueFailures.put(market.id(), failure);
    }

    /** The ids of the rounds {@link #otherVenueLive} was asked about, in order, since {@link #reset}. */
    public List<String> otherVenueLiveAsked() {
        return List.copyOf(otherVenueAsked);
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
        return series.getOrDefault(marketId, List.of()).stream()
                .filter(p -> p.atMs() >= fromMs && p.atMs() < toMs).toList();
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
            return scripted.withVenue(venues.getOrDefault(market.id(), scripted.venue()));
        }
        BigDecimal raw = defaults.get(market.id());
        if (raw == null) {
            throw new PriceUnavailableException("no scripted price for " + market.id());
        }
        BigDecimal flat = market.toEngine(raw);
        return new RoundPriceData(flat, flat,
                List.of(new Candle(window.startMs(), window.endMs() - 1, flat, flat, flat, flat, BigDecimal.ZERO, 0)),
                window.endMs() - 1, venues.get(market.id()));
    }

    @Override
    public Optional<RoundPriceData> soFar(Market market, RoundWindow window, long nowMs) {
        return Optional.ofNullable(soFar.get(market.id())).map(data -> data.withVenue(venues.get(market.id())));
    }

    @Override
    public Optional<String> decidedVenue(Market market, RoundWindow window) {
        return Optional.ofNullable(venues.get(market.id()));
    }

    /** What a test scripted ({@link #setOtherVenueLive}); the ask is recorded and fails when told to. */
    @Override
    public boolean otherVenueLive(Market market, RoundWindow window, long nowMs) {
        otherVenueAsked.add(window.roundId());
        RuntimeException failure = otherVenueFailures.get(market.id());
        if (failure != null) {
            throw failure;
        }
        return otherVenueLive.contains(market.id());
    }

    /** Records the ask (what the scheduler does every tick for a market with a backup) and fails it when told to. */
    @Override
    public Optional<String> openRound(Market market, RoundWindow window, long nowMs) {
        opened.add(window.roundId());
        RuntimeException failure = openFailures.get(market.id());
        if (failure != null) {
            throw failure;
        }
        return decidedVenue(market, window);
    }

    /** The ids of the rounds {@link #openRound} was asked for, in order, since {@link #reset}. */
    public List<String> openedRounds() {
        return List.copyOf(opened);
    }

    /** {@link #openRound} of the market throws {@code failure}, until {@link #reset}. */
    public void failOpen(Market market, RuntimeException failure) {
        openFailures.put(market.id(), failure);
    }
}
