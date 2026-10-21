package crowdvsmarket.server.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.Bet;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.EngineLockProbe;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The core writer under the real game and a real Postgres (architecture plan F6): many threads at once, one batch that
 * fails to commit, the order of a player's bets, and sign-ins. What each proves: every bet answered "OK" is in the journal
 * exactly once and every bet answered 503 is not; balances stay conserved; the engine rebuilt from the journal is the engine
 * that was served.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class CoreWriterGameTest extends LiveGameTestBase {

    private static final Interval HOURLY = Interval.HOURLY;

    @InjectSpy
    EventStore eventStoreSpy;

    @AfterEach
    void healTheEngine() {
        Mockito.reset(eventStoreSpy);
        rebuild.rebuild();
    }

    private record Fixture(Market market, RoundWindow betting) {
    }

    private Fixture fixture(String id, String symbol) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, symbol, 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        return new Fixture(market, roundProgress.bettingWindow(market.id(), HOURLY));
    }

    private long count(String sql, Object... params) {
        return db.read(c -> db.queryOne(c, sql, rs -> rs.getLong("n"), params));
    }

    private long betEvents(Fixture f) {
        return count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'BetPlaced' AND payload->>'market' = ? "
                + "AND (payload->>'windowStart')::bigint = ?", f.market().id(), f.betting().startMs());
    }

    private long betEventsOf(Fixture f, String player) {
        return count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'BetPlaced' AND payload->>'market' = ? "
                + "AND (payload->>'windowStart')::bigint = ? AND payload->>'playerId' = ?", f.market().id(),
                f.betting().startMs(), player);
    }

    private int betsInTheEngine(Fixture f) {
        return hub.market(f.market().id()).engine(HOURLY).betBook().count(f.betting().roundId());
    }

    private Map<String, String> balances(List<String> players) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String p : players) {
            var account = gameService.account(p);
            out.put(p, account.balance() + "/" + account.inPlay());
        }
        return out;
    }

    private List<String> signUp(String prefix, int n) {
        List<String> players = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            players.add(signUp(prefix + i));
        }
        return players;
    }

    /** The call's outcome: {@code null} when it was answered OK, else what it threw. */
    private Throwable placeBet(Fixture f, String player, Enums.Direction direction, int leverage, String stake) {
        try {
            gameService.placeBet(player, bet(f.betting(), direction, leverage, stake), f.betting().startMs() - 1);
            return null;
        } catch (Throwable e) {
            return e;
        }
    }

    // ------------------------------------------------------------------------------------------

    @Test
    void manyThreadsPlacingBetsEveryAnsweredBetIsInTheJournalOnceAndBalancesAreConserved() throws Exception {
        Fixture f = fixture("T_CW_MANY", "CWM");
        int n = 48;
        List<String> players = signUp("many", n);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                String player = players.get(i);
                Enums.Direction direction = i % 2 == 0 ? Enums.Direction.UP : Enums.Direction.DOWN;
                calls.add(pool.submit(() -> {
                    start.await();
                    return placeBet(f, player, direction, 5, "100.000");
                }));
            }
            start.countDown();
            for (Future<Throwable> call : calls) {
                assertThat(call.get(60, TimeUnit.SECONDS)).as("every bet was accepted").isNull();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(betEvents(f)).as("one journal row per accepted bet").isEqualTo(n);
        for (String player : players) {
            assertThat(betEventsOf(f, player)).as("exactly one row for " + player).isEqualTo(1);
        }
        assertThat(betsInTheEngine(f)).isEqualTo(n);

        BigDecimal total = BigDecimal.ZERO;
        for (String player : players) {
            var account = gameService.account(player);
            total = total.add(new BigDecimal(account.balance())).add(new BigDecimal(account.inPlay()))
                    .add(hub.ledger().feesPaid(player).toMoney());
        }
        assertThat(total).as("what the players hold, have in play and have paid in fees is what they were given")
                .isEqualByComparingTo(BigDecimal.valueOf(10_000L * n));

        Map<String, String> served = balances(players);
        rebuild.rebuild();
        assertThat(balances(players)).as("the engine rebuilt from the journal is the one that was served").isEqualTo(served);
        assertThat(betsInTheEngine(f)).isEqualTo(n);
    }

    @Test
    void aBatchThatFailsToCommitAnswers503ToItsBetsWritesNoneOfThemAndTheNextBatchWorks() throws Exception {
        Fixture f = fixture("T_CW_FAIL", "CWF");
        int n = 6;
        List<String> players = signUp("fail", n);
        ExecutorService pool = Executors.newFixedThreadPool(n + 1);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<EngineLockProbe.Probe> probes = new ArrayList<>();
        List<Future<Throwable>> calls = new ArrayList<>();
        try {
            // the writer cannot begin a batch while this thread holds the gate: the bets queue up behind it
            Future<?> holder = pool.submit(() -> gameService.runExclusively(() -> {
                holding.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(holding.await(30, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < n; i++) {
                String player = players.get(i);
                EngineLockProbe.Probe probe = new EngineLockProbe.Probe();
                probes.add(probe);
                calls.add(pool.submit(() -> {
                    probe.arrive();
                    return placeBet(f, player, Enums.Direction.UP, 5, "100.000");
                }));
                EngineLockProbe.awaitQueued(probe); // one after the other: the writer's order is the order of the players
            }
            // a batch of more than one command cannot be written; a lone command can
            doAnswer(inv -> {
                List<?> events = inv.getArgument(0);
                if (events.size() >= 2) {
                    throw new IllegalStateException("injected commit failure");
                }
                return inv.callRealMethod();
            }).when(eventStoreSpy).appendAll(anyList());
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            List<String> answeredOk = new ArrayList<>();
            List<String> answered503 = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Throwable outcome = calls.get(i).get(60, TimeUnit.SECONDS);
                if (outcome == null) {
                    answeredOk.add(players.get(i));
                } else {
                    assertThat(outcome).isInstanceOfSatisfying(ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.ENGINE_NOT_READY);
                        assertThat(e.httpStatus()).isEqualTo(503);
                    });
                    answered503.add(players.get(i));
                }
            }
            assertThat(answered503).as("the batch of the queued bets failed").isNotEmpty();
            for (String player : answeredOk) {
                assertThat(betEventsOf(f, player)).as("an OK is a bet in the journal: " + player).isEqualTo(1);
            }
            for (String player : answered503) {
                assertThat(betEventsOf(f, player)).as("a 503 is no bet in the journal: " + player).isZero();
                assertThat(gameService.account(player).balance()).as("and no money moved").isEqualTo("10000.000");
            }
            assertThat(betEvents(f)).isEqualTo(answeredOk.size());
            assertThat(rebuild.isReady()).as("the engine was rebuilt from the journal and the game goes on").isTrue();
            assertThat(betsInTheEngine(f)).as("the rebuilt engine has the bets of the journal and no others")
                    .isEqualTo(answeredOk.size());

            // the database answers again: the next batch works, for a player whose bet was refused as well
            Mockito.reset(eventStoreSpy);
            String retrying = answered503.get(0);
            assertThat(placeBet(f, retrying, Enums.Direction.DOWN, 5, "100.000")).isNull();
            assertThat(betEventsOf(f, retrying)).isEqualTo(1);

            Map<String, String> served = balances(players);
            rebuild.rebuild();
            assertThat(balances(players)).as("served state equals the journal's").isEqualTo(served);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void aPlayersBetsAreAppliedInTheOrderTheyWereAcceptedAndTheJournalIsInThatOrder() throws Exception {
        Fixture f = fixture("T_CW_ORDER", "CWO");
        String player = signUp("order");
        int n = 30;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> calls = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                String stake = String.format("%d.000", 100 + i); // a different stake each: the last accepted is the one that stands
                calls.add(pool.submit(() -> {
                    start.await();
                    return placeBet(f, player, Enums.Direction.UP, 5, stake);
                }));
            }
            start.countDown();
            for (Future<Throwable> call : calls) {
                assertThat(call.get(60, TimeUnit.SECONDS)).isNull();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(betEventsOf(f, player)).as("every replacement is an event").isEqualTo(n);
        long lastInJournal = db.read(c -> db.queryOne(c, "SELECT (payload->>'stakeMojos')::bigint AS stake FROM game_events "
                + "WHERE event_type = 'BetPlaced' AND payload->>'market' = ? AND (payload->>'windowStart')::bigint = ? "
                + "AND payload->>'playerId' = ? ORDER BY id DESC LIMIT 1", rs -> rs.getLong("stake"), f.market().id(),
                f.betting().startMs(), player));
        Bet standing = hub.market(f.market().id()).engine(HOURLY).betBook().find(f.betting().roundId(), player).orElseThrow();
        assertThat(standing.stake().mojos()).as("the bet that stands is the last one in the journal").isEqualTo(lastInJournal);

        Map<String, String> served = balances(List.of(player));
        rebuild.rebuild();
        assertThat(hub.market(f.market().id()).engine(HOURLY).betBook().find(f.betting().roundId(), player).orElseThrow()
                .stake().mojos()).as("a replay of the journal ends on the same bet").isEqualTo(lastInJournal);
        assertThat(balances(List.of(player))).isEqualTo(served);
    }

    @Test
    void simultaneousSignInsRegisterEachWalletOnceWithOneEvent() throws Exception {
        int wallets = 24;
        int perWallet = 4;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<String> addresses = new ArrayList<>();
        List<Future<String>> calls = new ArrayList<>();
        try {
            for (int w = 0; w < wallets; w++) {
                String address = "txch1cw" + UUID.randomUUID().toString().replace("-", "");
                addresses.add(address);
                for (int k = 0; k < perWallet; k++) {
                    calls.add(pool.submit(() -> {
                        start.await();
                        return gameService.registerOrTouchPlayer(address, System.currentTimeMillis()).player().playerId();
                    }));
                }
            }
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (Future<String> call : calls) {
                ids.add(call.get(60, TimeUnit.SECONDS));
            }
            for (int w = 0; w < wallets; w++) {
                List<String> mine = ids.subList(w * perWallet, (w + 1) * perWallet);
                assertThat(mine.stream().distinct().count()).as("one player per wallet").isEqualTo(1);
                String id = mine.get(0);
                assertThat(count("SELECT COUNT(*) AS n FROM players WHERE wallet_address = ?", addresses.get(w))).isEqualTo(1);
                assertThat(count("SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'PlayerRegistered' "
                        + "AND payload->>'playerId' = ?", id)).as("and one event").isEqualTo(1);
                assertThat(gameService.account(id).balance()).isEqualTo("10000.000");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
