package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.BetFeed;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.snapshot.StateCodec;
import crowdvsmarket.server.testsupport.LiveRpcTestBase;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.restassured.response.Response;
import java.util.Map;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code clientRequestId} on {@code placeBet} (architecture plan, lmax_roadmap step 1): a repeat of a request with the same id from the
 * same player places no second bet and gets the first answer - also when the repeats race, and also after the engine was rebuilt from
 * the journal; another player may use the same id; the same id for another bet is refused; a bet without an id is as it always was.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class BetClientRequestIdTest extends LiveRpcTestBase {

    private static final Interval HOURLY = Interval.HOURLY;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private Market market;
    private RoundWindow betting;

    @BeforeEach
    void aMarketWithItsBettingRoundOpen() {
        market = list("T_RQ" + Long.toString(System.nanoTime(), 36).toUpperCase(), MarketKind.PERP, Category.CRYPTO, "RQI", 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, HOURLY, roundProgress.next("BTC", HOURLY));
        betting = roundProgress.bettingWindow(market.id(), HOURLY);
    }

    @AfterEach
    void heal() {
        pool.shutdownNow();
        rebuild.rebuild();
    }

    private Betting.PlaceBetRequest request(String id, Enums.Direction direction, int leverage, String stake) {
        return new Betting.PlaceBetRequest(betting.roundId(), direction, leverage, stake, null, id);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private long betEvents(String playerId) {
        return db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'BetPlaced' "
                + "AND payload->>'playerId' = ?", rs -> rs.getLong("n"), playerId));
    }

    private long betEventsWithId(String playerId, String id) {
        return db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'BetPlaced' "
                + "AND payload->>'playerId' = ? AND payload->>'clientRequestId' = ?", rs -> rs.getLong("n"), playerId, id));
    }

    @Test
    void aRepeatedRequestPlacesNoSecondBetAndGetsTheFirstAnswer() {
        String player = signUp("dup");
        String id = newId();
        long at = betting.startMs() - 1_000;

        Rounds.BetDto first = gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "300.000"), at);
        String balanceAfterFirst = gameService.account(player).balance();
        Rounds.BetDto second = gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "300.000"), at + 5);

        assertThat(second).isEqualTo(first);
        assertThat(betEvents(player)).isEqualTo(1);
        assertThat(betEventsWithId(player, id)).isEqualTo(1);
        assertThat(gameService.account(player).balance()).isEqualTo(balanceAfterFirst);
        assertThat(gameService.betFeed(betting.roundId(), null).entries()).hasSize(1);
    }

    @Test
    void theRepeatIsTheFirstAnswerEvenWhenAnotherBetCameInBetween() {
        String player = signUp("between");
        String id = newId();
        long at = betting.startMs() - 1_000;
        Rounds.BetDto first = gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "300.000"), at);
        gameService.placeBet(player, bet(betting, Enums.Direction.DOWN, 3, "500.000"), at + 1); // a new press, no id: replaces the bet

        Rounds.BetDto repeat = gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "300.000"), at + 2);

        assertThat(repeat).isEqualTo(first);
        assertThat(betEvents(player)).as("the repeat wrote nothing").isEqualTo(2);
        assertThat(gameService.account(player).inPlay()).as("the bet that stands is the later one").isEqualTo("500.000");
    }

    @Test
    void aRepeatIsAnsweredEvenWhenTheRoundIsNoLongerOpen() {
        String player = signUp("late");
        String id = newId();
        Rounds.BetDto first = gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "300.000"), betting.startMs() - 1_000);

        Rounds.BetDto repeat = gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "300.000"), betting.startMs() + 50);

        assertThat(repeat).isEqualTo(first);
    }

    @Test
    void anotherPlayerMayUseTheSameId() {
        String alice = signUp("alice");
        String bob = signUp("bob");
        String id = newId();
        long at = betting.startMs() - 1_000;

        gameService.placeBet(alice, request(id, Enums.Direction.UP, 5, "300.000"), at);
        Rounds.BetDto bobs = gameService.placeBet(bob, request(id, Enums.Direction.DOWN, 7, "400.000"), at + 1);

        assertThat(bobs.playerId()).isEqualTo(bob);
        assertThat(bobs.direction()).isEqualTo(Enums.Direction.DOWN);
        assertThat(betEvents(alice)).isEqualTo(1);
        assertThat(betEvents(bob)).isEqualTo(1);
    }

    @Test
    void theSameIdForADifferentBetIsRefused() {
        String player = signUp("other");
        String id = newId();
        long at = betting.startMs() - 1_000;
        gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "300.000"), at);

        for (Betting.PlaceBetRequest different : List.of(request(id, Enums.Direction.DOWN, 5, "300.000"),
                request(id, Enums.Direction.UP, 6, "300.000"), request(id, Enums.Direction.UP, 5, "301.000"),
                new Betting.PlaceBetRequest(betting.roundId(), Enums.Direction.UP, 0, "300.000", Enums.BetMode.FIXED, id))) {
            assertThatThrownBy(() -> gameService.placeBet(player, different, at + 2)).isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).code()).isEqualTo(Enums.ApiErrorCode.BAD_REQUEST);
        }
        assertThat(betEvents(player)).isEqualTo(1);
    }

    @Test
    void aRefusedBetIsNotRemembered() {
        String player = signUp("refused");
        String id = newId();
        long at = betting.startMs() - 1_000;
        assertThatThrownBy(() -> gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "1.000"), at))
                .isInstanceOf(ApiException.class); // below the minimum stake

        Rounds.BetDto retried = gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "300.000"), at + 1);

        assertThat(retried.stake()).isEqualTo("300.000");
        assertThat(betEvents(player)).isEqualTo(1);
    }

    @Test
    void aBetWithoutAnIdIsAsItAlwaysWasAndItsEventHasNoSuchKey() {
        String player = signUp("noid");
        long at = betting.startMs() - 1_000;

        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 5, "300.000"), at);
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 5, "300.000"), at + 1); // a second press replaces the bet, as before

        assertThat(betEvents(player)).isEqualTo(2);
        long withKey = db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM game_events WHERE event_type = 'BetPlaced' "
                + "AND payload->>'playerId' = ? AND payload->>'clientRequestId' IS NOT NULL", rs -> rs.getLong("n"), player));
        assertThat(withKey).isZero();
    }

    @Test
    void anIdThatIsNotWellFormedIsRefused() {
        String player = signUp("badid");
        long at = betting.startMs() - 1_000;
        for (String bad : new String[] {"", " ", "a b", "x".repeat(65), "id;drop", "é"}) {
            assertThatThrownBy(() -> gameService.placeBet(player, request(bad, Enums.Direction.UP, 5, "300.000"), at))
                    .as("[%s]", bad).isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code())
                    .isEqualTo(Enums.ApiErrorCode.BAD_REQUEST);
        }
        assertThat(betEvents(player)).isZero();
        gameService.placeBet(player, request("x".repeat(64), Enums.Direction.UP, 5, "300.000"), at); // the longest id
        assertThat(betEvents(player)).isEqualTo(1);
    }

    @Test
    void repeatsThatRaceGiveOneBetAndOneAnswer() throws Exception {
        String player = signUp("race");
        String id = newId();
        long at = betting.startMs() - 1_000;
        int threads = 12;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Rounds.BetDto>> answers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            answers.add(pool.submit(() -> {
                go.await();
                return gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "300.000"), at);
            }));
        }
        go.countDown();

        List<Rounds.BetDto> got = new ArrayList<>();
        for (Future<Rounds.BetDto> f : answers) {
            got.add(f.get(30, TimeUnit.SECONDS));
        }

        assertThat(got).hasSize(threads).containsOnly(got.get(0));
        assertThat(betEvents(player)).isEqualTo(1);
        assertThat(gameService.account(player).inPlay()).isEqualTo("300.000");
    }

    @Test
    void theMemoryIsRestoredByARebuildFromTheJournal() {
        String player = signUp("rebuild");
        String id = newId();
        long at = betting.startMs() - 1_000;
        Rounds.BetDto first = gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "300.000"), at);
        assertThat(hub.betRequests().size()).isGreaterThanOrEqualTo(1);

        rebuild.rebuild();

        assertThat(hub.betRequests().find(player, id, at)).isNotNull();
        Rounds.BetDto repeat = gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "300.000"), at + 10);
        assertThat(repeat).isEqualTo(first);
        assertThat(betEvents(player)).isEqualTo(1);
    }

    @Test
    void theMemoryIsInTheSnapshotText() {
        String player = signUp("snapshot");
        String id = newId();
        long at = betting.startMs() - 1_000;
        gameService.placeBet(player, request(id, Enums.Direction.UP, 5, "300.000"), at);

        String[] text = new String[1];
        gameService.runExclusively(() -> text[0] = StateCodec.encode(hub, new BetFeed()));

        assertThat(text[0]).contains("\nbetRequest\t" + player + "\t" + id + "\t" + at + "\t");
    }

    @Test
    void overHttpARepeatIsTheSameAnswerAndABadIdIs400() throws Exception {
        Session session = signIn("http");
        String id = newId();
        Map<String, Object> body = Map.of("roundId", betting.roundId(), "direction", "UP", "leverage", 5, "stake", "300.000",
                "clientRequestId", id);

        Response first = rpc(session, "placeBet", body);
        Response second = rpc(session, "placeBet", body);

        assertThat(first.statusCode()).as(first.asString()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(ok(second)).isEqualTo(ok(first));
        assertThat(betEvents(session.playerId())).isEqualTo(1);
        assertThat(betEventsWithId(session.playerId(), id)).isEqualTo(1);

        Response bad = rpc(session, "placeBet", Map.of("roundId", betting.roundId(), "direction", "UP", "leverage", 5,
                "stake", "300.000", "clientRequestId", "not valid!"));
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.jsonPath().getString("error.code")).isEqualTo("BAD_REQUEST");

        Response mismatch = rpc(session, "placeBet", Map.of("roundId", betting.roundId(), "direction", "DOWN", "leverage", 5,
                "stake", "300.000", "clientRequestId", id));
        assertThat(mismatch.statusCode()).isEqualTo(400);
        assertThat(betEvents(session.playerId())).isEqualTo(1);
    }
}
