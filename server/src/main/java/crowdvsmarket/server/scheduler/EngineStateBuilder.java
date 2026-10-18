package crowdvsmarket.server.scheduler;

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
import crowdvsmarket.server.service.BoundaryPrices;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.service.RoundCancellations;
import crowdvsmarket.server.service.SeriesSeeder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>The one thing it does that outlives a failed build is the refund of a closed market's open
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
        List<Market> markets = marketRegistry.all();
        EngineHub hub = new EngineHub();
        BetFeed betFeed = new BetFeed();
        EventApplier applier = EventApplier.over(hub, betFeed, players);
        for (Market market : markets) {
            hub.install(market); // a fresh hub: BTC's engines are rebuilt from the registry row, like every other market's
        }
        HerdRebuild jfr = new HerdRebuild(); // I-5: the clock and the heap are read only while JFR records
        boolean rec = jfr.isEnabled();
        if (rec) {
            jfr.begin();
            jfr.heapBefore = usedHeap();
        }
        long t = JfrClock.nanos(rec);
        List<GameEvent> events = eventStore.loadAll();
        jfr.loadNs = JfrClock.since(rec, t);
        t = JfrClock.nanos(rec);
        for (GameEvent event : events) {
            applier.apply(event); // also keeps the hub's index of the windows that hold bets
        }
        if (rec) {
            jfr.applyNs = JfrClock.since(rec, t);
            jfr.events = events.size();
            jfr.heapAfter = usedHeap();
            jfr.end();
            jfr.commit();
        }
        cancelRoundsOfClosedMarkets(applier, hub, markets, nowMs);

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
        return new Built(hub, betFeed, cursors, boundaryPrices.load(cursors), events.size(), markets.size(),
                series.toString());
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
