package crowdvsmarket.server.testsupport;

import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.scheduler.EngineRebuildService;
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
            + "oracle_records, player_deposit_addresses, deposit_confirmations RESTART IDENTITY CASCADE";

    @Override
    public void beforeEach(QuarkusTestMethodContext context) {
        Class<?> testClass = context.getTestInstance().getClass();
        if (!RESET_DONE.add(testClass)) {
            return; // already reset for this class earlier in the run — leave this method's own
            // in-class fixtures (e.g. another @Test's players/bets in the SAME class) alone.
        }
        Db db = Arc.container().instance(Db.class).get();
        db.tx(c -> {
            db.update(c, TRUNCATE_SQL);
            return null;
        });
        // Same code path a real restart uses: resets every in-memory singleton (EngineHub,
        // ComparisonAccounts, BetFeed, RoundProgress, PlayerDirectory) from the now-empty tables,
        // then BotService#ensureBotsExist creates exactly this class's own configured bot count —
        // necessary even when Quarkus itself did restart for this class (that restart's own
        // StartupEvent-triggered rebuild ran against whatever the PREVIOUS class had left in the
        // database, before this truncate), and doubly necessary when it did not.
        Arc.container().instance(EngineRebuildService.class).get().rebuild();
    }
}
