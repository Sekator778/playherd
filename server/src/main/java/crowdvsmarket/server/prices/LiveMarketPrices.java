package crowdvsmarket.server.prices;

import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.markets.Market;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
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
 * close of one round is the open of the next (Р-40). That equality must survive everything that
 * happens to the ticks afterwards, so a tick that is fresh at a boundary <i>is</i> the price at that
 * boundary: history is never merged at or before {@code start} when the tick before it is fresh
 * (below), a merge only ever adds ticks, it never replaces one, and a round that settled pins its two
 * boundary prices at exactly {@code start} and {@code end} ({@link #resolve}'s {@code settled}) so that
 * not even a merge for another, longer window can move them — and nothing at all is merged behind the
 * latest boundary pinned for the market ({@link TickStore#pinBoundary}).
 *
 * <p><b>A pin is not a tick</b> ({@link TickRing#PIN}): {@link #latest} and {@link #series} never show
 * it, so during an outage it cannot make an old price look fresh to a bot or the bet slip; and the
 * {@code sourceTs} of a settlement is the time of the <i>real</i> tick its price comes from, whether the
 * last tick at the boundary is that tick itself or a pin or a quiet carry standing for it
 * ({@link TickRing.Window}).
 *
 * <p><b>A hole</b> — a boundary price that is missing, or two neighbouring ticks of the window
 * more than the feed's {@code maxGapMs} apart. The tick behind {@code P(start)} counts as sitting at
 * {@code start} itself (it is the price there), the tick behind {@code P(end)} at its own time.
 * {@link #resolve} then asks the source for history of that range once
 * ({@link PriceSource#historyOf}), merges what it returns into the hole — never over a tick that is
 * already there, and never at or before {@code start} when {@code P(start)} is already known — and
 * looks again; a hole that is still there is a {@link PriceUnavailableException}, which the
 * scheduler retries and, an hour after the round's end, answers by cancelling the round with a
 * refund (Р-23). History is only fetched for a market once per
 * {@link PricesConfig#backfillCooldownMs() cooldown}: the scheduler retries every second and a
 * source that is down must not be asked every second.
 *
 * <p><b>What history may fill in.</b> That depends on what the source's history <i>is</i>
 * ({@link PriceSource#historyKind}). A source whose history is the same kind of number as its ticks
 * (Polymarket's midpoint buckets) fills any hole. A source whose history is another one (Hyperliquid: candles are
 * trade prices, the ticks are mark prices, and a quiet asset's last trade can be a percent away from its
 * mark) may only be the <i>past of the first poll</i> — points older than every live tick of the market —
 * and never a stretch between two polls: a trade price put between marks made a flat mark settle as DOWN
 * and put a dip into a path that the liquidation check used. What such a source still says about a hole
 * between live ticks is evidence for the quiet rule below: any trade inside the hole means the asset was
 * not quiet.
 *
 * <p><b>A quiet asset</b> (a stock outside its exchange's hours, an illiquid coin): the source may
 * have nothing at all for a hole, because nothing traded. The price of the last <i>live</i> tick then
 * continues in {@link #QUIET_CARRY_STEP_MS} steps ({@link TickRing#CARRIED}) when the source answered, had no
 * point inside the hole, the hole is at most {@link PricesConfig#quietCarryMaxMs()} long, the live tick on
 * its right shows the same price — or there is no tick on its right yet and the source's last trade (a
 * candle's close) is that very price — and, for a source whose history is not the same kind of number, the
 * tick on the left is a live one. If the price after the hole differs, nobody knows when it moved, and the
 * round waits (and, in the end, is refunded) rather than settling on an invented flat line; if the source
 * did not answer, nothing is carried either — the next try may bring the real points.
 *
 * <p>Locking (docs/research/markets_architecture.md §3.2, the engine lock): {@link #resolve} may
 * block on HTTP and holds no lock while it does; {@link #latest}, {@link #series} and
 * {@link #soFar} only copy slices out of the rings, each for the length of one array copy. Nothing
 * here ever calls the market registry, so none of it can wait for the engine lock that the
 * registry's listener takes.
 */
@ApplicationScoped
public class LiveMarketPrices implements MarketPrices {

    private static final Logger LOG = Logger.getLogger(LiveMarketPrices.class);

    /** History from a candle source has a one-minute resolution: ask a minute before the first hole. */
    static final long HISTORY_PAD_MS = 60_000L;

    /** Spacing of the points that carry a quiet asset's last tick across a hole of its own: well inside every maximum gap. */
    static final long QUIET_CARRY_STEP_MS = 10_000L;

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
        Optional<PricePoint> last = store.last(marketId); // a live tick: never a pin, a carry or a history point
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
            return settled(market, window, first);
        }
        if (binding != null) {
            backfill(market, window, binding.source(), first);
        }
        Assessment second = assess(market, window, feed);
        if (!second.holes().isEmpty()) {
            throw new PriceUnavailableException("no price for " + market.id() + " " + window.roundId() + ": "
                    + second.describe());
        }
        return settled(market, window, second);
    }

    /**
     * The settlement data of a window whose boundary prices are complete — and the pin of those two
     * prices: when no tick sits exactly at {@code start} or {@code end}, a {@link TickRing#PIN} is inserted
     * there with the boundary price, so that {@code P(start)} and {@code P(end)} of this round can never be
     * changed by history merged later. Two rounds that share a boundary share nothing else than the ring, and
     * the other window to settle may be a longer one of the other series: the scheduler settles the DAILY
     * series before the HOURLY one in every pass and their boundaries coincide, so a day that ends where
     * an hour ends is settled — its hole around the previous hour's boundary filled from history, with
     * points before that boundary — just before that hour is, after the hour before it already closed on
     * the live tick that was fresh at the boundary: without the pin the next hour would open on a point
     * of the history (Р-40). A pin is memory only like every merge: a restart rebuilds the boundary price from
     * the live ticks, or asks the source for it again — and the core takes the opening price of a round from
     * the persisted close of the previous one.
     */
    private RoundPriceData settled(Market market, RoundWindow window, Assessment ok) {
        RoundPriceData data = ok.toData(market, window);
        TickRing.Window w = ok.w();
        long closePx = w.atMs().length > 0 ? w.px()[w.px().length - 1] : w.floorPx();
        store.pinBoundary(market.id(), ok.start(), w.floorPx());
        store.pinBoundary(market.id(), ok.end(), closePx);
        return data;
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
            return Optional.of(new RoundPriceData(RoundPath.engine(market, w.floorPx()),
                    RoundPath.engine(market, folded.closePx()), folded.candles(), sourceTsOf(w)));
        } catch (IllegalArgumentException e) { // a price the engine's candle rejects: show nothing, not an error
            warnings.failure("running path " + market.id(), "no running path for " + market.id() + " "
                    + window.roundId() + ": " + e.getMessage(), null); // every poll of every client lands here
            return Optional.empty();
        }
    }

    /**
     * The time of the real tick the last price of the window comes from: the newest real
     * ({@link TickRing#isReal}) tick of the window, else the real tick behind the floor. A pin or a carried
     * tick is a copy of a price seen earlier and never the time the price was seen.
     */
    static long sourceTsOf(TickRing.Window w) {
        for (int i = w.atMs().length - 1; i >= 0; i--) {
            if (TickRing.isReal(w.kind()[i])) {
                return w.atMs()[i];
            }
        }
        if (w.hasFloor()) {
            return w.floorSourceAtMs();
        }
        return w.atMs().length > 0 ? w.atMs()[w.atMs().length - 1] : 0L;
    }

    /**
     * A stretch of a round window in which ticks are missing: the open interval
     * {@code (fromMs, toMs)}, with the price of the tick on either side when there is one
     * ({@link Px} units; {@code null} for the head of a window that has no tick before it, or the
     * tail of one that has no tick after it), and whether the tick on the left is a copy of a <i>live</i> one
     * (it stands for a poll's answer, not for a history point).
     */
    private record Hole(long fromMs, long toMs, Long leftPx, Long rightPx, boolean leftLive) {

        /** Ticks on both sides, at the same price: a quiet asset's price did not move across the hole. */
        boolean flatBothSides() {
            return leftPx != null && leftPx.equals(rightPx);
        }

        boolean contains(long atMs) {
            return atMs > fromMs && atMs < toMs;
        }
    }

    /** What the ticks of a round window say: its holes, or the data to settle with. */
    private record Assessment(TickRing.Window w, long start, long end, PriceSource.Feed feed, List<Hole> holes) {

        RoundPriceData toData(Market market, RoundWindow window) {
            long closePx = w.atMs().length > 0 ? w.px()[w.px().length - 1] : w.floorPx();
            long sourceTs = sourceTsOf(w);
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
            for (Hole hole : holes) {
                if (sb.length() > 0) {
                    sb.append("; ");
                }
                sb.append("no tick between ").append(Instant.ofEpochMilli(hole.fromMs())).append(" and ")
                        .append(Instant.ofEpochMilli(Math.min(hole.toMs(), end)));
            }
            return sb + " (feed " + feed.id() + ", stale after " + feed.staleMs() + " ms, gap over "
                    + feed.maxGapMs() + " ms)";
        }
    }

    /**
     * The holes of the window: the lead-in to the first tick when {@code P(start)} is missing, every
     * pair of neighbours too far apart, and the tail to {@code end} when {@code P(end)} is missing.
     * Overlapping holes are merged. No holes — the window can be settled from memory.
     *
     * <p>A tick that is fresh at {@code start} is the price at {@code start}: the gap behind it is
     * measured from {@code start}, not from the tick's own, earlier time, so that a hole — and the
     * history merged into it — can never reach back over the boundary that a neighbouring round
     * (which ended there) may have settled on already (Р-40).
     */
    private Assessment assess(Market market, RoundWindow window, PriceSource.Feed feed) {
        long start = window.startMs();
        long end = window.endMs();
        TickRing.Window w = store.window(market.id(), start, end);
        long[] at = w.atMs();
        long[] px = w.px();
        byte[] kind = w.kind();
        List<Hole> holes = new ArrayList<>();
        boolean startOk = w.hasFloor() && start - w.floorAtMs() <= feed.staleMs();
        long floorAt = startOk ? Math.max(w.floorAtMs(), start) : w.floorAtMs();
        boolean floorLive = w.hasFloor() && w.floorSourceKind() == TickRing.LIVE;
        if (!startOk) {
            long a = w.hasFloor() ? floorAt : start - feed.staleMs() - HISTORY_PAD_MS;
            long b = at.length > 0 ? at[0] : end + 1;
            Long left = w.hasFloor() ? Long.valueOf(w.floorPx()) : null;
            Long right = at.length > 0 ? Long.valueOf(px[0]) : pxAfter(market, end);
            holes.add(new Hole(a, b, left, right, floorLive));
        }
        long previousAt = startOk ? floorAt : Long.MIN_VALUE;
        long previousPx = startOk ? w.floorPx() : 0L;
        boolean previousLive = floorLive; // the real tick a copy at the head of the window stands for is the floor's
        for (int i = 0; i < at.length; i++) {
            if (previousAt != Long.MIN_VALUE && at[i] - previousAt > feed.maxGapMs()) {
                holes.add(new Hole(previousAt, at[i], previousPx, px[i], previousLive));
            }
            previousAt = at[i];
            previousPx = px[i];
            if (TickRing.isReal(kind[i])) { // a copy keeps standing for the real tick before it
                previousLive = kind[i] == TickRing.LIVE;
            }
        }
        long lastAt = at.length > 0 ? at[at.length - 1] : (w.hasFloor() ? w.floorAtMs() : Long.MIN_VALUE);
        if (lastAt == Long.MIN_VALUE || end - lastAt > feed.staleMs()) {
            // the tail starts at the newest tick of the window; with none in it, at the floor tick (from
            // `start` on when that is fresh, see above); with no tick at all, in the past
            long a = at.length > 0 ? at[at.length - 1]
                    : (w.hasFloor() ? floorAt : start - feed.staleMs() - HISTORY_PAD_MS);
            Long left = at.length > 0 ? Long.valueOf(px[at.length - 1])
                    : (w.hasFloor() ? Long.valueOf(w.floorPx()) : null);
            boolean leftLive = at.length > 0 ? previousLive : floorLive;
            holes.add(new Hole(a, end + 1, left, pxAfter(market, end), leftLive));
        }
        return new Assessment(w, start, end, feed, merge(holes));
    }

    /** The price of the first tick after the window, if the source has come back since; else null. */
    private Long pxAfter(Market market, long end) {
        long[] tick = store.tickAfter(market.id(), end);
        return tick == null ? null : tick[1];
    }

    private static List<Hole> merge(List<Hole> holes) {
        holes.sort(Comparator.comparingLong(Hole::fromMs));
        List<Hole> merged = new ArrayList<>();
        for (Hole h : holes) {
            Hole last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last == null || h.fromMs() >= last.toMs()) {
                merged.add(h);
            } else if (h.toMs() > last.toMs()) { // overlapping: one hole, from the first's start to the later end
                merged.set(merged.size() - 1,
                        new Hole(last.fromMs(), h.toMs(), last.leftPx(), h.rightPx(), last.leftLive()));
            }
        }
        return merged;
    }

    /**
     * Asks the source once per cooldown for the range of the holes and merges the points that fall
     * into them — as far as the kind of the source's history allows (class comment); for a hole the source
     * had nothing for, see {@link #carryQuietHoles}.
     */
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
        List<Hole> holes = assessment.holes();
        // behind a boundary a round settled on nothing is merged any more: do not even ask for that stretch
        long pinned = store.latestPin(market.id()).orElse(Long.MIN_VALUE);
        long from = Math.max(holes.get(0).fromMs(), pinned);
        long to = Math.min(holes.get(holes.size() - 1).toMs(), window.endMs());
        if (from >= to) {
            return;
        }
        boolean tradePrices = source.historyKind() == PriceSource.HistoryKind.TRADE_PRICES;
        PriceSource.History answer;
        try {
            // no lock is held: this blocks on HTTP; the boundaries let a candle source build their prices itself
            answer = source.historyOf(market, from, to, window.startMs(), window.endMs());
        } catch (Exception e) {
            LOG.warnf("price history of %s for %s [%d, %d] failed: %s", market.id(), window.roundId(), from, to,
                    e.toString());
            return;
        }
        List<PricePoint> inHoles = new ArrayList<>();
        int[] perHole = new int[holes.size()];
        for (PricePoint p : answer.points()) {
            int i = holeAt(holes, p.atMs());
            if (i >= 0 && usable(market, p)) {
                inHoles.add(p);
                perHole[i]++; // for trade prices too: they are not merged, but they say the asset traded here
            }
        }
        int added = store.mergeHistory(market.id(), inHoles, tradePrices);
        LOG.infof("price history for %s %s: %d point(s) from the source, %d added into %d hole(s)%s", market.id(),
                window.roundId(), answer.points().size(), added, holes.size(),
                tradePrices && !inHoles.isEmpty() && added < inHoles.size()
                        ? " (trade prices are only the past of the first poll, never put between live ticks)" : "");
        carryQuietHoles(market, window, holes, perHole, answer.lastTrade(), tradePrices);
    }

    /**
     * A quiet asset: for a hole in which the source had no point, that is not longer than
     * {@link PricesConfig#quietCarryMaxMs()} and whose neighbours say the price did not move, the price of the
     * last tick continues in {@link #QUIET_CARRY_STEP_MS} steps up to the next tick (or, at the tail, up to
     * {@code end}). "Did not move" is: a live tick on the right with the same price — or, with none yet, the
     * source's last trade (a candle's close) being the very price of the left tick. With
     * {@code tradePrices} the tick on the left must be a live one: the carried price is a mark, never a
     * trade price. Only called after the source answered.
     */
    private void carryQuietHoles(Market market, RoundWindow window, List<Hole> holes, int[] perHole,
                                 Optional<PricePoint> lastTrade, boolean tradePrices) {
        List<PricePoint> carried = new ArrayList<>();
        for (int i = 0; i < holes.size(); i++) {
            Hole hole = holes.get(i);
            if (perHole[i] > 0 || hole.leftPx() == null || (tradePrices && !hole.leftLive())) {
                continue;
            }
            long spanEnd = Math.min(hole.toMs(), window.endMs() + 1);
            if (spanEnd - hole.fromMs() > config.quietCarryMaxMs()) {
                continue; // an outage that long is no flat line any more
            }
            BigDecimal price = Px.decode(hole.leftPx());
            boolean unchanged;
            if (hole.rightPx() != null) {
                unchanged = hole.flatBothSides();
            } else {
                unchanged = tradePrices && lastTrade.isPresent() && lastTrade.get().raw().compareTo(price) == 0;
            }
            if (!unchanged) {
                continue;
            }
            for (long at = hole.fromMs() + QUIET_CARRY_STEP_MS; at < hole.toMs() && at <= window.endMs();
                    at += QUIET_CARRY_STEP_MS) {
                carried.add(new PricePoint(at, price));
            }
        }
        if (!carried.isEmpty()) {
            int added = store.mergeCarried(market.id(), carried);
            LOG.infof("price history for %s %s: nothing from the source for a hole whose price did not move, carried "
                    + "the last tick forward (%d point(s))", market.id(), window.roundId(), added);
        }
    }

    /** The index of the hole the time lies strictly inside of, or -1. */
    private static int holeAt(List<Hole> holes, long atMs) {
        for (int i = 0; i < holes.size(); i++) {
            if (holes.get(i).contains(atMs)) {
                return i;
            }
        }
        return -1;
    }

    /** A history price must survive the same checks as a live one, including the engine's lot range. */
    static boolean usable(Market market, PricePoint p) {
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
