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
import crowdvsmarket.server.snapshot.EngineSnapshotRepository;
import crowdvsmarket.server.snapshot.SnapshotService;
import crowdvsmarket.server.snapshot.StateCodec;
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
 * <p><b>Snapshot, then tail (architecture plan F5).</b> The state starts from the newest snapshot that can be trusted
 * ({@link #fromSnapshot}) and replays the journal after it in pages ({@code EventStore#replayAfter}); with no usable snapshot it
 * replays the whole journal the same way. The projection tables are repaired from the journal events of their own types in
 * both cases, and a snapshot that would leave a gap only a replay can fill (a ledger row or a result row missing) is not used.
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
    @Inject
    SnapshotService snapshots;
    @Inject
    crowdvsmarket.server.service.SidecarReconciler reconciler;

    /** How many ledger movements a replay collects before it writes them to the table (a first start with an empty table). */
    static final int LEDGER_SPILL_BATCH = 5_000;

    /** The event types the projection tables are repaired from ({@code projectFromJournal}, {@code repairFromJournal}): the repair reads
     *  these alone, so it does not need the whole journal even when the engine state starts from a snapshot. */
    static final List<String> PROJECTION_EVENT_TYPES = List.of("PlayerRegistered", "NicknameChanged", "BotStrategyAssigned",
            "BotRetired", "MarketListed", "MarketClosed");

    /** A finished build: ready to be swapped in. {@code cursors} holds one window per running series;
     *  {@code boundaries} the closes of the settled rounds those cursors may open at; {@code description}
     *  is the log line's tail. {@code eventsReplayed} counts the journal rows applied; with {@code snapshotSeq} above
     *  zero those are the rows after that snapshot (F5), and {@code lastEventId} is the id of the last row applied. */
    public record Built(EngineHub hub, BetFeed betFeed, Map<RoundProgress.SeriesKey, RoundWindow> cursors,
                        BoundaryPrices.Snapshot boundaries, int eventsReplayed, int marketCount,
                        String description, long snapshotSeq, long lastEventId) {

        public Built(EngineHub hub, BetFeed betFeed, Map<RoundProgress.SeriesKey, RoundWindow> cursors,
                     BoundaryPrices.Snapshot boundaries, int eventsReplayed, int marketCount, String description) {
            this(hub, betFeed, cursors, boundaries, eventsReplayed, marketCount, description, 0, 0);
        }
    }

    /** A state being built: the hub and feed, the applier over them, and what the replay found written or not. */
    private static final class Replay {
        final EngineHub hub = new EngineHub();
        final BetFeed betFeed = new BetFeed();
        final EventApplier applier = EventApplier.over(hub, betFeed);
        final List<GameEvent.RoundResolved> unwrittenEvents = new ArrayList<>();
        final List<RoundReport> unwrittenReports = new ArrayList<>();
        /** Cancelled rounds whose result row the table lacks (Р-107), with the bets each held when it was cancelled. */
        final List<GameEvent.RoundCancelled> unwrittenCancelled = new ArrayList<>();
        final List<Integer> unwrittenCancelledBets = new ArrayList<>();
        long snapshotSeq;
        long lastEventId;
        int applied;
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
        // F4: the players and markets tables are projections of the journal. Whatever they lack (a database built from the journal
        // alone, a write that never committed) is written before anything reads them; a journal of schema version 1 has nothing to
        // add and leaves them as they are. The repair folds the events of a few types, read on their own: it needs them all, snapshot
        // or not (a snapshot says nothing about whether a row was written), and they are a small part of the journal.
        List<GameEvent> projected = eventStore.loadOfTypes(PROJECTION_EVENT_TYPES);
        if (marketRegistry.projectFromJournal(projected) > 0) {
            marketRegistry.reload();
        }
        players.repairFromJournal(projected);
        players.all().stream().mapToInt(PlayerDirectory.Row::playerIndex).max().ifPresent(serverMeta::raiseNextPlayerIndexAbove);
        List<Market> markets = marketRegistry.all();
        // The ledger keeps no history in memory: the movements the replay produces and the ledger_entries table lacks (all of
        // them on the first start after V16, the tail a crash or a failed write left otherwise) are written from here in
        // batches; a write that fails leaves them in the hub's outbox, where the live flush takes them over.
        LedgerEntryRepository.Stored storedRows = ledgerRows.stored();
        // The derived rows of a settlement are written after its event is committed (GameService#resolveRound), and a
        // stop or a failing write between the two leaves rounds without them - possibly several, since the game goes on
        // while a write is retried. Every resolved round of the journal that has no row is repaired below.
        Set<String> withRows = roundResults.allRoundIds();
        Replay replay = fromSnapshot(markets, storedRows, withRows);
        if (replay == null) {
            replay = start(markets, storedRows);
        }
        jfr.loadNs = JfrClock.since(rec, t);
        t = JfrClock.nanos(rec);
        Replay state = replay;
        state.lastEventId = eventStore.replayAfter(state.snapshotSeq, (id, event) -> {
            if (event instanceof GameEvent.RoundCancelled cancelled) {
                RoundWindow window = new RoundWindow(cancelled.market(), cancelled.interval(), cancelled.windowStart(), cancelled.windowEnd());
                if (!withRows.contains(window.roundId())) {
                    state.unwrittenCancelled.add(cancelled);
                    state.unwrittenCancelledBets.add(state.hub.engine(cancelled.market(), cancelled.interval()).betBook().count(window.roundId()));
                }
            }
            Object result = state.applier.apply(event); // also keeps the hub's index of the windows that hold bets
            if (event instanceof GameEvent.RoundResolved resolved) {
                RoundReport report = (RoundReport) result;
                if (!withRows.contains(report.window().roundId())) {
                    state.unwrittenEvents.add(resolved);
                    state.unwrittenReports.add(report);
                }
            }
            state.applied++;
        });
        EngineHub hub = state.hub;
        BetFeed betFeed = state.betFeed;
        EventApplier applier = state.applier;
        // The oracle counter: what the journal issued (RoundResolved.oracleSeq, raised while replaying) or the database counter holds
        // (the floor the chain service demanded, rows of a version-1 journal), whichever is higher.
        hub.raiseOracleSeq(serverMeta.oracleSeq());
        if (hub.oracleSeq() > serverMeta.oracleSeq()) {
            serverMeta.projectOracleSeq(hub.oracleSeq()); // the counter in the database follows the journal (a database built from it)
        }
        if (rec) {
            jfr.applyNs = JfrClock.since(rec, t);
            jfr.events = state.applied;
            jfr.heapAfter = usedHeap();
            jfr.end();
            jfr.commit();
        }
        // Written from the events, here, before the boundary prices are read, which read them; a write that fails does
        // not fail the build (the game does not depend on the rows, the engine's marker says what is resolved): it goes to the
        // background retry of GameService, and the close of that round is added to the boundary prices from the event.
        List<GameEvent.RoundResolved> notWritten = new ArrayList<>();
        for (int i = 0; i < state.unwrittenEvents.size(); i++) {
            gameService.repairSettledResult(state.unwrittenEvents.get(i), state.unwrittenReports.get(i));
            if (gameService.rowsPending(state.unwrittenReports.get(i).window().roundId())) {
                notWritten.add(state.unwrittenEvents.get(i));
            }
        }
        for (int i = 0; i < state.unwrittenCancelled.size(); i++) {
            GameEvent.RoundCancelled cancelled = state.unwrittenCancelled.get(i);
            Market market = markets.stream().filter(m -> m.id().equals(cancelled.market())).findFirst().orElse(null);
            if (market != null) {
                LOG.warnf("round %s is cancelled in the journal but has no result row - writing it from the event",
                        new RoundWindow(cancelled.market(), cancelled.interval(), cancelled.windowStart(), cancelled.windowEnd()).roundId());
                cancellations.repairRow(market, cancelled, state.unwrittenCancelledBets.get(i));
            }
        }
        // Р-107: the rows of withdrawal requests are written after their events are durable; what a stop left out is written from the engine
        reconciler.withdrawalRows(hub.ledger().exportState().withdrawals(), players);
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
                RoundWindow seed = seeder.seedFor(hub, market, interval, nowMs, roundResults.latestWindowEnd(market.id(), interval));
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
        return new Built(hub, betFeed, cursors, closes, state.applied, markets.size(), series.toString(), state.snapshotSeq,
                state.lastEventId);
    }

    /** A fresh state with the engines of every market in the registry (closed ones too: their history replays), the roster
     *  seeded from the {@code players} table (a journal of schema version 1 does not describe the players: an event of version 2
     *  then overrides them) and the ledger outbox set to write the movements the table lacks. */
    private Replay start(List<Market> markets, LedgerEntryRepository.Stored storedRows) {
        Replay replay = new Replay();
        for (Market market : markets) {
            replay.hub.install(market); // a fresh hub: BTC's engines are rebuilt from the registry row, like every other market's
        }
        replay.hub.roster().seed(players.all());
        replay.hub.ledgerOutbox().skipStored(storedRows::has);
        replay.hub.ledgerOutbox().spillTo(ledgerRows::insertBatch, LEDGER_SPILL_BATCH);
        return replay;
    }

    /**
     * F5: the state of the newest snapshot that can be trusted, or {@code null} for a replay of the whole journal. A snapshot is
     * left aside, with a log line, when
     * <ul>
     *   <li>it does not verify (format version, hash: {@link SnapshotService#verified}) or does not decode into the registry's markets;
     *   <li>it stands ahead of the journal (the journal is shorter than it);
     *   <li>the {@code ledger_entries} table lacks a movement the snapshot's ledger has made: the tail would not write it, only a
     *       replay of the journal can - correct rather than fast;
     *   <li>a round resolved at or before it has no result row: the repair of that row needs the round's report, which only a
     *       replay of it gives.
     * </ul>
     * The older snapshots are tried in turn: the conditions are the looser the earlier the snapshot is.
     */
    private Replay fromSnapshot(List<Market> markets, LedgerEntryRepository.Stored storedRows, Set<String> withRows) {
        if (!snapshots.enabled()) {
            return null;
        }
        List<EngineSnapshotRepository.Meta> candidates = snapshots.stored();
        if (candidates.isEmpty()) {
            return null;
        }
        long journalEnd = eventStore.lastId();
        for (EngineSnapshotRepository.Meta meta : candidates) {
            SnapshotService.Verified verified = snapshots.verified(meta).orElse(null);
            if (verified == null) {
                continue;
            }
            if (verified.seq() > journalEnd) {
                LOG.warnf("engine snapshot %d ignored: the journal ends at row %d", verified.seq(), journalEnd);
                continue;
            }
            Replay replay = start(markets, storedRows);
            try {
                StateCodec.decode(verified.text(), replay.hub, replay.betFeed);
            } catch (IllegalArgumentException e) {
                LOG.warnf("engine snapshot %d ignored: %s", verified.seq(), e.getMessage());
                continue;
            }
            if (!storedRows.hasAllThrough(replay.hub.ledger().lastSequence())) {
                LOG.warnf("engine snapshot %d ignored: the ledger_entries table lacks movements up to %d that only a replay of the journal "
                        + "can write", verified.seq(), replay.hub.ledger().lastSequence());
                continue;
            }
            Set<String> missing = eventStore.resolvedRoundIds(verified.seq());
            missing.removeAll(withRows);
            if (!missing.isEmpty()) {
                LOG.warnf("engine snapshot %d ignored: %d round(s) resolved before it have no result row (e.g. %s) that only a replay "
                        + "of the journal can repair", verified.seq(), missing.size(), missing.iterator().next());
                continue;
            }
            replay.snapshotSeq = verified.seq();
            return replay;
        }
        LOG.warn("no engine snapshot can be used - replaying the whole journal");
        return null;
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
