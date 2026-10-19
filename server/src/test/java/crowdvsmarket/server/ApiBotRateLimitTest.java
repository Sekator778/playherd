package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.dto.Bots;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.LiveRpcTestBase;
import crowdvsmarket.server.testsupport.RpcClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Р-106: an API key has a budget of its own ({@code herd.ratelimit.bot.*}), apart from the per-address one
 * ({@code herd.ratelimit.*}). A tiny, no-refill bucket for both makes it deterministic in a handful of calls (and gives this
 * class its own application and its own {@code RateLimiter}, so no other test's traffic is in the buckets).
 */
@QuarkusTest
@TestProfile(ApiBotRateLimitTest.TinyBuckets.class)
class ApiBotRateLimitTest extends LiveRpcTestBase {

    public static class TinyBuckets extends LiveMarketsProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config = new HashMap<>(super.getConfigOverrides());
            config.put("herd.ratelimit.capacity", "2");
            config.put("herd.ratelimit.refill-per-sec", "0");
            config.put("herd.ratelimit.bot.capacity", "3");
            config.put("herd.ratelimit.bot.refill-per-sec", "0");
            return config;
        }
    }

    private Bots.ApiBotKeyDto newBot(String name) {
        return gameService.createApiBot(signUp("rl"), name, System.currentTimeMillis());
    }

    private static String code(Response response) {
        return response.jsonPath().getString("error.code");
    }

    // One test method on purpose: the buckets live as long as the application and are not reset between methods.
    @Test
    void aKeyHasItsOwnBudgetThatNeitherOtherKeysNorTheAddressBucketsShare() {
        String keyA = newBot("Limit A").apiKey();
        String keyB = newBot("Limit B").apiKey();

        for (int i = 0; i < 3; i++) {
            assertThat(RpcClient.rpcWithKey(keyA, "currentPlayer").statusCode()).as("call %d of key A", i + 1).isEqualTo(200);
        }
        Response over = RpcClient.rpcWithKey(keyA, "currentPlayer");
        assertThat(over.statusCode()).as(over.asString()).isEqualTo(429);
        assertThat(code(over)).isEqualTo("RATE_LIMITED");

        // another key is another bucket
        assertThat(RpcClient.rpcWithKey(keyB, "currentPlayer").statusCode()).isEqualTo(200);

        // a call that is limited per ADDRESS for a visitor (cancelBet: capacity 2 here) takes from the key's bucket instead:
        // two more calls of key B pass, the third is the key's own limit...
        for (int i = 0; i < 2; i++) {
            assertThat(RpcClient.rpcWithKey(keyB, "cancelBet", "BTC-1h-1").statusCode()).as("cancelBet %d", i + 1)
                    .isNotEqualTo(429);
        }
        assertThat(RpcClient.rpcWithKey(keyB, "cancelBet", "BTC-1h-1").statusCode()).isEqualTo(429);

        // ...and left the address's bucket alone: a visitor from the same address still has its two calls
        assertThat(rpc(null, "cancelBet", "BTC-1h-1").statusCode()).isEqualTo(401);
        assertThat(rpc(null, "cancelBet", "BTC-1h-1").statusCode()).isEqualTo(401);
        assertThat(rpc(null, "cancelBet", "BTC-1h-1").statusCode()).isEqualTo(429);

        // guessing keys is throttled by address: capacity 2 for wrong keys
        assertThat(code(RpcClient.rpcWithKey("herd_" + "C".repeat(43), "currentPlayer"))).isEqualTo("API_KEY_INVALID");
        assertThat(code(RpcClient.rpcWithKey("herd_" + "D".repeat(43), "currentPlayer"))).isEqualTo("API_KEY_INVALID");
        Response guessing = RpcClient.rpcWithKey("herd_" + "E".repeat(43), "currentPlayer");
        assertThat(guessing.statusCode()).isEqualTo(429);
        assertThat(code(guessing)).isEqualTo("RATE_LIMITED");
    }
}
