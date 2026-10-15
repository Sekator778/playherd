package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import crowdvsmarket.engine.ledger.PlayAmount;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Direction;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Paging;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.events.GameEvent;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.persistence.OracleRecordRepository;
import crowdvsmarket.server.persistence.RoundResultRepository;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A recovery that fails stops the game instead of leaving it on memory that cannot be trusted
 * (docs/research/markets_architecture.md §4 п.3; review of the multi-market core, finding 2).
 *
 * <p>A settlement transaction that fails AFTER its event was applied to the engine leaves memory ahead
 * of the database, so the engine is rebuilt from the event log. Before, if that rebuild failed too,
 * the process only logged it and went on serving — from the settlement the database never saw, or,
 * when the rebuild had already reset the hub, from an EMPTY ledger: a player who then bet was given a
 * second starter pack (9 900 PLAY made out of nothing), and the event of that bet could not be
 * replayed at the next restart. Now the rebuild is built aside and swapped in only when complete, the
 * engine is "not ready" from the failure until a rebuild has succeeded (every call that changes
 * anything and every read that comes from the engine answers 503 {@code ENGINE_NOT_READY}; what comes
 * from the database or needs no engine is answered as usual), and the scheduler keeps rebuilding.
 *
 * <p>Failures are injected into the four stages a rebuild has: reading the player table, reading the
 * event log, replaying it, seeding the series. Each of them is tried both ways — the recovery of a
 * settlement that failed, and a plain rebuild that fails (which must leave the state in use alone).
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class FailStopRecoveryTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;

    @InjectSpy
    OracleRecordRepository oracleRecords;
    @InjectSpy
    EventStore eventStoreSpy;
    @InjectSpy
    RoundResultRepository roundResultsSpy;
    @InjectSpy
    PlayerDirectory playersSpy;

    @AfterEach
    void healTheEngine() {
        Mockito.reset(oracleRecords, eventStoreSpy, roundResultsSpy, playersSpy);
        rebuild.rebuild(); // whatever a test left half-way: the next one starts on a ready engine
    }

    private record Fixture(Market market, RoundWindow running, RoundWindow betting, String player) {
    }

    private record Snapshot(String balance, String inPlay, int pendingBets, int crowdHistory, String crowdCapital,
                            int feedEntries, int knownPlayers) {
    }

    /** A 25x market, its running window already settled (empty), a 300 PLAY bet DOWN 10x on the window
     *  that ends next, and that window's price scripted as -4% — everything a settlement needs. */
    private Fixture settlementDue(String id, String label) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, label.toUpperCase().substring(0, 3), 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        RoundWindow running = roundProgress.next(market.id(), HOURLY);
        RoundWindow betting = roundProgress.bettingWindow(market.id(), HOURLY);
        String player = signUp(label);
        gameService.placeBet(player, bet(betting, Enums.Direction.DOWN, 10, "300.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());
        prices.script(market, betting, "2500.000", "2400.000", "2400.000", "2510.000");
        return new Fixture(market, running, betting, player);
    }

    private Snapshot snapshot(Fixture f) {
        var engines = hub.market(f.market().id());
        return new Snapshot(gameService.account(f.player()).balance(), gameService.account(f.player()).inPlay(),
                engines.engine(HOURLY).betBook().betsFor(f.betting().roundId()).size(),
                engines.crowd().history().size(), engines.crowd().capital().toPlainString(),
                gameService.betFeed(f.betting().roundId(), null).entries().size(),
                hub.ledger().playerIds().size());
    }

    private long count(String sql, Object... params) {
        return db.read(c -> db.queryOne(c, sql, rs -> rs.getLong("n"), params));
    }

    private long resolvedEvents(Fixture f) {
        return count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'RoundResolved' "
                + "AND payload->>'market' = ? AND (payload->>'windowStart')::bigint = ?", f.market().id(),
                f.betting().startMs());
    }

    private long oracleSeq() {
        return Long.parseLong(db.read(c -> db.queryOne(c, "SELECT value FROM server_meta WHERE key = 'oracle_seq'",
                rs -> rs.getString("value"))));
    }

    private static Response rpc(String method, Object... args) {
        return given().contentType(ContentType.JSON).body(Map.of("args", Arrays.asList(args)))
                .post("/api/rpc/" + method);
    }

    private static void assertNotReady(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ENGINE_NOT_READY);
            assertThat(e.httpStatus()).isEqualTo(503);
        });
    }

    /** The settlement fails after its event was applied to the engine (the oracle insert throws). */
    private void failTheSettlementAfterItsEventWasApplied() throws Exception {
        doThrow(new IllegalStateException("injected failure after the event append")).when(oracleRecords)
                .insert(any(Connection.class), any(OracleRecordRepository.NewRecord.class));
    }

    /** What the game answers while the engine cannot be trusted: 503 for everything that changes
     *  anything and for every read that comes from the engine, the usual answer for the rest. */
    private void assertTheGameAnswers503OnlyWhereItMust(Fixture f) {
        long now = System.currentTimeMillis();
        String roundId = f.betting().roundId();
        // --- everything that changes anything: refused, and nothing was changed ---
        assertNotReady(() -> gameService.placeBet(f.player(), bet(f.betting(), Enums.Direction.UP, 2, "100.000"),
                f.betting().startMs() - 1));
        assertNotReady(() -> gameService.cancelBet(f.player(), roundId, f.betting().startMs() - 1));
        assertNotReady(() -> gameService.registerOrTouchPlayer("txch1down" + System.nanoTime(), now));
        assertNotReady(() -> gameService.createBotPlayer("Bot Down", "RANDOM", now));
        assertNotReady(() -> gameService.setNickname(f.player(), "Still Down"));
        assertNotReady(() -> gameService.resolveRound(f.betting(), f.betting().endMs()));
        assertNotReady(() -> gameService.cancelRoundById(roundId, "operator", now));
        assertNotReady(() -> gameService.reverseWithdrawal("w-1", "operator", now));
        assertNotReady(() -> gameService.reverseDeposit("d-1", "operator", now));
        assertNotReady(() -> gameService.requestWithdrawal(f.player(), "100.000", now));
        assertNotReady(() -> gameService.confirmWithdrawalOnChain("w-1", "coin", 1L, now));
        assertNotReady(() -> gameService.failWithdrawalOnChain("w-1", "reason", now));
        long reservedCoinsBefore = count("SELECT COUNT(*) AS n FROM deposit_confirmations");
        assertNotReady(() -> gameService.creditOnChainDeposit(f.player(), 1, PlayAmount.ofWholePlay(100),
                "coin-while-down", 1L, now));
        assertThat(count("SELECT COUNT(*) AS n FROM deposit_confirmations"))
                .as("a deposit coin is not reserved by a call that is refused: the poller offers it again")
                .isEqualTo(reservedCoinsBefore);
        // --- every read that comes from the engine ---
        assertNotReady(() -> gameService.account(f.player()));
        assertNotReady(() -> gameService.seasonRecord(f.player(), null));
        assertNotReady(() -> gameService.myLedger(f.player(), new Paging.PageParams(null, null)));
        assertNotReady(() -> gameService.ledgerCorrections(new Paging.PageParams(null, null)));
        assertNotReady(() -> gameService.crowdAccount(f.market().id()));
        assertNotReady(() -> gameService.ratings(null));
        assertNotReady(() -> gameService.home(null));
        assertNotReady(() -> gameService.roundsPair(Enums.Interval.HOURLY, f.market().id(), null));
        assertNotReady(() -> gameService.round(roundId, null));
        assertNotReady(() -> gameService.betFeed(roundId, null));
        assertNotReady(() -> gameService.publicProfile(f.player()));
        // --- over HTTP: the 503 carries its code in the usual envelope; a read that needs no engine is a 200 ---
        Response rounds = rpc("rounds", "HOURLY", f.market().id());
        assertThat(rounds.statusCode()).as(rounds.asString()).isEqualTo(503);
        assertThat(rounds.jsonPath().getString("error.code")).isEqualTo("ENGINE_NOT_READY");
        assertThat(rpc("home").statusCode()).isEqualTo(503);
        Response reference = rpc("referencePrice", f.market().id());
        assertThat(reference.statusCode()).as(reference.asString()).isEqualTo(200);
        assertThat(reference.jsonPath().getString("price")).isEqualTo("2500.000");
        // --- what comes from the database or needs no engine is answered as usual ---
        assertThat(gameService.round(f.running().roundId(), null)).isInstanceOf(Rounds.RoundSettled.class);
        assertThat(gameService.cancellations(new Paging.PageParams(null, null)).items()).isNotNull();
        assertThat(gameService.myUnseenResults(f.player())).isNotNull();
        assertThat(gameService.referencePrice(f.market().id()).price()).isEqualTo("2500.000");
        assertThat(gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(f.market().id(),
                Enums.Direction.UP, 10, "100.000", "2500.000")).liquidationPrice()).isNotBlank();
        assertThat(gameService.summary(f.player()).playerId()).isEqualTo(f.player());
        assertThat(gameService.runningMarkets()).extracting(Market::id).contains("BTC", f.market().id());
    }

    /**
     * The whole story, for one way the rebuild can fail: a settlement fails after its event was
     * applied; the recovery fails too ({@code injectRebuildFailure}); nothing is answered from memory
     * and the state in use is not touched; the scheduler's tick keeps trying while the failure lasts;
     * once it is gone one tick rebuilds, resumes, settles the round exactly once, and what is in memory
     * is what a fresh rebuild gives.
     */
    private void aFailedRecoveryStopsTheGameUntilARebuildSucceeds(String marketId, Runnable injectRebuildFailure,
                                                                  String failure) throws Exception {
        Fixture f = settlementDue(marketId, "failstop");
        Snapshot before = snapshot(f);
        long seqBefore = oracleSeq();
        assertThat(before.pendingBets()).isEqualTo(1);
        assertThat(before.balance()).isEqualTo("9699.600"); // 300 staked + herd fee 300 * 10 bp = 0.3 + network fee 0.1
        failTheSettlementAfterItsEventWasApplied();
        injectRebuildFailure.run();

        assertThatThrownBy(() -> gameService.resolveRound(f.betting(), f.betting().endMs()))
                .hasMessageContaining("injected failure after the event append")
                .satisfies(e -> assertThat(e.getSuppressed())
                        .as("the failed rebuild is attached to the failure it was recovering from")
                        .anySatisfy(s -> assertThat(s).hasStackTraceContaining(failure)));

        // --- not ready: 503 where it must be, the usual answer elsewhere ---
        assertThat(rebuild.isReady()).isFalse();
        assertTheGameAnswers503OnlyWhereItMust(f);
        // the state in use was not touched, in particular never half-built: the ledger still knows the
        // player (an emptied one would hand a second starter pack to the next bet)
        assertThat(hub.ledger().playerIds()).contains(f.player());
        assertThat(hub.ledger().balance(f.player()).toMoney().toPlainString())
                .as("memory is where it was left: ahead of the database, which is why nothing is served from it")
                .isEqualTo("10119.600");

        // --- the scheduler keeps trying while the failure lasts, and does nothing else ---
        scheduler.tick(f.betting().endMs());
        scheduler.tick(f.betting().endMs());
        assertThat(rebuild.isReady()).isFalse();
        assertThat(roundResults.exists(f.betting().roundId())).isFalse();
        assertThat(resolvedEvents(f)).isZero();
        assertThat(count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'PlayerRegistered'"))
                .as("no player was registered while the engine was down").isEqualTo(
                        count("SELECT COUNT(*) AS n FROM players"));

        // --- the failure is gone: one tick rebuilds, resumes and settles the round, once ---
        Mockito.reset(oracleRecords, eventStoreSpy, roundResultsSpy, playersSpy);
        scheduler.tick(f.betting().endMs());

        assertThat(rebuild.isReady()).isTrue();
        assertThat(resolvedEvents(f)).isEqualTo(1);
        assertThat(roundResults.exists(f.betting().roundId())).isTrue();
        assertThat(count("SELECT COUNT(*) AS n FROM oracle_records WHERE round_id = ?", f.betting().roundId()))
                .isEqualTo(1);
        // (the tick settled whatever else was due as well: the daily window of the market, say)
        long maxSeq = count("SELECT COALESCE(MAX(seq), 0) AS n FROM oracle_records");
        assertThat(oracleSeq()).as("the failed settlement burned no seq").isEqualTo(maxSeq);
        assertThat(count("SELECT COUNT(*) AS n FROM oracle_records WHERE seq > ?", seqBefore))
                .as("and left no hole in the sequence").isEqualTo(maxSeq - seqBefore);
        assertThat(gameService.account(f.player()).balance()).as("paid out once: 9699.6 + 420").isEqualTo("10119.600");
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("0.000");
        assertThat(roundProgress.next(f.market().id(), HOURLY).startMs()).isGreaterThanOrEqualTo(f.betting().endMs());
        // ... and what is in memory now is what a restart would give
        Snapshot recovered = snapshot(f);
        rebuild.rebuild();
        assertThat(snapshot(f)).isEqualTo(recovered);
        assertThat(recovered.knownPlayers()).isEqualTo(before.knownPlayers());
    }

    @Test
    void aRecoveryThatFailsReadingThePlayersStopsTheGameUntilARebuildSucceeds() throws Exception {
        aFailedRecoveryStopsTheGameUntilARebuildSucceeds("T_FS_PLAYERS",
                () -> doThrow(new IllegalStateException("db down while reading the players")).when(playersSpy).reload(),
                "db down while reading the players");
        verify(playersSpy, atLeast(1)).reload();
    }

    @Test
    void aRecoveryThatFailsReadingTheEventLogStopsTheGameUntilARebuildSucceeds() throws Exception {
        aFailedRecoveryStopsTheGameUntilARebuildSucceeds("T_FS_LOG",
                () -> doThrow(new IllegalStateException("db down while reading the log")).when(eventStoreSpy)
                        .loadAll(),
                "db down while reading the log");
        verify(eventStoreSpy, atLeast(2)).loadAll(); // the recovery itself, then the scheduler's retries
    }

    @Test
    void aRecoveryThatFailsInTheMiddleOfTheReplayStopsTheGameAndLeavesNoHalfBuiltHubBehind() throws Exception {
        aFailedRecoveryStopsTheGameUntilARebuildSucceeds("T_FS_REPLAY", () -> doAnswer(inv -> {
            // a log whose last event cannot be applied: the replay has applied every real event by then
            @SuppressWarnings("unchecked")
            List<GameEvent> real = (List<GameEvent>) inv.callRealMethod();
            List<GameEvent> events = new ArrayList<>(real);
            events.add(new GameEvent.BetPlaced("nobody", Interval.HOURLY, 1_000L, 61_000L, Direction.UP, 2, 100_000L,
                    1_000L, "GHOSTMARKET"));
            return events;
        }).when(eventStoreSpy).loadAll(), "GHOSTMARKET");
    }

    @Test
    void aRecoveryThatFailsSeedingTheSeriesAfterTheWholeLogWasReplayedStopsTheGame() throws Exception {
        aFailedRecoveryStopsTheGameUntilARebuildSucceeds("T_FS_SEED",
                () -> doThrow(new IllegalStateException("db flapping while seeding")).when(roundResultsSpy)
                        .latestWindowEnd(anyString(), any(Interval.class)),
                "db flapping while seeding");
    }

    @Test
    void aPlainRebuildThatFailsLeavesTheStateInUseAloneAndTheGameServing() {
        Fixture f = settlementDue("T_PLAINFAIL", "plainfail");
        Snapshot before = snapshot(f);
        long cursorsBefore = roundProgress.seededSeries().size();
        for (Runnable inject : List.<Runnable>of(
                () -> doThrow(new IllegalStateException("db down: players")).when(playersSpy).reload(),
                () -> doThrow(new IllegalStateException("db down: log")).when(eventStoreSpy).loadAll(),
                () -> doThrow(new IllegalStateException("db down: seeding")).when(roundResultsSpy)
                        .latestWindowEnd(anyString(), any(Interval.class)))) {
            inject.run();

            assertThatThrownBy(() -> rebuild.rebuild()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("db down");

            // nothing was reset, nothing is gated: the state in use is exactly what it was
            assertThat(rebuild.isReady()).isTrue();
            assertThat(snapshot(f)).isEqualTo(before);
            assertThat(roundProgress.seededSeries()).hasSize((int) cursorsBefore);
            assertThat(roundProgress.next(f.market().id(), HOURLY)).isEqualTo(f.betting());
            Mockito.reset(eventStoreSpy, roundResultsSpy, playersSpy);
        }
        // and it still takes bets: on the window open for betting now (the one the fixture bet on is running)
        RoundWindow open = roundProgress.bettingWindow(f.market().id(), HOURLY);
        gameService.placeBet(f.player(), bet(open, Enums.Direction.UP, 2, "100.000"), open.startMs() - 1);
        assertThat(gameService.account(f.player()).inPlay()).isEqualTo("400.000");
    }

    @Test
    void aMarketListedOrClosedWhileTheEngineIsDownDoesNotThrowAndIsPickedUpByTheRecovery() throws Exception {
        Fixture f = settlementDue("T_DOWNFIX", "downfix");
        // a second market with a bet of its own, to be closed while the engine is down
        Market doomed = list("T_DOOMED", MarketKind.PERP, Category.CRYPTO, "DMD", 10, 0);
        prices.setPrice(doomed, "50.00");
        alignTo(doomed, HOURLY, f.running());
        RoundWindow doomedBetting = roundProgress.bettingWindow(doomed.id(), HOURLY);
        String bettor = signUp("doomed");
        gameService.placeBet(bettor, bet(doomedBetting, Enums.Direction.UP, 5, "400.000"),
                doomedBetting.startMs() - 1);
        failTheSettlementAfterItsEventWasApplied();
        doThrow(new IllegalStateException("db down while reading the log")).when(eventStoreSpy).loadAll();
        assertThatThrownBy(() -> gameService.resolveRound(f.betting(), f.betting().endMs()))
                .hasMessageContaining("injected failure after the event append");
        assertThat(rebuild.isReady()).isFalse();

        // the registry commits both and tells a listener that must not throw out of list()/close()
        AtomicReference<Market> late = new AtomicReference<>();
        assertThatCode(() -> late.set(list("T_LATEMARKET", MarketKind.PERP, Category.CRYPTO, "LTM", 10, 0)))
                .doesNotThrowAnyException();
        assertThatCode(() -> registry.close(doomed.id(), "closed while down", System.currentTimeMillis()))
                .doesNotThrowAnyException();
        assertThat(registry.require("T_LATEMARKET").isActive()).isTrue();
        assertThat(registry.require(doomed.id()).isActive()).isFalse();
        assertThat(hub.findMarket("T_LATEMARKET")).as("no engines are created on a state that cannot be trusted")
                .isEmpty();
        assertThat(roundProgress.isSeeded("T_LATEMARKET", HOURLY)).isFalse();

        Mockito.reset(oracleRecords, eventStoreSpy);
        prices.setPrice(late.get(), "10.00");
        scheduler.tick(f.betting().endMs());

        assertThat(rebuild.isReady()).isTrue();
        // the recovery gave the new market its engines and its series ...
        assertThat(hub.findMarket("T_LATEMARKET")).isPresent();
        assertThat(roundProgress.isSeeded("T_LATEMARKET", HOURLY)).isTrue();
        assertThat(roundProgress.isSeeded("T_LATEMARKET", Interval.DAILY)).isTrue();
        // ... and refunded the open round of the market that closed
        Rounds.RoundDto cancelled = roundResults.findRoundDto(doomedBetting.roundId()).orElseThrow();
        assertThat(cancelled).isInstanceOf(Rounds.RoundCancelled.class);
        assertThat(((Rounds.RoundCancelled) cancelled).cancellation().reason()).isEqualTo("market closed: closed while down");
        assertThat(gameService.account(bettor).balance()).isEqualTo("10000.000");
        assertThat(roundProgress.isSeeded(doomed.id(), HOURLY)).isFalse();
        // and the settlement that had failed went through
        assertThat(roundResults.exists(f.betting().roundId())).isTrue();
        assertThat(gameService.account(f.player()).balance()).isEqualTo("10119.600");
    }
}
