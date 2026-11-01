package crowdvsmarket.server.testsupport;

import crowdvsmarket.server.bots.BotService;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.scheduler.EngineRebuildService;
import crowdvsmarket.server.scheduler.RoundSchedulerService;
import crowdvsmarket.server.service.GameService;
import io.quarkus.arc.Arc;
import io.quarkus.test.junit.callback.QuarkusTestBeforeEachCallback;
import io.quarkus.test.junit.callback.QuarkusTestMethodContext;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CI regression fix (docs/research/server_design.md "Shared Postgres across @QuarkusTest
 * classes"): every {@code @QuarkusTest} class in this module shares ONE Postgres instance and ONE
 * {@code game_events} table for the whole {@code mvn verify} run (this codebase deliberately uses
 * plain JDBC with manual, immediately-committed transactions — see {@link Db}'s own javadoc —
 * never a per-test-rolled-back JTA transaction), and several classes call {@link
 * EngineRebuildService#rebuild()} directly to exercise "the exact same code path a real process
 * restart uses". That rebuild replays <b>every</b> row ever committed to {@code game_events} by
 * <b>any</b> earlier test class in the same JVM/fork, and several tests assert an absolute count
 * or a still-open betting window — true only against a genuinely empty/fresh database. Which
 * classes ran before, and what real time is left in any given round window, then depends purely on
 * surefire's classpath-scan order (filesystem-dependent, not alphabetical) and on incidental wall-
 * clock timing — different between machines/CI runners, which is what made the golden-fixture leak
 * (now fixed at the source in {@code EventStoreGoldenFixturesTest} itself) fail "consistently" on
 * CI's runner while passing locally, and is the same root cause behind {@code
 * BotRestartRecoveryTest}, {@code BetFeedCapsTest}, {@code GameFlowIntegrationTest} and {@code
 * UnseenResultsTest} failing once that specific crash stopped masking them.
 *
 * <p>Fix: give every {@code @QuarkusTest} class the same guarantee a real fresh deployment has —
 * an empty database and a matching in-memory engine — right before its first test method runs,
 * regardless of what any earlier class in the same suite run committed or whether Quarkus decided
 * to reuse its already-running instance for this class (it does not always restart just because a
 * class declares its own distinct {@code @TestProfile} — observed directly: {@code
 * BetFeedCapsTest} reusing an already-running instance left over from a preceding class, its still-
 * live background bot scheduler polluting the very feed this class asserts exact counts on). Only
 * truncating the tables from an {@code AfterAll}-style hook (tried first) is not enough for exactly
 * that reason — nothing then forces the CURRENTLY LIVE app's in-memory {@code EngineHub}/{@code
 * BetFeed}/{@code PlayerDirectory} to notice the truncation when Quarkus does not restart, so this
 * runs the truncate-then-rebuild pair itself, unconditionally, in {@link #beforeEach}, gated by
 * {@link #resetDone} to run only once per class (JUnit only exposes a per-method hook here; {@code
 * QuarkusTestBeforeClassCallback} looked like the obvious fit but fires before Quarkus has started
 * the app for a class that does restart, so the CDI container is not reliably up yet to reach
 * {@link Db}/{@link EngineRebuildService} through). The one class this measurably cost extra
 * latency against ({@code BotRestartRecoveryTest}, a 3-second betting window racing this same
 * truncate+rebuild pair immediately before its own explicit "simulate a restart" {@code rebuild()}
 * call) is hardened directly in that test ({@code ensureComfortableMargin}) rather than by trying
 * to make this shared hook cheaper — the round window's origin epoch is a fixed wall-clock instant
 * for the whole suite run (never reset per class), so no amount of trimming this hook's own cost
 * removes the underlying "landed close to this window's natural close" risk; only waiting for a
 * fresh window does.
 *
 * <p>Left alone on purpose: {@code server_meta} (only holds the monotonic {@code
 * next_player_index} counter — gaps in it are harmless, regressing it would not be) and Flyway's
 * own {@code flyway_schema_history}.
 */
public class CleanDatabasePerTestClass implements QuarkusTestBeforeEachCallback {

    /** One JVM fork runs the whole module's test suite (surefire's default forkCount=1/reuseForks
     *  — confirmed by the accumulation this class exists to fix), so a plain static set correctly
     *  tracks "already reset" for the run's whole lifetime; a test class is never reset twice. */
    private static final Set<Class<?>> RESET_DONE = ConcurrentHashMap.newKeySet();

    private static final String TRUNCATE_SQL = "TRUNCATE TABLE game_events, players, round_results, "
            + "player_round_history, withdrawal_requests, deposit_requests, auth_challenges, sessions, "
            + "oracle_records, player_deposit_addresses, deposit_confirmations, price_snapshots, round_venues, "
            + "feedback, registration_ips, ledger_entries, engine_snapshots, player_sidecar RESTART IDENTITY CASCADE";

    /** V7's {@code markets} table: every market a test listed goes, but BTC's row (id 0, created by the
     *  migration, always present) stays — back to ACTIVE in case a test closed it. */
    private static final String DELETE_LISTED_MARKETS_SQL = "DELETE FROM markets WHERE id <> 'BTC'";
    private static final String REOPEN_BTC_SQL = "UPDATE markets SET status = 'ACTIVE', closed_at_ms = NULL, "
            + "close_reason = NULL WHERE id = 'BTC'";

    static {
        // the reset truncates the tables and rebuilds the engine inside GameService.runExclusively, so that no writer turn runs meanwhile
        crowdvsmarket.server.core.IoGuard.allow("CleanDatabasePerTestClass.resetDatabaseAndMemory",
                "test fixture: database reset under the exclusive engine lock");
    }

    @Override
    public void beforeEach(QuarkusTestMethodContext context) {
        Class<?> testClass = context.getTestInstance().getClass();
        if (!RESET_DONE.add(testClass)) {
            return; // already reset for this class earlier in the run — leave this method's own
            // in-class fixtures (e.g. another @Test's players/bets in the SAME class) alone.
        }
        resetDatabaseAndMemory();
    }

    /**
     * The database emptied and the engine rebuilt from it. The truncation and the rebuild of memory are
     * ONE step under the engine lock. Done one after the other they left a gap in which the live
     * scheduler's bots — the application starts on whatever the previous class left in a shared
     * database, bots included, before this hook can run — could still place a bet for a player the cache
     * knew: the bet's event landed in the freshly truncated game_events and survived the rebuild
     * (BetFeedCapsTest saw "3 expected, 4 found", the fourth a bot's; docs/reviews/2026-10-01-goals-vs-
     * state.md §3.8). Under the lock such a bet either completes before the truncation (and is wiped
     * with it) or finds the players reloaded (and is refused: unknown player).
     */
    public static void resetDatabaseAndMemory() {
        Db db = Arc.container().instance(Db.class).get();
        GameService gameService = Arc.container().instance(GameService.class).get();
        EngineRebuildService rebuildService = Arc.container().instance(EngineRebuildService.class).get();
        // the windows it had failed to settle are gone with the database: nothing may hold a new class's windows back
        Arc.container().instance(RoundSchedulerService.class).get().forgetFailedAttempts();
        // a fresh deployment also starts with full rate-limit buckets: a class that signs in many players must not inherit the budget of
        // 127.0.0.1 that the classes before it spent (429 on a sign-in)
        Arc.container().instance(crowdvsmarket.server.api.RateLimiter.class).get().clear();
        gameService.runExclusively(() -> {
            db.tx(c -> {
                db.update(c, TRUNCATE_SQL);
                db.update(c, DELETE_LISTED_MARKETS_SQL);
                db.update(c, REOPEN_BTC_SQL);
                return null;
            });
            // D-107: the journal is the truth and game_events its copy: emptied together
            Arc.container().instance(crowdvsmarket.server.journal.Journal.class).get().resetForTests();
            // Same code path a real restart uses: resets every in-memory singleton (EngineHub with its
            // per-market ComparisonAccounts, BetFeed, RoundProgress, PlayerDirectory, the market
            // registry) from the now-empty tables — necessary even when Quarkus itself did restart for
            // this class (that restart's own StartupEvent-triggered rebuild ran against whatever the
            // PREVIOUS class had left in the database, before this truncate), and doubly necessary when
            // it did not.
            rebuildService.rebuildStateLocked();
        });
        // the sessions table was truncated: a cached session of an earlier class must not outlive it
        Arc.container().instance(crowdvsmarket.server.auth.SessionService.class).get().clearCache();
        // ... and BotService#ensureBotsExist then creates exactly this class's own configured bot count
        // (outside the engine lock, like EngineRebuildService#rebuild: it takes the BotService monitor).
        // economy stage B: a profile with herd.economy.treasury-opening-play > 0 starts its (emptied) game with the cutover, as a first start does
        gameService.ensureEconomyCutover(System.currentTimeMillis());
        Arc.container().instance(BotService.class).get().ensureBotsExist(System.currentTimeMillis());
    }
}
