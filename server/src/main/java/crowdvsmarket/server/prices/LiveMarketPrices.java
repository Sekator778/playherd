package crowdvsmarket.server.prices;

import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jboss.logging.Logger;

/**
 * The live {@link MarketPrices} (mode=live): boundary prices, round paths and the lobby quote, all
 * from {@link TickStore}'s ticks (docs/research/markets_architecture.md §3.2). It replaces the
 * fallback {@link NoMarketPrices}.
 *
 * <p><b>Boundary price {@code P(t)}</b> — the last tick with {@code at <= t} that is not older than
 * the feed's {@code staleMs} at {@code t}. {@code open = P(start)}, {@code close = P(end)}, hence the
 * close of one round is the open of the next (Р-40).
 *
 * <p><b>A hole</b> — a boundary price that is missing, or two neighbouring ticks of the window
 * (the tick behind {@code P(start)} and the one behind {@code P(end)} included) more than the feed's
 * {@code maxGapMs} apart. {@link #resolve} then asks the source for history of that range once
 * ({@link PriceSource#history}), merges what it returns into the hole — never over a tick that is
 * already there — and looks again; a hole that is still there is a {@link PriceUnavailableException},
 * which the scheduler retries and, an hour after the round's end, answers by cancelling the round
 * with a refund (Р-23). History is only fetched for a market once per
 * {@link PricesConfig#backfillCooldownMs() cooldown}: the scheduler retries every second and a
 * source that is down must not be asked every second.
 */
@ApplicationScoped
public class LiveMarketPrices implements MarketPrices {

    private static final Logger LOG = Logger.getLogger(LiveMarketPrices.class);

    /** History from a candle source has a one-minute resolution: ask a minute before the first hole. */
    static final long HISTORY_PAD_MS = 60_000L;

    /** The limits of a market whose feed is not among the active ones (a stale tick after a config change). */
    private static final PriceSource.Feed FALLBACK_FEED =
            new PriceSource.Feed("unknown", 15_000L, 30_000L, m -> true);

    private final TickStore store;
    private final PriceSources sources;
    private final PricesConfig config;
    private final LongSupplier clock;
    private final ThrottledLog warnings;
    private final ConcurrentHashMap<String, Long> lastBackfillMs = new ConcurrentHashMap<>();

    @Inject
    public LiveMarketPrices(TickStore store, PriceSources sources, PricesConfig config) {
        this(store, sources, config, System::currentTimeMillis);
    }

    LiveMarketPrices(TickStore store, PriceSources sources, PricesConfig config, LongSupplier clock) {
        this.store = store;
        this.sources = sources;
        this.config = config;
        this.clock = clock;
        this.warnings = new ThrottledLog(LOG, clock, 60_000L);
    }

    @Override
    public Optional<PriceQuote> latest(String marketId) {
        Optional<PricePoint> last = store.last(marketId);
        if (last.isEmpty()) {
            return Optional.empty();
        }
        PriceSource.Feed feed = store.feedOf(marketId).flatMap(sources::bindingById).map(PriceSources.Binding::feed)
                .orElse(FALLBACK_FEED);
        boolean stale = clock.getAsLong() - last.get().atMs() > feed.staleMs();
        return Optional.of(new PriceQuote(last.get().raw(), last.get().atMs(), store.change24h(marketId), stale));
    }

    @Override
    public List<PricePoint> series(String marketId, long fromMs, long toMs) {
        return store.series(marketId, fromMs, toMs);
    }

    @Override
    public RoundPriceData resolve(Market market, RoundWindow window) throws PriceUnavailableException {
        requireSameMarket(market, window);
        PriceSources.Binding binding = sources.bindingFor(market).orElse(null);
        PriceSource.Feed feed = binding == null ? FALLBACK_FEED : binding.feed();
        Assessment first = assess(market, window, feed);
        if (first.holes().isEmpty()) {
            return first.toData(market, window);
        }
        if (binding != null) {
            backfill(market, window, binding.source(), first);
        }
        Assessment second = assess(market, window, feed);
        if (!second.holes().isEmpty()) {
            throw new PriceUnavailableException("no price for " + market.id() + " " + window.roundId() + ": "
                    + second.describe());
        }
        return second.toData(market, window);
    }

    @Override
    public Optional<RoundPriceData> soFar(Market market, RoundWindow window, long nowMs) {
        requireSameMarket(market, window);
        long start = window.startMs();
        long upTo = Math.min(nowMs, window.endMs());
        if (upTo < start) {
            return Optional.empty();
        }
        PriceSource.Feed feed = sources.bindingFor(market).map(PriceSources.Binding::feed).orElse(FALLBACK_FEED);
        TickRing.Window w = store.window(market.id(), start, upTo);
        if (!w.hasFloor() || start - w.floorAtMs() > feed.staleMs()) {
            return Optional.empty();
        }
        try {
            RoundPath.Folded folded = RoundPath.fold(market, start, window.endMs(), upTo, w.floorPx(), w.atMs(), w.px());
            long sourceTs = w.atMs().length > 0 ? w.atMs()[w.atMs().length - 1] : w.floorAtMs();
            return Optional.of(new RoundPriceData(RoundPath.engine(market, w.floorPx()),
                    RoundPath.engine(market, folded.closePx()), folded.candles(), sourceTs));
        } catch (IllegalArgumentException e) { // a price the engine's candle rejects: show nothing, not an error
            warnings.failure("running path " + market.id(), "no running path for " + market.id() + " "
                    + window.roundId() + ": " + e.getMessage(), null); // every poll of every client lands here
            return Optional.empty();
        }
    }

    /** What the ticks of a round window say: its holes, or the data to settle with. */
    private record Assessment(TickRing.Window w, long start, long end, PriceSource.Feed feed, List<long[]> holes) {

        RoundPriceData toData(Market market, RoundWindow window) {
            long closePx = w.atMs().length > 0 ? w.px()[w.px().length - 1] : w.floorPx();
            long sourceTs = w.atMs().length > 0 ? w.atMs()[w.atMs().length - 1] : w.floorAtMs();
            try {
                RoundPath.Folded folded = RoundPath.fold(market, start, end, end, w.floorPx(), w.atMs(), w.px());
                return new RoundPriceData(RoundPath.engine(market, w.floorPx()), RoundPath.engine(market, closePx),
                        folded.candles(), sourceTs);
            } catch (IllegalArgumentException e) { // a lot price that rounds to zero
                throw new PriceUnavailableException("price of " + market.id() + " " + window.roundId()
                        + " is outside what the engine can hold: " + e.getMessage());
            }
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            for (long[] hole : holes) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append("no tick between ").append(Instant.ofEpochMilli(hole[0])).append(" and ")
                        .append(Instant.ofEpochMilli(Math.min(hole[1], end)));
            }
            return sb + " (feed " + feed.id() + ", stale after " + feed.staleMs() + " ms, gap over "
                    + feed.maxGapMs() + " ms)";
        }
    }

    /**
     * The holes of the window as open intervals {@code (a, b)} of time in which a tick is missing:
     * the lead-in to the first tick when {@code P(start)} is missing, every pair of neighbours too
     * far apart, and the tail to {@code end} when {@code P(end)} is missing. Overlapping holes are
     * merged. No holes — the window can be settled from memory.
     */
    private Assessment assess(Market market, RoundWindow window, PriceSource.Feed feed) {
        long start = window.startMs();
        long end = window.endMs();
        TickRing.Window w = store.window(market.id(), start, end);
        long[] at = w.atMs();
        List<long[]> holes = new ArrayList<>();
        boolean startOk = w.hasFloor() && start - w.floorAtMs() <= feed.staleMs();
        if (!startOk) {
            long a = w.hasFloor() ? w.floorAtMs() : start - feed.staleMs() - HISTORY_PAD_MS;
            long b = at.length > 0 ? at[0] : end + 1;
            holes.add(new long[] {a, b});
        }
        long previous = startOk ? w.floorAtMs() : Long.MIN_VALUE;
        for (long t : at) {
            if (previous != Long.MIN_VALUE && t - previous > feed.maxGapMs()) {
                holes.add(new long[] {previous, t});
            }
            previous = t;
        }
        long lastTick = at.length > 0 ? at[at.length - 1] : (w.hasFloor() ? w.floorAtMs() : Long.MIN_VALUE);
        if (lastTick == Long.MIN_VALUE || end - lastTick > feed.staleMs()) {
            long a = lastTick != Long.MIN_VALUE ? lastTick : start - feed.staleMs() - HISTORY_PAD_MS;
            holes.add(new long[] {a, end + 1});
        }
        return new Assessment(w, start, end, feed, merge(holes));
    }

    private static List<long[]> merge(List<long[]> holes) {
        holes.sort(Comparator.comparingLong(h -> h[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] h : holes) {
            long[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && h[0] < last[1]) {
                last[1] = Math.max(last[1], h[1]);
            } else {
                merged.add(new long[] {h[0], h[1]});
            }
        }
        return merged;
    }

    /** Asks the source once per cooldown for the range of the holes and merges the points that fall into them. */
    private void backfill(Market market, RoundWindow window, PriceSource source, Assessment assessment) {
        long now = clock.getAsLong();
        boolean[] allowed = {false};
        lastBackfillMs.compute(market.id(), (id, last) -> {
            if (last == null || now - last >= config.backfillCooldownMs()) {
                allowed[0] = true;
                return now;
            }
            return last;
        });
        if (!allowed[0]) {
            return; // asked a moment ago: the scheduler retries every second, the source is not asked every second
        }
        long from = assessment.holes().get(0)[0];
        long to = Math.min(assessment.holes().get(assessment.holes().size() - 1)[1], window.endMs());
        List<PricePoint> history;
        try {
            history = source.history(market, from, to);
        } catch (Exception e) {
            LOG.warnf("price history of %s for %s [%d, %d] failed: %s", market.id(), window.roundId(), from, to,
                    e.toString());
            return;
        }
        List<PricePoint> inHoles = new ArrayList<>();
        for (PricePoint p : history) {
            if (insideAHole(assessment.holes(), p.atMs()) && usable(market, p)) {
                inHoles.add(p);
            }
        }
        int added = store.mergeHistory(market.id(), inHoles);
        LOG.infof("price history for %s %s: %d point(s) from the source, %d added into %d hole(s)", market.id(),
                window.roundId(), history.size(), added, assessment.holes().size());
    }

    private static boolean insideAHole(List<long[]> holes, long atMs) {
        for (long[] hole : holes) {
            if (atMs > hole[0] && atMs < hole[1]) {
                return true;
            }
        }
        return false;
    }

    /** A history price must survive the same checks as a live one, including the engine's lot range. */
    private static boolean usable(Market market, PricePoint p) {
        if (!Px.isStorable(p.raw())) {
            return false;
        }
        return market.toEngine(p.raw()).signum() > 0;
    }

    private static void requireSameMarket(Market market, RoundWindow window) {
        if (!market.id().equals(window.market())) {
            throw new IllegalArgumentException("window of " + window.market() + " given with market " + market.id());
        }
    }
}
