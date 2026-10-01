package crowdvsmarket.server.scheduler;

import crowdvsmarket.engine.game.GameEngine;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotService;
import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundCalendar;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.DbMarketRegistry;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.service.GameService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * "survive restarts": rebuilds every piece of in-memory state from durable storage, in this order
 * — origin epoch → player directory → market registry → a fresh {@link EngineHub} with the engines
 * of EVERY market in the registry (closed ones too: their history replays) → replay
 * {@code game_events} → seed {@link RoundProgress} for each running market → ensure the bot players
 * exist. Runs once at {@link StartupEvent}, and is also called directly by the restart-recovery
 * integration test to simulate a process restart without actually killing the JVM (same DB, same
 * code path), and — as {@link #rebuildStateLocked} — by {@code GameService} when a settlement
 * transaction failed and memory must be made to agree with the database again.
 */
@ApplicationScoped
public class EngineRebuildService {

    private static final Logger LOG = Logger.getLogger(EngineRebuildService.class);

    @Inject
    RoundCalendar roundCalendar;
    @Inject
    PlayerDirectory players;
    @Inject
    DbMarketRegistry marketRegistry;
    @Inject
    EngineHub hub;
    @Inject
    BetFeed betFeed;
    @Inject
    EventStore eventStore;
    @Inject
    EventApplier eventApplier;
    @Inject
    RoundProgress roundProgress;
    @Inject
    RoundResultRepository roundResults;
    @Inject
    BotService botService;
    @Inject
    GameConfig config;

    @Inject
    GameService gameService;

    /** True once the first rebuild has finished: before it the engine is empty, no series is seeded
     *  and nothing the scheduler does would mean anything. */
    private volatile boolean ready;

    void onStart(@Observes StartupEvent ev) {
        rebuild();
    }

    /** Whether {@link #rebuildStateLocked} has completed at least once in this process. */
    public boolean isReady() {
        return ready;
    }

    /**
     * Runs holding {@code gameService}'s write lock ({@link crowdvsmarket.server.service.GameService
     * #runExclusively}) — the same exclusive-access guarantee every {@code GameService} mutating
     * method gets by holding that same lock itself (wave-2 fix: {@code GameService} moved from a
     * plain {@code synchronized} monitor to a {@link java.util.concurrent.locks.ReentrantReadWriteLock}
     * so its many read paths, e.g. {@code home}/{@code ratings}, can run concurrently with each
     * other — see that class's javadoc) — so a rebuild can never interleave with a live bet/round-
     * resolution/withdrawal, or with any read, and swap {@link EngineHub}'s engine references out
     * from under an in-flight call.
     *
     * <p>{@link BotService#ensureBotsExist} runs deliberately <b>outside</b> that block: it is
     * idempotent and does not touch {@link EngineHub} directly (it goes through
     * {@code GameService.createBotPlayer}, which takes the same lock itself), and
     * {@link BotService} is a separate monitor that the scheduler's own tick acquires in the
     * opposite order (bots first, then {@code GameService} calls) — nesting the two here in the
     * other order would be a classic lock-order-inversion deadlock risk against a concurrently
     * running scheduler tick. For the same reason {@code GameService}'s failure recovery calls
     * {@link #rebuildStateLocked} (no bots) and never this method.
     */
    public void rebuild() {
        long now = System.currentTimeMillis();
        gameService.runExclusively(this::rebuildStateLocked);
        botService.ensureBotsExist(now);
    }

    /**
     * The state half of {@link #rebuild}. <b>The caller must hold {@code GameService}'s write
     * lock</b> ({@code runExclusively}, or a mutating method of that class that is recovering from a
     * failed transaction).
     *
     * <p>The series of every running market is seeded from that market's latest {@code round_results}
     * row — its {@code window_end_ms} is where the next window to resolve starts — or, for a market
     * without one, from the window that contains "now" (docs/research/markets_architecture.md §4
     * п.2): never from the calendar origin, which for a market listed later would make the scheduler
     * resolve every window since the origin. After a long stop the catch-up rule of the scheduler
     * (§4 п.5) skips the empty windows in between.
     */
    public void rebuildStateLocked() {
        long now = System.currentTimeMillis();
        roundCalendar.init(now);
        players.reload();
        marketRegistry.reload();

        hub.resetEmpty();
        betFeed.reset();
        roundProgress.clear();
        List<Market> markets = marketRegistry.all();
        for (Market market : markets) {
            hub.ensureMarket(market);
        }
        List<GameEvent> events = eventStore.loadAll();
        for (GameEvent event : events) {
            eventApplier.apply(event);
        }
        gameService.cancelStrandedRoundsLocked(now);

        StringBuilder series = new StringBuilder();
        for (Market market : gameService.runningMarkets()) {
            for (Interval interval : Interval.values()) {
                RoundWindow seed = seedFor(market, interval, now);
                roundProgress.seed(market.id(), interval, seed);
                series.append(' ').append(market.id()).append('/').append(interval).append(".next=")
                        .append(seed.roundId());
            }
        }
        ready = true;
        LOG.infof("engine rebuilt: %d event(s) replayed, markets=%d, players=%d, ledger-player-count=%d,%s",
                events.size(), markets.size(), players.all().size(), hub.ledger().playerIds().size(), series);
    }

    private RoundWindow seedFor(Market market, Interval interval, long nowMs) {
        Optional<Long> latestEnd = roundResults.latestWindowEnd(market.id(), interval);
        if (latestEnd.isEmpty()) {
            return roundCalendar.windowContaining(market.id(), interval, nowMs);
        }
        long stepMs = config.roundDurations().forInterval(interval);
        RoundWindow seed = new RoundWindow(market.id(), interval, latestEnd.get(), latestEnd.get() + stepMs);
        // The newest row can be the operator's cancellation of the betting round while the running
        // round before it was never resolved (a stop in between): that round's bets would be
        // stranded behind the cursor. Look two windows back and start at the earliest one that still
        // holds bets and has no result.
        GameEngine engine = hub.engine(market.id(), interval);
        RoundWindow start = seed;
        for (int back = 1; back <= 2; back++) {
            RoundWindow earlier = new RoundWindow(market.id(), interval, seed.startMs() - back * stepMs,
                    seed.startMs() - (back - 1) * stepMs);
            if (!engine.betBook().betsFor(earlier.roundId()).isEmpty() && !roundResults.exists(earlier.roundId())) {
                start = earlier;
            }
        }
        return start;
    }
}
