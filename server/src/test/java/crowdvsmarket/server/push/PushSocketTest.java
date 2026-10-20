package crowdvsmarket.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.domain.RoundProgress;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.service.GameService;
import crowdvsmarket.server.testsupport.BettingWindows;
import crowdvsmarket.server.testsupport.RpcClient;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** {@code /api/ws} end to end on a real socket: the hello, the topics, the private topic, and the notices the game itself causes. */
@QuarkusTest
@TestProfile(PushSocketTest.OwnApp.class)
class PushSocketTest {

    /** A profile of its own, so that this class gets an application (and a rate limiter) of its own: its sign-ins must not use up the
     *  per-address budget of authChallenge that the other tests of the default application share. */
    public static class OwnApp implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("herd.push.coalesce-ms", "100");
        }
    }

    @TestHTTPResource("/api/ws")
    URL url;
    @Inject
    PushHub hub;
    @Inject
    GameService game;
    @Inject
    RoundProgress roundProgress;
    @InjectMock
    ChainSvcClient chainSvcClient;

    private final List<WsTestClient> open = new ArrayList<>();

    private WsTestClient connect(String cookie) {
        WsTestClient client = WsTestClient.connect(URI.create(url.toString().replaceFirst("^http", "ws")), cookie);
        open.add(client);
        return client;
    }

    @AfterEach
    void closeAll() {
        open.forEach(WsTestClient::close);
        await().atMost(Duration.ofSeconds(10)).until(() -> hub.connectionCount() == 0);
    }

    private Session signIn(String label) {
        return RpcClient.signIn(chainSvcClient, label);
    }

    @Test
    void aSocketIsGreetedAndSubscribesAndAnAnonymousOneCannotHoldThePrivateTopic() {
        WsTestClient client = connect(null);
        client.awaitMessage("\"t\":\"hello\"");
        assertThat(client.messages.get(0)).contains("\"auth\":false");

        client.send("{\"op\":\"sub\",\"topics\":[\"markets\",\"round:BTC-HOURLY-1\",\"me\",\"player:x\"]}");
        client.awaitMessage("\"t\":\"subscribed\"");

        String answer = client.messages.stream().filter(m -> m.contains("subscribed")).findFirst().orElseThrow();
        assertThat(answer).contains("\"markets\"").contains("round:BTC-HOURLY-1")
                .contains("\"me\":\"auth\"").contains("\"player:x\":\"bad-topic\"");
    }

    @Test
    void aNoticeReachesTheSubscriberOfItsTopicOnly() {
        WsTestClient markets = connect(null);
        WsTestClient room = connect(null);
        markets.subscribe("markets");
        room.subscribe("market:ZZZ");

        hub.changed("markets");

        markets.awaitMessage("\"topic\":\"markets\"");
        assertThat(room.has("\"t\":\"n\"")).isFalse();
    }

    @Test
    void pingGetsAPongAndGarbageGetsAnError() {
        WsTestClient client = connect(null);
        client.send("{\"op\":\"ping\"}");
        client.awaitMessage("\"t\":\"pong\"");
        client.send("not json");
        client.awaitMessage("\"code\":\"bad-message\"");
        client.send("{\"op\":\"dance\"}");
        client.awaitMessage("\"code\":\"bad-op\"");
    }

    /** Replay rounds are short here: the window read a moment ago may be gone, so read it, subscribe to it and bet, and try again if it was. */
    private RoundWindow betInAFreshWindow(WsTestClient watcher, String playerId, Enums.Direction direction, Interval interval) {
        for (int attempt = 0; ; attempt++) {
            RoundWindow betting = BettingWindows.fresh(roundProgress, interval);
            watcher.subscribe("round:" + betting.roundId(), "feed:" + betting.roundId(), "market:BTC");
            try {
                game.placeBet(playerId, new Betting.PlaceBetRequest(betting.roundId(), direction, 2, "100.000"),
                        betting.startMs() - 1);
                return betting;
            } catch (crowdvsmarket.server.dto.ApiException e) {
                if (attempt >= 5) {
                    throw e;
                }
            }
        }
    }

    @Test
    void aBetIsAnnouncedToTheRoundTheFeedTheMarketAndOnlyToTheBettorsOwnTopic() {
        Session alice = signIn("pushalice");
        Session bob = signIn("pushbob");
        WsTestClient watcher = connect(null);
        WsTestClient aliceSocket = connect(alice.cookie());
        WsTestClient bobSocket = connect(bob.cookie());
        aliceSocket.subscribe("me");
        bobSocket.subscribe("me");
        aliceSocket.awaitMessage("\"auth\":true");

        RoundWindow betting = betInAFreshWindow(watcher, alice.playerId(), Enums.Direction.UP, Interval.HOURLY);

        watcher.awaitMessage("\"topic\":\"round:" + betting.roundId() + "\"");
        watcher.awaitMessage("\"topic\":\"feed:" + betting.roundId() + "\"");
        watcher.awaitMessage("\"topic\":\"market:BTC\"");
        aliceSocket.awaitMessage("\"topic\":\"me\"");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> Thread.sleep(400));
        assertThat(bobSocket.has("\"topic\":\"me\"")).as("another player's positions are not announced to bob").isFalse();
    }

    @Test
    void aRefusedBetIsNotAnnounced() {
        Session alice = signIn("pushrefused");
        WsTestClient aliceSocket = connect(alice.cookie());
        aliceSocket.subscribe("me", "market:BTC");
        RoundWindow betting = BettingWindows.fresh(roundProgress, Interval.HOURLY);
        long meBefore = aliceSocket.count("\"topic\":\"me\"");

        // too late: the round has started by the time the bet is stamped
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> game.placeBet(alice.playerId(),
                new Betting.PlaceBetRequest(betting.roundId(), Enums.Direction.UP, 2, "100.000"), betting.startMs()))
                .isInstanceOf(crowdvsmarket.server.dto.ApiException.class);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> Thread.sleep(500));
        assertThat(aliceSocket.count("\"topic\":\"me\"")).as("a batch that did not commit announces nothing").isEqualTo(meBefore);
    }

    @Test
    void cancellingABetIsAnnouncedToo() {
        Session alice = signIn("pushcancel");
        WsTestClient watcher = connect(null);
        WsTestClient aliceSocket = connect(alice.cookie());
        aliceSocket.subscribe("me");
        RoundWindow betting = betInAFreshWindow(watcher, alice.playerId(), Enums.Direction.DOWN, Interval.HOURLY);
        long meBefore = aliceSocket.count("\"topic\":\"me\"");
        long feedBefore = watcher.count("\"topic\":\"feed:" + betting.roundId() + "\"");

        game.cancelBet(alice.playerId(), betting.roundId(), betting.startMs() - 1);

        await().atMost(Duration.ofSeconds(15)).until(() -> watcher.count("\"topic\":\"feed:" + betting.roundId() + "\"") > feedBefore);
        await().atMost(Duration.ofSeconds(15)).until(() -> aliceSocket.count("\"topic\":\"me\"") > meBefore);
    }

    @Test
    void aCancelledRoundIsAnnouncedToTheRoundTheMarketAndEverySignedInPlayer() {
        Session alice = signIn("pushround");
        WsTestClient watcher = connect(null);
        WsTestClient aliceSocket = connect(alice.cookie());
        aliceSocket.subscribe("me");
        watcher.subscribe("markets");
        RoundWindow betting = betInAFreshWindow(watcher, alice.playerId(), Enums.Direction.UP, Interval.DAILY);
        long meBefore = aliceSocket.count("\"topic\":\"me\"");

        game.cancelRoundById(betting.roundId(), "test cancellation", System.currentTimeMillis());

        watcher.awaitMessage("\"topic\":\"round:" + betting.roundId() + "\"");
        watcher.awaitMessage("\"topic\":\"markets\"");
        await().atMost(Duration.ofSeconds(15)).until(() -> aliceSocket.count("\"topic\":\"me\"") > meBefore);
    }

    @Test
    void theSocketUnsubscribesAndForgetsAClosedClient() {
        WsTestClient client = connect(null);
        client.subscribe("markets");
        assertThat(hub.subscriberCount("markets")).isEqualTo(1);

        client.send("{\"op\":\"unsub\",\"topics\":[\"markets\"]}");
        client.awaitMessage("unsubscribed");
        assertThat(hub.subscriberCount("markets")).isZero();

        client.subscribe("markets");
        client.close();
        await().atMost(Duration.ofSeconds(10)).until(() -> hub.subscriberCount("markets") == 0);
    }
}
