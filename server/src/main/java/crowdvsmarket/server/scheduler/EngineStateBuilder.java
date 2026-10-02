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
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.service.RoundCancellations;
import crowdvsmarket.server.service.SeriesSeeder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jboss.logging.Logger;

/**
 * Builds a complete engine state ASIDE: a fresh {@link EngineHub} with the engines of every market in
 * the registry (closed ones too: their history replays), a fresh {@link BetFeed}, the replay of
 * {@code game_events} into both, the cancellation of what a closed market left open, and the cursor
 * of every running series — and touches nothing that serves requests. Whatever it throws, the live
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
    GameService gameService;

    /** A finished build: ready to be swapped in. {@code cursors} holds one window per running series;
     *  {@code description} is the log line's tail. */
    public record Built(EngineHub hub, BetFeed betFeed, Map<RoundProgress.SeriesKey, RoundWindow> cursors,
                        int eventsReplayed, int marketCount, String description) {
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
            hub.ensureMarket(market);
        }
        List<GameEvent> events = eventStore.loadAll();
        // Every window that got a bet and has not been resolved or cancelled since — what the seeding
        // must not leave behind a cursor (SeriesSeeder). A bet that was cancelled by its player
        // leaves its window here; the hub's bet book, asked afterwards, says it holds nothing.
        Map<RoundProgress.SeriesKey, Map<Long, RoundWindow>> betWindows = new HashMap<>();
        for (GameEvent event : events) {
            applier.apply(event);
            trackBetWindows(betWindows, event);
        }
        cancelRoundsOfClosedMarkets(applier, hub, markets, betWindows, nowMs);

        Map<RoundProgress.SeriesKey, RoundWindow> cursors = new LinkedHashMap<>();
        StringBuilder series = new StringBuilder();
        for (Market market : markets) {
            if (!market.isActive() || !gameService.runs(market)) {
                continue;
            }
            for (Interval interval : Interval.values()) {
                RoundProgress.SeriesKey key = new RoundProgress.SeriesKey(market.id(), interval);
                RoundWindow seed = seeder.seedFor(hub, market, interval, nowMs, betWindowsOf(betWindows, key));
                cursors.put(key, seed);
                series.append(' ').append(market.id()).append('/').append(interval).append(".next=")
                        .append(seed.roundId());
            }
        }
        return new Built(hub, betFeed, cursors, events.size(), markets.size(), series.toString());
    }

    private static void trackBetWindows(Map<RoundProgress.SeriesKey, Map<Long, RoundWindow>> betWindows,
                                        GameEvent event) {
        switch (event) {
            case GameEvent.BetPlaced e -> betWindows
                    .computeIfAbsent(new RoundProgress.SeriesKey(e.market(), e.interval()), k -> new TreeMap<>())
                    .put(e.windowStart(), new RoundWindow(e.market(), e.interval(), e.windowStart(), e.windowEnd()));
            case GameEvent.RoundResolved e -> forget(betWindows, e.market(), e.interval(), e.windowStart());
            case GameEvent.RoundCancelled e -> forget(betWindows, e.market(), e.interval(), e.windowStart());
            default -> {
            }
        }
    }

    private static void forget(Map<RoundProgress.SeriesKey, Map<Long, RoundWindow>> betWindows, String market,
                               Interval interval, long windowStart) {
        Map<Long, RoundWindow> windows = betWindows.get(new RoundProgress.SeriesKey(market, interval));
        if (windows != null) {
            windows.remove(windowStart);
        }
    }

    private static Collection<RoundWindow> betWindowsOf(Map<RoundProgress.SeriesKey, Map<Long, RoundWindow>> betWindows,
                                                        RoundProgress.SeriesKey series) {
        return betWindows.getOrDefault(series, Map.of()).values();
    }

    /** Restart safety net: a market was closed in the database but the process stopped before its
     *  listener cancelled the open rounds, so the replayed bet book still holds bets whose stake is
     *  debited and that nobody would ever settle — the scheduler does not run a closed market. Every
     *  window of such a market that still holds bets and has no result row is cancelled with a
     *  refund. */
    private void cancelRoundsOfClosedMarkets(EventApplier applier, EngineHub hub, List<Market> markets,
                                             Map<RoundProgress.SeriesKey, Map<Long, RoundWindow>> betWindows,
                                             long nowMs) {
        for (Market market : markets) {
            if (market.isActive() || market.closedAtMs() == null) {
                continue;
            }
            for (Interval interval : Interval.values()) {
                RoundProgress.SeriesKey series = new RoundProgress.SeriesKey(market.id(), interval);
                for (RoundWindow window : seeder.pending(hub, market, interval, betWindowsOf(betWindows, series))) {
                    LOG.warnf("market %s closed while round %s still held bets — cancelling it with a refund",
                            market.id(), window.roundId());
                    cancellations.cancel(applier, hub, market, window, "market closed: " + market.closeReason(), nowMs);
                }
            }
        }
    }
}
