package crowdvsmarket.server.scheduler;

import crowdvsmarket.engine.game.RoundReport;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.jfr.HerdRebuild;
import crowdvsmarket.server.jfr.JfrClock;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.persistence.LedgerEntryRepository;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.persistence.ServerMetaRepository;
import crowdvsmarket.server.service.BoundaryPrices;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.service.RoundCancellations;
import crowdvsmarket.server.service.SeriesSeeder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Builds a complete engine state ASIDE: a fresh {@link EngineHub} with the engines of every market in
 * the registry (closed ones too: their history replays), a fresh {@link BetFeed}, the replay of
 * {@code game_events} into both, the cancellation of what a closed market left open, the cursor
 * of every running series and the boundary prices those cursors can open at ({@link BoundaryPrices}) —
 * and touches nothing that serves requests. Whatever it throws, the live
 * state is exactly what it was; whatever it returns is complete, and {@code EngineRebuildService}
 * swaps it in without anything left to fail. That is what makes a rebuild safe to attempt at the very
 * moment something has just gone wrong (a half-replayed hub that requests could reach, or an empty
 * ledger that hands out a new starter pack, are what the in-place rebuild it replaced could leave).
 *
 * <p>The things it does that outlive a failed build: the result rows of the last resolved round when a stop left the
 * journal ahead of them ({@code GameService#repairSettledResult}), and the refund of a closed market's open
 * rounds ({@link RoundCancellations}): that is a committed event and result row in the database
 * (memory is then BEHIND the database, never ahead — the next build replays it). The caller holds
 * the engine write lock.
 */
@ApplicationScoped
public class EngineStateBuilder {

    private static final Logger LOG = Logger.getLogger(EngineStateBuilder.class);

    @Inject
    MarketRegistry marketRegistry;
    @Inject
    PlayerDirectory players;
    @Inject
    EventStore eventStore;
    @Inject
    SeriesSeeder seeder;
    @Inject
    RoundCancellations cancellations;
    @Inject
    BoundaryPrices boundaryPrices;
    @Inject
    GameService gameService;
    @Inject
    RoundResultRepository roundResults;
    @Inject
    LedgerEntryRepository ledgerRows;
    @Inject
    ServerMetaRepository serverMeta;

    /** How many ledger movements a replay collects before it writes them to the table (a first start with an empty table). */
    static final int LEDGER_SPILL_BATCH = 5_000;

    /** A finished build: ready to be swapped in. {@code cursors} holds one window per running series;
     *  {@code boundaries} the closes of the settled rounds those cursors may open at; {@code description}
     *  is the log line's tail. */
    public record Built(EngineHub hub, BetFeed betFeed, Map<RoundProgress.SeriesKey, RoundWindow> cursors,
                        BoundaryPrices.Snapshot boundaries, int eventsReplayed, int marketCount,
                        String description) {
    }

    /**
     * @param nowMs "now" for seeding the series and for the time of a cancellation
     * @throws RuntimeException anything the database or the replay throws: the build is then simply
     *         dropped
     */
    public Built build(long nowMs) {
        HerdRebuild jfr = new HerdRebuild(); // I-5: the clock and the heap are read only while JFR records
        boolean rec = jfr.isEnabled();
        if (rec) {
            jfr.begin();
            jfr.heapBefore = usedHeap();
        }
        long t = JfrClock.nanos(rec);
        List<GameEvent> events = eventStore.loadAll();
        jfr.loadNs = JfrClock.since(rec, t);
        // F4: the players and markets tables are projections of the journal. Whatever they lack (a database built from the journal
        // alone, a write that never committed) is written before anything reads them; a journal of schema version 1 has nothing to
        // add and leaves them as they are.
        if (marketRegistry.projectFromJournal(events) > 0) {
            marketRegistry.reload();
        }
        players.repairFromJournal(events);
        players.all().stream().mapToInt(PlayerDirectory.Row::playerIndex).max().ifPresent(serverMeta::raiseNextPlayerIndexAbove);
        List<Market> markets = marketRegistry.all();
        EngineHub hub = new EngineHub();
        BetFeed betFeed = new BetFeed();
        EventApplier applier = EventApplier.over(hub, betFeed);
        for (Market market : markets) {
            hub.install(market); // a fresh hub: BTC's engines are rebuilt from the registry row, like every other market's
        }
        // The applier reads nothing outside the hub: the players a journal of schema version 1 does not describe come from the table,
        // put into the hub's roster before the replay (an event of version 2 then overrides them).
        hub.roster().seed(players.all());
        // The ledger keeps no history in memory: the movements the replay produces and the ledger_entries table lacks (all of
        // them on the first start after V16, the tail a crash or a failed write left otherwise) are written from here in
        // batches; a write that fails leaves them in the hub's outbox, where the live flush takes them over.
        hub.ledgerOutbox().skipStored(ledgerRows.stored()::has);
        hub.ledgerOutbox().spillTo(ledgerRows::insertBatch, LEDGER_SPILL_BATCH);
        t = JfrClock.nanos(rec);
        // The derived rows of a settlement are written after its event is committed (GameService#resolveRound), and a
        // stop or a failing write between the two leaves rounds without them - possibly several, since the game goes on
        // while a write is retried. Every resolved round of the journal that has no row is repaired below.
        Set<String> withRows = roundResults.allRoundIds();
        List<GameEvent.RoundResolved> unwrittenEvents = new ArrayList<>();
        List<RoundReport> unwrittenReports = new ArrayList<>();
        for (GameEvent event : events) {
            Object result = applier.apply(event); // also keeps the hub's index of the windows that hold bets
            if (event instanceof GameEvent.RoundResolved resolved) {
                RoundReport report = (RoundReport) result;
                if (!withRows.contains(report.window().roundId())) {
                    unwrittenEvents.add(resolved);
                    unwrittenReports.add(report);
                }
            }
        }
        // The oracle counter: what the journal issued (RoundResolved.oracleSeq, raised while replaying) or the database counter holds
        // (the floor the chain service demanded, rows of a version-1 journal), whichever is higher.
        hub.raiseOracleSeq(serverMeta.oracleSeq());
        if (hub.oracleSeq() > serverMeta.oracleSeq()) {
            serverMeta.projectOracleSeq(hub.oracleSeq()); // the counter in the database follows the journal (a database built from it)
        }
        if (rec) {
            jfr.applyNs = JfrClock.since(rec, t);
            jfr.events = events.size();
            jfr.heapAfter = usedHeap();
            jfr.end();
            jfr.commit();
        }
        // Written from the events, here, before the boundary prices are read, which read them; a write that fails does
        // not fail the build (the game does not depend on the rows, the engine's marker says what is resolved): it goes to
        // the background retry of GameService, and the close of that round is added to the boundary prices from the event.
        List<GameEvent.RoundResolved> notWritten = new ArrayList<>();
        for (int i = 0; i < unwrittenEvents.size(); i++) {
            gameService.repairSettledResult(unwrittenEvents.get(i), unwrittenReports.get(i));
            if (gameService.rowsPending(unwrittenReports.get(i).window().roundId())) {
                notWritten.add(unwrittenEvents.get(i));
            }
        }
        cancelRoundsOfClosedMarkets(applier, hub, markets, nowMs);
        hub.ledgerOutbox().endReplay();

        Map<RoundProgress.SeriesKey, RoundWindow> cursors = new LinkedHashMap<>();
        StringBuilder series = new StringBuilder();
        for (Market market : markets) {
            if (!market.isActive() || !gameService.runs(market)) {
                continue;
            }
            for (Interval interval : Interval.values()) {
                RoundProgress.SeriesKey key = new RoundProgress.SeriesKey(market.id(), interval);
                RoundWindow seed = seeder.seedFor(hub, market, interval, nowMs);
                cursors.put(key, seed);
                series.append(' ').append(market.id()).append('/').append(interval).append(".next=")
                        .append(seed.roundId());
            }
        }
        BoundaryPrices.Snapshot closes = boundaryPrices.load(cursors);
        for (GameEvent.RoundResolved resolved : notWritten) {
            closes.settled(new RoundWindow(resolved.market(), resolved.interval(), resolved.windowStart(),
                    resolved.windowEnd()), new BigDecimal(resolved.closePrice()).movePointRight(2).longValueExact(),
                    resolved.venue());
        }
        return new Built(hub, betFeed, cursors, closes, events.size(), markets.size(), series.toString());
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** Restart safety net: a market was closed in the database but the process stopped before its
     *  listener cancelled the open rounds, so the replayed bet book still holds bets whose stake is
     *  debited and that nobody would ever settle — the scheduler does not run a closed market. Every
     *  window of such a market, of either series, that still holds bets and has no result row is cancelled
     *  with a refund. */
    private void cancelRoundsOfClosedMarkets(EventApplier applier, EngineHub hub, List<Market> markets,
                                             long nowMs) {
        for (Market market : markets) {
            if (market.isActive() || market.closedAtMs() == null) {
                continue;
            }
            for (Interval interval : Interval.values()) {
                for (RoundWindow window : seeder.pending(hub, market, interval)) {
                    LOG.warnf("market %s closed while round %s still held bets — cancelling it with a refund",
                            market.id(), window.roundId());
                    cancellations.cancel(applier, hub, market, window, "market closed: " + market.closeReason(), nowMs);
                }
            }
        }
    }
}
