package crowdvsmarket.server.scheduler;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.bots.BotService;
import crowdvsmarket.server.domain.ComparisonAccounts;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundCalendar;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.events.EventApplier;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.persistence.RoundResultRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * "survive restarts": rebuilds every piece of in-memory state from durable storage, in this order
 * — origin epoch → player directory → replay {@code game_events} into a fresh
 * {@link EngineHub}/{@link ComparisonAccounts} → seed {@link RoundProgress} from the latest
 * resolved round per series → ensure the 20 bot players exist. Runs once at
 * {@link StartupEvent}, and is also called directly by the restart-recovery integration test to
 * simulate a process restart without actually killing the JVM (same DB, same code path).
 */
@ApplicationScoped
public class EngineRebuildService {

    private static final Logger LOG = Logger.getLogger(EngineRebuildService.class);

    @Inject
    RoundCalendar roundCalendar;
    @Inject
    PlayerDirectory players;
    @Inject
    EngineHub hub;
    @Inject
    ComparisonAccounts comparisonAccounts;
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
    crowdvsmarket.server.config.GameConfig config;

    @Inject
    crowdvsmarket.server.service.GameService gameService;

    void onStart(@Observes StartupEvent ev) {
        rebuild();
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
     * running scheduler tick.
     */
    public void rebuild() {
        long now = System.currentTimeMillis();
        gameService.runExclusively(() -> {
            roundCalendar.init(now);
            players.reload();

            hub.resetEmpty();
            comparisonAccounts.reset();
            List<GameEvent> events = eventStore.loadAll();
            for (GameEvent event : events) {
                eventApplier.apply(event);
            }

            for (Interval interval : Interval.values()) {
                RoundWindow seed = roundResults.latestWindowEnd(interval)
                        .map(endMs -> new RoundWindow(interval, endMs, endMs + config.roundDurations().forInterval(interval)))
                        .orElseGet(() -> roundCalendar.firstWindow(interval));
                roundProgress.seed(interval, seed);
            }

            LOG.infof("engine rebuilt: %d event(s) replayed, players=%d, ledger-player-count=%d, "
                            + "hourly.next=%s daily.next=%s",
                    events.size(), players.all().size(), hub.ledger().playerIds().size(),
                    roundProgress.next(Interval.HOURLY).roundId(), roundProgress.next(Interval.DAILY).roundId());
        });
        botService.ensureBotsExist(now);
    }
}
