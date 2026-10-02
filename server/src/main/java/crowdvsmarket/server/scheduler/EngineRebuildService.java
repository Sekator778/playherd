package crowdvsmarket.server.scheduler;

import crowdvsmarket.server.bots.BotService;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.domain.EngineHub;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.domain.RoundCalendar;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.markets.MarketRegistry;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.util.LogThrottle;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * "survive restarts": rebuilds every piece of in-memory state from durable storage, in this order
 * — origin epoch → player directory → market registry → <b>a complete new engine state, built aside</b>
 * ({@link EngineStateBuilder}: a fresh {@link EngineHub} with the engines of EVERY market in the
 * registry, the replay of {@code game_events}, the cursor of every running series) → the swap of that
 * state into the live singletons → the bot players ({@link #rebuild} only). Runs once at {@link
 * StartupEvent}, and is also called directly by the restart-recovery integration test to simulate a
 * process restart without actually killing the JVM (same DB, same code path), and — as {@link
 * #recoverAfterFailure} — by {@code GameService} when a settlement transaction failed and memory must
 * be made to agree with the database again.
 *
 * <p><b>Fail-stop.</b> Until the first rebuild has finished, and from the moment a failure may have
 * left memory AHEAD of the database ({@link #recoverAfterFailure}: an event was applied inside a
 * transaction that then rolled back) until a rebuild has succeeded, {@link #isReady} is false, and
 * the game answers nothing from memory that cannot be trusted:
 * <ul>
 *   <li><b>Every call that changes anything</b> ({@code placeBet}, {@code cancelBet}, sign-in,
 *       {@code setNickname}, withdrawals and deposit credits, settlements and cancellations, the
 *       operator's actions) answers 503 {@code ENGINE_NOT_READY} and has changed nothing.
 *   <li><b>Every read that comes from the engine</b> (balances and "in play", the ledger, ratings,
 *       the crowd accounts, the running and betting round of a series, the bet feed, a profile's
 *       season record, {@code home}) answers 503 too: in a failed recovery the memory may show a
 *       settlement that the database never saw, and a result that is later taken back is worse than
 *       an error. The last complete state is deliberately NOT served.
 *   <li><b>Every read that comes from the database or needs no engine</b> (a settled or cancelled
 *       round, the cancellations log, "unseen results", the status of a withdrawal or deposit, prices
 *       and the liquidation preview, the server info) is answered as usual.
 * </ul>
 * The scheduler keeps trying the rebuild until it succeeds ({@link #retryPendingRebuild}, at most
 * once every {@code herd.rebuild.retry-ms}); nothing else is needed to get the game back, and once it
 * has, the state is what a restart would give.
 *
 * <p><b>Aside, then swap.</b> A rebuild never touches the live state until the new one is complete,
 * so a failure — in the database read, half-way through the replay, in the seeding — leaves exactly
 * what was there. That matters in both directions: a plain rebuild that fails (an operator's, a
 * test's) leaves the game serving from the state it had, and a failed recovery leaves nothing
 * half-built behind for a request to reach (the old in-place rebuild reset the hub first and could
 * stop with an empty ledger, which handed a player who bet in that moment a second starter pack).
 */
@ApplicationScoped
public class EngineRebuildService {

    private static final Logger LOG = Logger.getLogger(EngineRebuildService.class);

    @Inject
    RoundCalendar roundCalendar;
    @Inject
    PlayerDirectory players;
    @Inject
    MarketRegistry marketRegistry;
    @Inject
    EngineHub hub;
    @Inject
    BetFeed betFeed;
    @Inject
    RoundProgress roundProgress;
    @Inject
    EngineStateBuilder builder;
    @Inject
    BotService botService;
    @Inject
    GameService gameService;

    /** The least time between two attempts of {@link #retryPendingRebuild} (milliseconds): a rebuild
     *  that cannot succeed (the database is down, an event cannot be replayed) must not read the whole
     *  log, under the engine lock, every second. */
    @ConfigProperty(name = "herd.rebuild.retry-ms", defaultValue = "2000")
    long retryGapMs;

    /** True while the in-memory state can be trusted: after the first rebuild has finished, and again
     *  after every recovery that succeeded; false in between (class javadoc). */
    private volatile boolean ready;
    /** A recovery was started and has not succeeded yet: the scheduler's cue to try again. Not set
     *  before the first rebuild has finished — that one is the startup observer's, and a second one
     *  next to it would only repeat its work. */
    private volatile boolean recoveryPending;
    private volatile long lastAttemptAtNanos;
    private final LogThrottle retryFailureLog = new LogThrottle(60_000L);

    void onStart(@Observes StartupEvent ev) {
        rebuild();
    }

    /** Whether the in-memory state can be trusted — see the class javadoc for what is answered when
     *  it cannot. */
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
     * resolution/withdrawal, or with any read, and swap {@link EngineHub}'s state out from under an
     * in-flight call.
     *
     * <p>{@link BotService#ensureBotsExist} runs deliberately <b>outside</b> that block: it is
     * idempotent and does not touch {@link EngineHub} directly (it goes through
     * {@code GameService.createBotPlayer}, which takes the same lock itself), and
     * {@link BotService} is a separate monitor that the scheduler's own tick acquires in the
     * opposite order (bots first, then {@code GameService} calls) — nesting the two here in the
     * other order would be a classic lock-order-inversion deadlock risk against a concurrently
     * running scheduler tick. For the same reason {@code GameService}'s failure recovery calls
     * {@link #recoverAfterFailure} (no bots) and never this method.
     *
     * @throws RuntimeException when the new state cannot be built; the state in use is then untouched
     */
    public void rebuild() {
        rebuild(System.currentTimeMillis());
    }

    /** {@link #rebuild()} as of {@code nowMs} — for a test that drives the clock. */
    public void rebuild(long nowMs) {
        gameService.runExclusively(() -> rebuildStateLocked(nowMs));
        botService.ensureBotsExist(nowMs);
    }

    /** {@link #rebuildStateLocked(long)} as of the current time. */
    public void rebuildStateLocked() {
        rebuildStateLocked(System.currentTimeMillis());
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
     * resolve every window since the origin — and never later than the earliest window that still
     * holds a bet ({@code SeriesSeeder}). After a long stop the catch-up rule of the scheduler (§4
     * п.5) skips the empty windows in between.
     */
    public void rebuildStateLocked(long nowMs) {
        lastAttemptAtNanos = System.nanoTime();
        roundCalendar.init(nowMs);
        players.reload();
        marketRegistry.reload();

        EngineStateBuilder.Built built = builder.build(nowMs);

        // The swap of a state that is complete. The cursors go first: replaceAll checks its windows and
        // assigns last, so if it were ever to throw, nothing would have changed; the two that follow
        // cannot fail.
        roundProgress.replaceAll(built.cursors());
        hub.adopt(built.hub());
        betFeed.adopt(built.betFeed());
        ready = true;
        recoveryPending = false;
        retryFailureLog.reset("retry");
        LOG.infof("engine rebuilt: %d event(s) replayed, markets=%d, players=%d, ledger-player-count=%d,%s",
                built.eventsReplayed(), built.marketCount(), players.all().size(), hub.ledger().playerIds().size(),
                built.description());
    }

    /**
     * A round operation failed after the engine may have been changed (an event applied inside a
     * transaction that rolled back): memory may be ahead of the database. From here on nothing is
     * served from it ({@link #isReady} is false) until a rebuild has succeeded — this call tries one
     * at once, and if it fails too the scheduler keeps trying ({@link #retryPendingRebuild}). Never
     * throws: the caller rethrows the failure it is recovering from, with this one added to it as
     * suppressed. <b>The caller holds the engine write lock.</b>
     */
    public void recoverAfterFailure(Throwable cause) {
        ready = false;
        recoveryPending = true;
        try {
            rebuildStateLocked(System.currentTimeMillis());
        } catch (RuntimeException | Error rebuildFailure) {
            cause.addSuppressed(rebuildFailure);
            LOG.error("the engine rebuild after a failed round operation failed as well — the game answers 503 "
                    + "until a rebuild succeeds, which the scheduler keeps trying", rebuildFailure);
        }
    }

    /**
     * The scheduler's cue, once per tick while {@link #isReady} is false: when a recovery is pending
     * and the retry gap has passed, tries the rebuild again under the engine lock.
     *
     * @return whether the engine is ready now (it was, or this attempt made it so)
     */
    public boolean retryPendingRebuild() {
        if (ready) {
            return true;
        }
        if (!recoveryPending || System.nanoTime() - lastAttemptAtNanos < retryGapMs * 1_000_000L) {
            return false;
        }
        gameService.runExclusively(() -> {
            if (ready) {
                return; // someone else recovered it while this call waited for the lock
            }
            long nowMs = System.currentTimeMillis();
            try {
                rebuildStateLocked(nowMs);
            } catch (RuntimeException | Error e) {
                if (retryFailureLog.allow("retry", nowMs)) {
                    LOG.error("the engine rebuild failed again — still answering 503, will retry", e);
                } else {
                    LOG.debugf("the engine rebuild failed again: %s", e.toString());
                }
            }
        });
        return ready;
    }
}
