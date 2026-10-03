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
 * <p><b>A market with a backup exchange</b> (Р-76, {@link Instruments}) has a ring per venue — its own, and its shadow's
 * ({@code spot_BTC~kraken}) — and everything above holds per ring unchanged: staleness, holes, pins, the bridge, the quiet
 * carry. What is new is the choice of the venue, made once for each round when it opens ({@link #openRound}; the
 * rule is in {@link #chooseByRings}): the primary if it has a <i>live</i> tick fresh at the round's start, else the backup if
 * it has; else — no live tick on either side — whichever ring can price the start from what it holds (seeded history, a
 * carried tick, a pin: non-live evidence, all of one rank), the primary's first; else the round waits, and the first venue
 * that can price the start later — from history ({@link #healStart}), the primary first — is taken. The choice is written
 * once ({@link RoundVenues}) and never revisited: the round is settled — open,
 * close, path — from its venue's ring alone ({@link #resolve}, {@link #soFar}), and a venue that cannot price it leaves the
 * round to wait, and to be refunded an hour after its end, as any round without a price (Р-23); it is never moved to the
 * other venue. The next round starts at the price of whichever venue it opens on, which may differ from the close of
 * this one by the basis between the two venues (Р-40 holds within a venue). For the lobby, {@link #latest(Market)} shows
 * the primary's live tick while it is fresh and the backup's when it is not, saying which.
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
 * mark) is the <i>past of the first poll</i> — points older than every live tick of the market — and a
 * <i>bridge over a gap between two live ticks</i> (below), and nothing else: a trade price put between marks in
 * any other way made a flat mark settle as DOWN and put a dip into a path that the liquidation check used. What
 * such a source still says about a hole between live ticks is evidence for the quiet rule: any trade inside the
 * hole means the asset was not quiet.
 *
 * <p><b>The bridge</b> ({@link GapBridge}). A hole whose two neighbours are live ticks — the left one may be a
 * pin or a carried copy standing for a live tick, and a window's start is the left edge when the live tick before it
 * is still fresh at the start — is healed by the 1-minute candles that overlap it, all or nothing: every minute of
 * the gap has a closed candle with trades; the live price on each side lies inside the range of its own minute's
 * candle, widened by the feed's own tolerance ({@link PriceSource.Feed#maxBasis}: {@link PricesConfig#hyperliquidMaxBasis()}
 * for Hyperliquid's perpetuals, one setting each for Coinbase and for Hyperliquid spot, {@link SpotPricesConfig}); and
 * the gap is not longer than {@link PricesConfig#hyperliquidMaxHealMs()}. What a candle contributes is what the
 * liquidation check needs, its
 * low and its high (a third and two thirds into the minute) and its close at the end of the minute — points at
 * most 20.001 s apart, so a fully covered gap satisfies the maximum gap of 30 s and a candle missing from the answer
 * breaks it. The bridge covers the whole gap up to the live tick on the right, also the part behind the window's end:
 * the round that starts there then finds no hole. The boundary prices keep the rule of the candle before the
 * boundary ({@link HyperliquidPriceSource}), and a pinned boundary is never touched ({@link TickStore#pinBoundary}).
 *
 * <p><b>How long a gap is bridged, and what a redeploy does to the running rounds.</b> A gap up to 30 minutes
 * ({@code herd.prices.hyperliquid.max-heal-ms}): half of the HOURLY round of production, so that one gap puts at most half of
 * a round on trade prices, and about the length of the stretch over which the marks of the stand were compared
 * with the candles (docs/research/hl_multi_asset_live.md §13.4); the two checks look at the ends of a gap only, and
 * the longer it is, the less they say about the middle. A redeploy of the server — a minute or a few without
 * polls — therefore no longer voids the rounds that were running: when such a round ends, the gap in its ticks is
 * bridged by the candles (one {@code candleSnapshot}), it settles, and a gap across a round boundary does not
 * hurt the round after it. The round stays a hole — it is retried, and an hour after its end cancelled with a
 * refund (Р-23), as before — when the gap is longer than the cap, when a minute of it had no trades (the asset is
 * not liquid: BTC, ETH, SOL and HYPE had none missing in 24 hours, TSLA only 60% of the 20-minute stretches were
 * fully traded, §13.5), when a live price is outside the range of its minute by more than the basis, or when the
 * candle of the live tick on the right is not closed yet (it is asked again after the cooldown).
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
 * <p>Locking (docs/research/markets_architecture.md §3.2, the engine lock): {@link #resolve} and
 * {@link #openRound} may block on HTTP or the database and hold no lock while they do; {@link #latest},
 * {@link #series}, {@link #soFar} and {@link #decidedVenue} only copy slices out of the rings and read the
 * decisions held in memory, each for the length of one array copy. Nothing here ever calls the market
 * registry, so none of it can wait for the engine lock that the registry's listener takes.
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
    private final RoundVenues venues;
    private final LongSupplier clock;
    private final ThrottledLog warnings;
    private final ConcurrentHashMap<String, Long> lastBackfillMs = new ConcurrentHashMap<>();

    @Inject
    public LiveMarketPrices(TickStore store, PriceSources sources, PricesConfig config, RoundVenues venues) {
        this(store, sources, config, venues, System::currentTimeMillis);
    }

    /** With a holder of round venues of its own, kept in memory: for a market with one venue, the one that ever matters. */
    LiveMarketPrices(TickStore store, PriceSources sources, PricesConfig config, LongSupplier clock) {
        this(store, sources, config, RoundVenues.inMemory(config.retentionMs(), clock), clock);
    }

    LiveMarketPrices(TickStore store, PriceSources sources, PricesConfig config, RoundVenues venues,
                     LongSupplier clock) {
        this.store = store;
        this.sources = sources;
        this.config = config;
        this.venues = venues;
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

    /**
     * What the lobby, the bots and the bet slip show. A market with one venue: its latest live tick. A market with a
     * backup: the primary's latest live tick while it is fresh, else the backup's while that is fresh — the quote then
     * says which venue it is from ({@link PriceQuote#venue}) — and with neither fresh, the newer of the two (ties: the
     * primary's), still saying where it is from, so that the stale flag is true of the very price that is shown.
     */
    @Override
    public Optional<PriceQuote> latest(Market market) {
        Optional<PriceQuote> primary = latest(market.id());
        Optional<Market> shadow = Instruments.shadowOf(market);
        if (shadow.isEmpty() || (primary.isPresent() && !primary.get().stale())) {
            return primary;
        }
        Optional<PriceQuote> backup = latest(shadow.get().id()).map(q -> q.withVenue(shadow.get().source()));
        if (backup.isPresent() && !backup.get().stale()) {
            return backup;
        }
        if (primary.isEmpty()) {
            return backup;
        }
        if (backup.isEmpty()) {
            return primary;
        }
        return backup.get().asOfMs() > primary.get().asOfMs() ? backup : primary;
    }

    /**
     * The series of exactly this key's ring. The chart of a market is its primary venue's series: the backup's points are
     * not mixed in — the series carries no venue per point (that would be a contract change), and two venues' mids stitched
     * into one line without a mark would be a line that jumps by their basis, unexplained.
     */
    @Override
    public List<PricePoint> series(String marketId, long fromMs, long toMs) {
        return store.series(marketId, fromMs, toMs);
    }

    @Override
    public Optional<String> decidedVenue(Market market, RoundWindow window) {
        requireSameMarket(market, window);
        return Instruments.hasBackup(market) ? venues.decided(window.roundId()) : Optional.empty();
    }

    @Override
    public Optional<String> openRound(Market market, RoundWindow window, long nowMs) {
        requireSameMarket(market, window);
        Optional<Market> shadow = Instruments.shadowOf(market);
        if (shadow.isEmpty()) {
            return Optional.empty();
        }
        return decideInstrument(market, shadow.get(), window, false, nowMs).map(Market::source);
    }

    @Override
    public RoundPriceData resolve(Market market, RoundWindow window) throws PriceUnavailableException {
        requireSameMarket(market, window);
        Optional<Market> shadow = Instruments.shadowOf(market);
        if (shadow.isEmpty()) {
            return resolveOn(market, window);
        }
        // a round of a market with a backup is settled from the exchange it was decided to run on, and from that one alone
        Market instrument = decideInstrument(market, shadow.get(), window, true, clock.getAsLong())
                .orElseThrow(() -> new PriceUnavailableException("no exchange can price the start of " + market.id() + " "
                        + window.roundId() + ": " + noVenue(market, shadow.get(), window.startMs())));
        return resolveOn(instrument, window).withVenue(instrument.source());
    }

    /** The settlement of {@code window} from the ticks of {@code market} — a market, or the shadow of one — alone. */
    private RoundPriceData resolveOn(Market market, RoundWindow window) throws PriceUnavailableException {
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

    // ------------------------------------------------------------------------------------------
    // The exchange of a round (Р-76)
    // ------------------------------------------------------------------------------------------

    /**
     * The instrument the round runs on, deciding it first when nothing has: what is already decided stands (the table's
     * row, remembered or read), a round that another of the market's rounds starting together was decided with takes
     * that, and otherwise {@link #chooseByRings}; with {@code heal}, and nothing chosen yet, {@link #healStart} asks the
     * venues' histories. A choice is written once ({@link RoundVenues#decide}); empty while no venue can price the
     * round's start.
     */
    private Optional<Market> decideInstrument(Market market, Market shadow, RoundWindow window, boolean heal,
                                              long nowMs) {
        Optional<String> known = heal ? venues.venueOfRound(window.roundId()) : venues.decided(window.roundId());
        if (known.isPresent()) {
            return Optional.of(instrumentOf(market, shadow, known.get()));
        }
        Optional<String> together = venues.decidedForStart(market.id(), window.startMs());
        Optional<Market> chosen = together.isPresent() ? Optional.of(instrumentOf(market, shadow, together.get()))
                : chooseByRings(market, shadow, window.startMs());
        if (chosen.isEmpty() && heal) {
            chosen = healStart(market, shadow, window);
        }
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        String venue = venues.decide(window.roundId(), market.id(), window.startMs(), chosen.get().source(), nowMs);
        return Optional.of(instrumentOf(market, shadow, venue));
    }

    /** The market or its shadow, by the venue's name; a venue that is neither of them cannot be priced from here. */
    private static Market instrumentOf(Market market, Market shadow, String venue) {
        if (venue.equals(market.source())) {
            return market;
        }
        if (venue.equals(shadow.source())) {
            return shadow;
        }
        throw new PriceUnavailableException("round of " + market.id() + " was decided on " + venue
                + ", which is neither its primary exchange " + market.source() + " nor its backup " + shadow.source());
    }

    /**
     * The venue to open a round at {@code startMs} on, from the rings as they are — two passes, each over the primary and
     * then the backup:
     * <ol>
     *   <li><b>A live tick fresh at the start</b> ({@link #liveFreshAt}; the rule of {@code P(start)}: the last tick at or
     *       before it, not older than the feed's {@code staleMs}) — the primary's, else the backup's. A venue that is
     *       alive at the boundary beats any amount of the other kind of evidence on the other side.</li>
     *   <li><b>No live tick on either side: whatever else can price the start</b> ({@link #anyFreshAt}) — seeded history (a
     *       market listed in the middle of a window has nothing else), a carried tick, a pin: all are non-live evidence of
     *       the same rank, the primary's ring first. So a pin the backup holds at the start (the close of the round before
     *       it, if that ran there) does not beat a primary whose ring has history for it; it prices the start only when the
     *       primary's ring cannot.</li>
     * </ol>
     * Empty when neither ring can price the start: the round waits, and {@link #healStart} asks the venues' histories later,
     * the primary first.
     */
    private Optional<Market> chooseByRings(Market primary, Market backup, long startMs) {
        for (Market venue : List.of(primary, backup)) {
            if (liveFreshAt(venue, startMs)) {
                return Optional.of(venue);
            }
        }
        for (Market venue : List.of(primary, backup)) {
            if (anyFreshAt(venue, startMs)) {
                return Optional.of(venue);
            }
        }
        return Optional.empty();
    }

    /** A venue with no source — switched off, or never configured — prices nothing, whatever its ring still holds. */
    private Optional<PriceSource.Feed> feedOf(Market instrument) {
        return sources.bindingFor(instrument).map(PriceSources.Binding::feed);
    }

    /**
     * Whether the instrument has a <i>live</i> tick fresh at {@code startMs}: the nearest real tick at or before it
     * ({@link TickRing.Window#floorSourceKind}) is a live one — not a history point — and no older than the feed's
     * {@code staleMs}. A pin or a carried tick at the start is a copy, not a tick of its own: it counts as the real tick it
     * stands for. The pin of the round before, standing for the live tick that round was settled from, must not demote its
     * venue at the very boundary; a pin standing for a history point, or for a tick that is too old, is non-live.
     */
    private boolean liveFreshAt(Market instrument, long startMs) {
        Optional<PriceSource.Feed> feed = feedOf(instrument);
        if (feed.isEmpty()) {
            return false;
        }
        TickRing.Window w = store.window(instrument.id(), startMs, startMs);
        return w.hasFloor() && w.floorSourceKind() == TickRing.LIVE
                && startMs - w.floorSourceAtMs() <= feed.get().staleMs();
    }

    /**
     * Whether the instrument's ring can price the start at all: a tick of any kind (live, history, carried, pin) at or before
     * it, no older than the feed's {@code staleMs} — exactly the {@code P(start)} that settling the round would use.
     */
    private boolean anyFreshAt(Market instrument, long startMs) {
        Optional<PriceSource.Feed> feed = feedOf(instrument);
        if (feed.isEmpty()) {
            return false;
        }
        TickRing.Window w = store.window(instrument.id(), startMs, startMs);
        return w.hasFloor() && startMs - w.floorAtMs() <= feed.get().staleMs();
    }

    /**
     * No venue can price the start from what the rings hold: asks the venues' histories, the primary first, for the start of the
     * round — the same request that settles a round with a hole ({@link #backfill}: a candle source's history is the past of
     * its first tick and a bridge over a gap between two live ticks) — and takes the first venue that has a price at the start
     * afterwards. A venue is asked once per backfill cooldown, so a settlement that is retried every few seconds asks
     * the exchanges once a minute.
     */
    private Optional<Market> healStart(Market primary, Market backup, RoundWindow window) {
        for (Market instrument : List.of(primary, backup)) {
            PriceSources.Binding binding = sources.bindingFor(instrument).orElse(null);
            if (binding == null) {
                continue;
            }
            Assessment assessment = assess(instrument, window, binding.feed());
            if (!assessment.holes().isEmpty()) {
                backfill(instrument, window, binding.source(), assessment);
            }
            if (anyFreshAt(instrument, window.startMs())) {
                return Optional.of(instrument);
            }
        }
        return Optional.empty();
    }

    private String noVenue(Market primary, Market backup, long startMs) {
        return primary.source() + " and " + backup.source() + " have no tick fresh at "
                + Instant.ofEpochMilli(startMs) + " and their histories could not give one";
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
        Optional<Market> shadow = Instruments.shadowOf(market);
        if (shadow.isEmpty()) {
            return soFarOn(market, window, nowMs);
        }
        Optional<String> venue = venues.decided(window.roundId()); // memory only; no venue yet, no running price
        if (venue.isEmpty()) {
            return Optional.empty();
        }
        Market instrument;
        try {
            instrument = instrumentOf(market, shadow.get(), venue.get());
        } catch (PriceUnavailableException e) { // a decision this configuration cannot read: nothing to show, nothing to fail
            warnings.failure("venue " + window.roundId(), e.getMessage(), null);
            return Optional.empty();
        }
        return soFarOn(instrument, window, nowMs).map(data -> data.withVenue(instrument.source()));
    }

    /** The running prices of {@code window} from the ticks of {@code market} — a market, or the shadow of one — alone. */
    private Optional<RoundPriceData> soFarOn(Market market, RoundWindow window, long nowMs) {
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
     *
     * <p>What a bridge over the hole needs to know about its two live ticks: {@code leftRealAtMs}, the time of the
     * live tick the left neighbour stands for ({@code fromMs} itself unless the neighbour is a copy, or a window's
     * start clamps the edge), and {@code rightAtMs} with {@code rightLive}, the time of the right neighbour — which
     * for the tail of a window is the first tick after its end, past {@code toMs} — and whether it is a live tick
     * ({@link Long#MIN_VALUE}: there is none).
     */
    private record Hole(long fromMs, long toMs, Long leftPx, Long rightPx, boolean leftLive, long leftRealAtMs,
                        long rightAtMs, boolean rightLive) {

        /** Ticks on both sides, at the same price: a quiet asset's price did not move across the hole. */
        boolean flatBothSides() {
            return leftPx != null && leftPx.equals(rightPx);
        }

        boolean contains(long atMs) {
            return atMs > fromMs && atMs < toMs;
        }

        /** A live tick on both sides: something the candles can be checked against. */
        boolean betweenLiveTicks() {
            return leftPx != null && leftLive && leftRealAtMs != Long.MIN_VALUE && rightPx != null && rightLive
                    && rightAtMs != Long.MIN_VALUE;
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
        long floorRealAt = w.hasFloor() ? w.floorSourceAtMs() : Long.MIN_VALUE; // the tick the floor's price was seen at
        if (!startOk) {
            long a = w.hasFloor() ? floorAt : start - feed.staleMs() - HISTORY_PAD_MS;
            long b = at.length > 0 ? at[0] : end + 1;
            Long left = w.hasFloor() ? Long.valueOf(w.floorPx()) : null;
            if (at.length > 0) {
                holes.add(new Hole(a, b, left, px[0], floorLive, floorRealAt, at[0], kind[0] == TickRing.LIVE));
            } else {
                long[] after = tickAfter(market, end);
                holes.add(new Hole(a, b, left, after == null ? null : after[1], floorLive, floorRealAt,
                        after == null ? Long.MIN_VALUE : after[0], after != null && after[2] == 1L));
            }
        }
        long previousAt = startOk ? floorAt : Long.MIN_VALUE;
        long previousPx = startOk ? w.floorPx() : 0L;
        boolean previousLive = floorLive; // the real tick a copy at the head of the window stands for is the floor's
        long previousRealAt = floorRealAt;
        for (int i = 0; i < at.length; i++) {
            if (previousAt != Long.MIN_VALUE && at[i] - previousAt > feed.maxGapMs()) {
                holes.add(new Hole(previousAt, at[i], previousPx, px[i], previousLive, previousRealAt, at[i],
                        kind[i] == TickRing.LIVE));
            }
            previousAt = at[i];
            previousPx = px[i];
            if (TickRing.isReal(kind[i])) { // a copy keeps standing for the real tick before it
                previousLive = kind[i] == TickRing.LIVE;
                previousRealAt = at[i];
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
            long[] after = tickAfter(market, end);
            holes.add(new Hole(a, end + 1, left, after == null ? null : after[1], leftLive,
                    at.length > 0 ? previousRealAt : floorRealAt, after == null ? Long.MIN_VALUE : after[0],
                    after != null && after[2] == 1L));
        }
        return new Assessment(w, start, end, feed, merge(holes));
    }

    /**
     * The first tick after the window, if the source has come back since, as {@code {atMs, px, 1 if it is a live
     * tick else 0}}; else null.
     */
    private long[] tickAfter(Market market, long end) {
        long[] tick = store.tickAfter(market.id(), end);
        return tick == null ? null : new long[] {tick[0], tick[1], store.liveTickAfter(market.id(), end) ? 1L : 0L};
    }

    private static List<Hole> merge(List<Hole> holes) {
        holes.sort(Comparator.comparingLong(Hole::fromMs));
        List<Hole> merged = new ArrayList<>();
        for (Hole h : holes) {
            Hole last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last == null || h.fromMs() >= last.toMs()) {
                merged.add(h);
            } else if (h.toMs() > last.toMs()) { // overlapping: one hole, from the first's start to the later end
                merged.set(merged.size() - 1, new Hole(last.fromMs(), h.toMs(), last.leftPx(), h.rightPx(),
                        last.leftLive(), last.leftRealAtMs(), h.rightAtMs(), h.rightLive()));
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
        boolean tradePrices = source.historyKind() == PriceSource.HistoryKind.TRADE_PRICES;
        boolean bridging = tradePrices && config.hyperliquidMaxHealMs() > 0;
        if (bridging) {
            for (Hole hole : holes) {
                if (bridgeable(hole)) {
                    to = Math.max(to, hole.rightAtMs() + 1); // the candle that holds the live tick on the right too
                }
            }
        }
        if (from >= to) {
            return;
        }
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
                        ? " (trade prices are only the past of the first poll, never put between live ticks, "
                        + "unless the candles bridge the whole gap)" : "");
        boolean[] bridged = bridging ? bridgeHoles(market, window, assessment, answer, pinned)
                : new boolean[holes.size()];
        carryQuietHoles(market, window, holes, perHole, bridged, answer, tradePrices);
    }

    /** A live tick on both sides and a gap within the cap: worth fetching the candles up to the tick on the right. */
    private boolean bridgeable(Hole hole) {
        return hole.betweenLiveTicks() && hole.rightAtMs() - hole.leftRealAtMs() <= config.hyperliquidMaxHealMs();
    }

    /**
     * Bridges every hole that lies between two live ticks with the candles of the answer, hole by hole and each
     * all or nothing ({@link GapBridge}); returns which holes were bridged. The points go to the ring like any
     * history ({@link TickStore#mergeHistory}): never over a tick, never at or before the latest pinned boundary.
     */
    private boolean[] bridgeHoles(Market market, RoundWindow window, Assessment assessment, PriceSource.History answer,
                                  long pinned) {
        List<Hole> holes = assessment.holes();
        boolean[] bridged = new boolean[holes.size()];
        // the boundary prices of this window the source priced itself (the close of the candle before the boundary)
        List<PricePoint> atBoundaries = answer.points().stream()
                .filter(p -> p.atMs() == window.startMs() || p.atMs() == window.endMs()).toList();
        Double feedBasis = assessment.feed().maxBasis(); // each venue has its own measured tolerance
        BigDecimal maxBasis = BigDecimal.valueOf(feedBasis != null ? feedBasis : config.hyperliquidMaxBasis());
        for (int i = 0; i < holes.size(); i++) {
            Hole hole = holes.get(i);
            long fillFrom = Math.max(hole.fromMs(), pinned);
            if (!hole.betweenLiveTicks() || fillFrom >= hole.rightAtMs()) {
                continue; // nothing to check the candles against, or nothing left that may still be merged
            }
            GapBridge.Gap gap = new GapBridge.Gap(hole.leftRealAtMs(), Px.decode(hole.leftPx()), fillFrom,
                    hole.rightAtMs(), Px.decode(hole.rightPx()));
            GapBridge.Result result = GapBridge.bridge(answer.bars(), gap, atBoundaries, assessment.feed().maxGapMs(),
                    config.hyperliquidMaxHealMs(), maxBasis, p -> usable(market, p), answer.coveredFromMs());
            if (result.ok()) {
                int added = store.mergeHistory(market.id(), result.points(), false);
                bridged[i] = true;
                LOG.infof("price history for %s %s: the gap between the live ticks of %s and %s (%d s) is bridged "
                        + "by the candles, %d point(s) of %d added", market.id(), window.roundId(),
                        Instant.ofEpochMilli(hole.leftRealAtMs()), Instant.ofEpochMilli(hole.rightAtMs()),
                        (hole.rightAtMs() - hole.leftRealAtMs()) / 1000, added, result.points().size());
            } else {
                LOG.infof("price history for %s %s: the gap between the live ticks of %s and %s stays a hole: %s",
                        market.id(), window.roundId(), Instant.ofEpochMilli(hole.leftRealAtMs()),
                        Instant.ofEpochMilli(hole.rightAtMs()), result.refusal());
            }
        }
        return bridged;
    }

    /**
     * A quiet asset: for a hole in which the source had no point, that is not longer than
     * {@link PricesConfig#quietCarryMaxMs()} and whose neighbours say the price did not move, the price of the
     * last tick continues in {@link #QUIET_CARRY_STEP_MS} steps up to the next tick (or, at the tail, up to
     * {@code end}). "Did not move" is: a live tick on the right with the same price — or, with none yet, the
     * source's last trade (a candle's close) being the very price of the left tick. With
     * {@code tradePrices} the tick on the left must be a live one: the carried price is a mark, never a
     * trade price. Only called after the source answered — and only for the part of a hole the answer reaches
     * ({@link PriceSource.History#reaches}): a source that keeps no history that old (Kraken keeps twelve hours)
     * has nothing to say about it, and its silence is no sign that the asset was quiet.
     */
    private void carryQuietHoles(Market market, RoundWindow window, List<Hole> holes, int[] perHole,
                                 boolean[] bridged, PriceSource.History answer, boolean tradePrices) {
        Optional<PricePoint> lastTrade = answer.lastTrade();
        List<PricePoint> carried = new ArrayList<>();
        for (int i = 0; i < holes.size(); i++) {
            Hole hole = holes.get(i);
            if (perHole[i] > 0 || bridged[i] || hole.leftPx() == null || (tradePrices && !hole.leftLive())) {
                continue; // a bridged hole has its points; one the asset traded in is not a quiet one
            }
            if (!answer.reaches(hole.fromMs())) {
                continue; // no data that old: the absence of trades in the answer says nothing about this hole
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
