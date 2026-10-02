package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.chain.ChainSvcClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Task item 2 (wave-1 finding): per-IP rate limit on authChallenge/authVerify/placeBet. Its own
 * {@code @TestProfile} — a tiny, no-refill bucket — makes this deterministic in a handful of calls
 * instead of needing to actually exhaust the real "generous" default (and gets its own isolated
 * Quarkus app context/{@link RateLimiter} instance, so it can never see traffic any other test
 * class sent against the same {@code (ip, method)} bucket).
 */
@QuarkusTest
@TestProfile(RateLimitTest.TinyBucketProfile.class)
class RateLimitTest {

    public static class TinyBucketProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "herd.ratelimit.capacity", "2",
                    "herd.ratelimit.refill-per-sec", "0");
        }
    }

    @InjectMock
    ChainSvcClient chainSvcClient;

    private Response authChallenge() {
        return given().contentType(ContentType.JSON)
                .body(Map.of("args", List.of()))
                .post("/api/rpc/authChallenge");
    }

    // Both assertions below share one test method on purpose: RateLimiter is a plain
    // (non-mocked) @ApplicationScoped bean, so its bucket state is NOT reset between @Test
    // methods the way an @InjectMock is — two separate methods both exhausting the same
    // (ip, "authChallenge") bucket would make whichever one JUnit happens to run second see an
    // already-empty bucket left over by the first, independently of anything either method itself
    // does wrong.
    @Test
    void thirdAuthChallengeIsRateLimitedButAnotherMethodsBudgetIsUnaffected() {
        assertThat(authChallenge().statusCode()).isEqualTo(200);
        assertThat(authChallenge().statusCode()).isEqualTo(200);

        Response third = authChallenge();
        assertThat(third.statusCode()).as(third.asString()).isEqualTo(429);
        assertThat(third.jsonPath().getString("error.code")).isEqualTo("RATE_LIMITED");

        // a different rate-limited method for the same caller still has its own, unexhausted
        // budget — the bucket key is "<ip>:<method>", not just "<ip>".
        Response verifyResp = given().contentType(ContentType.JSON)
                .body(Map.of("args", List.of(Map.of("nonce", "bogus", "address", "a",
                        "publicKey", "pk", "signature", "sig"))))
                .post("/api/rpc/authVerify");
        assertThat(verifyResp.statusCode()).as(verifyResp.asString()).isNotEqualTo(429);
    }

    @Test
    void cancelBetIsRateLimitedLikePlaceBetBeforeItEvenLooksForASession() {
        Response first = rpcWithoutSession("cancelBet");
        Response second = rpcWithoutSession("cancelBet");
        assertThat(first.statusCode()).as(first.asString()).isEqualTo(401);   // not signed in: the call was let through
        assertThat(second.statusCode()).as(second.asString()).isEqualTo(401);

        Response third = rpcWithoutSession("cancelBet");

        assertThat(third.statusCode()).as(third.asString()).isEqualTo(429);
        assertThat(third.jsonPath().getString("error.code")).isEqualTo("RATE_LIMITED");
    }

    private Response rpcWithoutSession(String method) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("args", List.of("BTC-1h-1")))
                .post("/api/rpc/" + method);
    }

    @Test
    void nonRateLimitedMethodIsNeverThrottled() {
        for (int i = 0; i < 5; i++) {
            Response resp = given().contentType(ContentType.JSON)
                    .body(Map.of("args", List.of()))
                    .post("/api/rpc/currentPlayer");
            assertThat(resp.statusCode()).as(resp.asString()).isEqualTo(200);
        }
    }
}
