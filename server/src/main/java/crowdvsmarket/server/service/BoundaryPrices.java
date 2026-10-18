package crowdvsmarket.server.service;

import crowdvsmarket.engine.market.Candle;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.prices.Instruments;
import crowdvsmarket.server.prices.RoundPriceData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.jboss.logging.Logger;

/**
 * Р-40 in the core: the close of a round is the open of the next one, and both series of a market share
 * their boundaries — whatever the price pipeline still remembers. The pipeline's boundary price is a fact of
 * its memory ({@code TickStore}: ticks, and history merged into holes), and after a restart a later merge can
 * give a boundary a price other than the one a round already closed on, settled, and anchored on chain. The
 * settled rows of {@code round_results} are the durable fact, so they win:
 *
 * <ul>
 *   <li><b>A round that is settled</b> — window {@code [S, E)} of market M — opens at the close of the SETTLED
 *       row of M, of either series, whose window ends at {@code S}; the pipeline's open is ignored, and the
 *       first candle of the path opens there (low and high widened to include it) ({@link #forSettlement}).
 *   <li><b>A round that is running</b> shows the same open in every DTO — {@code rounds}, {@code round},
 *       {@code myPositions} ({@link #forRunning}) — even when the pipeline does not know the open at all (a
 *       restart whose tick buffer is still empty): the previous close is the open, that is what Р-40 says.
 *   <li>A previous round that was CANCELLED has no close: nothing overrides, the pipeline's open is used.
 *   <li>Where two settled rows of the market, one of each series, end at the same boundary and disagree on the
 *       close — the pipeline gives one price per boundary, so only data from before this rule can — the first
 *       one that was settled counts: the longer series' row, the one the scheduler settles first.
 * </ul>
 *
 * <p><b>Venues (Р-76).</b> A market with a backup exchange has a price per exchange, so "the close of the round that
 * ended where this one starts" is a close <i>on the exchange this round runs on</i>: a round that opens on Kraken
 * after one that closed on Coinbase opens at Kraken's price at that boundary — the pipeline's — and not at Coinbase's
 * close, which would put another exchange's number into a round that is settled from Kraken's ticks alone. Р-40 holds
 * within an exchange; across a switch the open differs from the close before it by the basis between the two
 * (measured 2026-10-03: 0.4–1.2 bp on average, at most 5 bp). Two rounds of the two series that end together may have
 * run on different exchanges (each chose its own when it opened), so the closes are kept per exchange; a market with no
 * backup has none and its closes are as they ever were. A running round whose exchange is not decided yet shows no open
 * from here: which exchange's close it would be is not known.
 *
 * <p><b>Memory only on the hot paths.</b> What a round can open at is the close of a settled round that ended
 * at or after the earliest cursor of its market, which is a handful of rows: they are read from the database
 * when a state is built ({@link #load}, aside, together with the rest of a rebuild, swapped in by {@link
 * #adopt}) and added when a settlement has committed ({@link #settled}), pruned behind the cursors. The lookup
 * itself reads no database and takes no lock, so it is safe under the engine's lock.
 *
 * <p>Only in {@code mode=live}: the replay series is a recorded one — its next window opens at the next candle's
 * open, which is not the previous candle's close — and has no pipeline memory to disagree with.
 */
@ApplicationScoped
public class BoundaryPrices {

    private static final Logger LOG = Logger.getLogger(BoundaryPrices.class);

    /** Where a settled window ended and on which exchange ({@code ""}: a market with no backup). */
    private record CloseKey(long endMs, String venue) implements Comparable<CloseKey> {

        @Override
        public int compareTo(CloseKey other) {
            int byEnd = Long.compare(endMs, other.endMs);
            return byEnd != 0 ? byEnd : venue.compareTo(other.venue);
        }
    }

    private static String venueKey(String venue) {
        return venue == null ? "" : venue;
    }

    /** A state's boundary closes, built aside by {@link #load}. Opaque: what {@link #adopt} swaps in. */
    public static final class Snapshot {
        private final ConcurrentHashMap<String, ConcurrentSkipListMap<CloseKey, BigDecimal>> closes;

        private Snapshot(ConcurrentHashMap<String, ConcurrentSkipListMap<CloseKey, BigDecimal>> closes) {
            this.closes = closes;
        }

        /** Where the settled rounds of {@code marketId} (a market with no backup) closed that it still knows: end of window → close. */
        /** Adds the close of a round whose row could not be written ({@code EngineStateBuilder}): the next round of the
         *  series opens at it all the same. */
        public void settled(RoundWindow window, long closeCents, String venue) {
            closes.computeIfAbsent(window.market(), market -> new ConcurrentSkipListMap<>())
                    .putIfAbsent(new CloseKey(window.endMs(), venueKey(venue)), BigDecimal.valueOf(closeCents, 2));
        }

        public Map<Long, BigDecimal> of(String marketId) {
            return of(marketId, null);
        }

        /** The same for the rounds of {@code marketId} that ran on {@code venue}. */
        public Map<Long, BigDecimal> of(String marketId, String venue) {
            ConcurrentSkipListMap<CloseKey, BigDecimal> known = closes.get(marketId);
            Map<Long, BigDecimal> out = new TreeMap<>();
            if (known != null) {
                known.forEach((key, close) -> {
                    if (key.venue().equals(venueKey(venue))) {
                        out.put(key.endMs(), close);
                    }
                });
            }
            return Map.copyOf(out);
        }
    }

    @Inject
    GameConfig config;
    @Inject
    RoundResultRepository roundResults;
    @Inject
    RoundProgress roundProgress;

    /** market → (end of a settled window and its exchange → its close, engine price at scale 2); replaced wholesale by
     *  {@link #adopt}, the inner maps are written only under the engine's write lock. */
    private volatile ConcurrentHashMap<String, ConcurrentSkipListMap<CloseKey, BigDecimal>> closes =
            new ConcurrentHashMap<>();

    // ---------------------------------------------------------------------------------------
    // The memory: built aside, swapped in, topped up
    // ---------------------------------------------------------------------------------------

    /**
     * The closes a state with these {@code cursors} can need: for each market that has series, the SETTLED rows
     * of both its series that end at or after the earliest cursor of the market. Read from the database, for a
     * state that is being built aside — nothing live is touched. Where two settled rows end at the same boundary
     * on the same exchange with different closes (data from before this rule existed) the longer series' row counts:
     * that is the one the scheduler has always settled first.
     */
    public Snapshot load(Map<RoundProgress.SeriesKey, RoundWindow> cursors) {
        ConcurrentHashMap<String, ConcurrentSkipListMap<CloseKey, BigDecimal>> fresh = new ConcurrentHashMap<>();
        if (config.mode() != GameMode.LIVE) {
            return new Snapshot(fresh);
        }
        Map<String, Long> earliestCursor = new HashMap<>();
        cursors.forEach((series, window) -> earliestCursor.merge(series.marketId(), window.startMs(), Math::min));
        List<Interval> longestFirst = new ArrayList<>(Arrays.asList(Interval.values()));
        longestFirst.sort(Comparator.comparingLong((Interval i) -> config.roundDurations().forInterval(i)).reversed());
        earliestCursor.forEach((marketId, fromMs) -> {
            ConcurrentSkipListMap<CloseKey, BigDecimal> known = new ConcurrentSkipListMap<>();
            for (Interval interval : longestFirst) {
                long stepMs = config.roundDurations().forInterval(interval);
                for (RoundResultRepository.SettledClose row : roundResults.settledCloses(marketId, interval,
                        fromMs - stepMs)) {
                    if (row.windowEndMs() >= fromMs) {
                        known.putIfAbsent(new CloseKey(row.windowEndMs(), venueKey(row.venue())),
                                BigDecimal.valueOf(row.closeCents(), 2));
                    }
                }
            }
            fresh.put(marketId, known);
        });
        return new Snapshot(fresh);
    }

    /** Swaps in the closes of a state built aside. Cannot fail; the caller holds the engine lock. */
    public void adopt(Snapshot snapshot) {
        closes = snapshot.closes;
    }

    /**
     * A round was settled and the transaction has committed: its close is now a fact other rounds open at.
     * Called by the settlement under the engine's write lock, only after the commit — memory never runs ahead of
     * the database. Also drops what no cursor of the market can still need.
     *
     * @param closeCents the engine (lot) close price times 100, what {@code round_results} stores
     */
    public void settled(RoundWindow window, long closeCents) {
        settled(window, closeCents, null);
    }

    /** The same for a round that ran on {@code venue} ({@code null}: a market with no backup). */
    public void settled(RoundWindow window, long closeCents, String venue) {
        if (config.mode() != GameMode.LIVE) {
            return;
        }
        ConcurrentSkipListMap<CloseKey, BigDecimal> known =
                closes.computeIfAbsent(window.market(), market -> new ConcurrentSkipListMap<>());
        known.putIfAbsent(new CloseKey(window.endMs(), venueKey(venue)), BigDecimal.valueOf(closeCents, 2));
        long earliestCursorMs = Long.MAX_VALUE;
        for (Interval interval : Interval.values()) {
            Optional<RoundWindow> cursor = roundProgress.findNext(window.market(), interval);
            if (cursor.isPresent()) {
                earliestCursorMs = Math.min(earliestCursorMs, cursor.get().startMs());
            }
        }
        if (earliestCursorMs != Long.MAX_VALUE) {
            // every later round starts at or after a cursor; "" sorts before every venue, so this drops every key
            // that ends strictly before the cursor, whatever its venue
            known.headMap(new CloseKey(earliestCursorMs, ""), false).clear();
        }
    }

    /** Where a settled round of {@code marketId} (a market with no backup) closed that ended at {@code boundaryMs},
     *  in engine prices; empty when there is no such round, it was cancelled, or the mode is replay. Memory only. */
    public Optional<BigDecimal> closeAt(String marketId, long boundaryMs) {
        return closeAt(marketId, boundaryMs, null);
    }

    /** The same for a round that ran on {@code venue}: the close on that exchange. */
    public Optional<BigDecimal> closeAt(String marketId, long boundaryMs, String venue) {
        ConcurrentSkipListMap<CloseKey, BigDecimal> known = closes.get(marketId);
        return known == null ? Optional.empty()
                : Optional.ofNullable(known.get(new CloseKey(boundaryMs, venueKey(venue))));
    }

    // ---------------------------------------------------------------------------------------
    // The rule, applied
    // ---------------------------------------------------------------------------------------

    /**
     * {@code data}, the pipeline's settlement data of {@code window}, with its open made to agree with the
     * rounds that already settled: the open is the close of the round that ended at the window's start <i>on the same
     * exchange</i> ({@link RoundPriceData#venue}), and the first candle of the path opens there. {@code data} itself
     * when no such round exists — a round that moved to the other exchange opens where that exchange's ticks put it.
     */
    public RoundPriceData forSettlement(RoundWindow window, RoundPriceData data) {
        Optional<BigDecimal> open = closeAt(window.market(), window.startMs(), data.venue());
        if (open.isEmpty()) {
            return data;
        }
        if (differs(data.open(), open.get())) {
            LOG.warnf("round %s opens at %s, where the previous round closed — not at %s, which the price source "
                    + "reports for that boundary now", window.roundId(), open.get(), data.open());
        }
        return new RoundPriceData(open.get(), data.close(), openingAt(data.path(), open.get()), data.sourceTsMs(),
                data.venue());
    }

    /**
     * The prices so far of a running {@code window} of a market with no backup as every DTO shows them: the pipeline's,
     * with the open of the round that ended where this one started when there is one. Where the pipeline has nothing yet,
     * the open alone is known — a flat view at it, with no path, for the DTO to draw from. Memory only.
     */
    public Optional<RoundPriceData> forRunning(Market market, RoundWindow window, Optional<RoundPriceData> pipeline) {
        return forRunning(market, window, pipeline, Optional.empty());
    }

    /**
     * The same for a market that may have a backup: {@code decidedVenue} is the exchange the round was decided to run on
     * ({@code MarketPrices#decidedVenue}). The open is the close of the previous round on <i>that</i> exchange; while the
     * round has none (nothing could price its start yet) there is no open to show — the pipeline's answer stands, which
     * is nothing.
     */
    public Optional<RoundPriceData> forRunning(Market market, RoundWindow window, Optional<RoundPriceData> pipeline,
                                               Optional<String> decidedVenue) {
        String venue = pipeline.map(RoundPriceData::venue).orElse(decidedVenue.orElse(null));
        if (venue == null && Instruments.hasBackup(market)) {
            return pipeline;
        }
        Optional<BigDecimal> open = closeAt(market.id(), window.startMs(), venue);
        if (open.isEmpty()) {
            return pipeline;
        }
        if (pipeline.isEmpty()) {
            return Optional.of(new RoundPriceData(open.get(), open.get(), List.of(), window.startMs(), venue));
        }
        RoundPriceData data = pipeline.get();
        return Optional.of(new RoundPriceData(open.get(), data.close(), openingAt(data.path(), open.get()),
                data.sourceTsMs(), data.venue()));
    }

    private static boolean differs(BigDecimal pipelinePrice, BigDecimal settled) {
        return pipelinePrice.setScale(2, RoundingMode.HALF_UP).compareTo(settled) != 0;
    }

    /** {@code path} with its first candle opening at {@code open}; low and high are widened to take it in. */
    static List<Candle> openingAt(List<Candle> path, BigDecimal open) {
        if (path.isEmpty()) {
            return path;
        }
        Candle first = path.get(0);
        if (first.open().compareTo(open) == 0) {
            return path;
        }
        List<Candle> adjusted = new ArrayList<>(path);
        adjusted.set(0, new Candle(first.openTimeMs(), first.closeTimeMs(), open, first.high().max(open),
                first.low().min(open), first.close(), first.volume(), first.trades()));
        return adjusted;
    }
}
