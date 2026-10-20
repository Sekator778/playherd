package crowdvsmarket.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.testsupport.LiveServerProfile;
import crowdvsmarket.server.testsupport.LiveServerTestBase;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import org.junit.jupiter.api.Test;

/**
 * The real game in real time ({@link LiveServerProfile}: synthetic prices, rounds of 6 and 12 seconds, the scheduler on): a client on
 * {@code /api/ws} is told when the price of a market moves (the ticker), when a bet is placed and when the round is settled by the
 * scheduler - and what it then reads over RPC is the settled round.
 */
@QuarkusTest
@TestProfile(LiveServerProfile.class)
class PushLiveGameTest extends LiveServerTestBase {

    @TestHTTPResource("/api/ws")
    URL url;

    @Test
    void thePriceMovesABetIsPlacedAndTheSchedulerSettlesTheRoundAndTheSocketIsToldEachTime() throws Exception {
        awaitMarketsListedWithPrices("BTC");
        Session me = signIn("pushlive");
        WsTestClient socket = WsTestClient.connect(URI.create(url.toString().replaceFirst("^http", "ws")), me.cookie());
        try {
            socket.subscribe("me", "markets", "market:BTC");

            // the synthetic price ticks every second: the ticker announces the market without anybody betting
            await().atMost(ROUND_TIMEOUT).until(() -> socket.count("\"topic\":\"market:BTC\"") >= 2);
            assertThat(socket.has("\"topic\":\"markets\"")).isTrue();

            JsonNode series = awaitBettingWindow("BTC", "DAILY", 7_000L);
            String roundId = series.path("bettingRoundId").asText();
            socket.subscribe("round:" + roundId, "feed:" + roundId);
            long meBefore = socket.count("\"topic\":\"me\"");
            ok(me, "placeBet", bet(roundId, "UP", 2, "100.000"));

            await().atMost(ROUND_TIMEOUT).until(() -> socket.count("\"topic\":\"me\"") > meBefore);
            socket.awaitMessage("\"topic\":\"round:" + roundId + "\"");
            socket.awaitMessage("\"topic\":\"feed:" + roundId + "\"");

            // settled by the scheduler: the notice comes with the settlement, and the RPC then shows the result
            long roundNoticesBefore = socket.count("\"topic\":\"round:" + roundId + "\"");
            long meAfterBet = socket.count("\"topic\":\"me\"");
            await().atMost(ROUND_TIMEOUT).pollInterval(POLL)
                    .until(() -> "SETTLED".equals(ok(null, "round", roundId).path("phase").asText()));
            await().atMost(ROUND_TIMEOUT).until(() -> socket.count("\"topic\":\"round:" + roundId + "\"") > roundNoticesBefore);
            await().atMost(ROUND_TIMEOUT).until(() -> socket.count("\"topic\":\"me\"") > meAfterBet);
        } finally {
            socket.close();
        }
    }
}
