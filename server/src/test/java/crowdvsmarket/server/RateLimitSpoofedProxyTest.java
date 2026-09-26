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
 * Companion to {@link RateLimitTrustedProxyTest} (task item 2, BLOCKER security fix): this class
 * only overrides the rate-limit bucket size, leaving {@code quarkus.http.proxy.trusted-proxies} at
 * the application's REAL default (application.properties: the compose network subnet
 * {@code 172.28.77.0/24}) — a JUnit/RestAssured test always connects from loopback, which is never
 * a member of that subnet, so this exercises the actual "untrusted peer" behavior the production
 * config gives an attacker who is not nginx, not a contrived stand-in for it. Its own
 * {@code @QuarkusTest} class (own Quarkus app context / {@code RateLimiter} instance) so its
 * buckets can never collide with {@link RateLimitTrustedProxyTest}'s.
 */
@QuarkusTest
@TestProfile(RateLimitSpoofedProxyTest.TinyBucketOnlyProfile.class)
class RateLimitSpoofedProxyTest {

    public static class TinyBucketOnlyProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "herd.ratelimit.capacity", "2",
                    "herd.ratelimit.refill-per-sec", "0");
        }
    }

    @InjectMock
    ChainSvcClient chainSvcClient;

    private Response authChallengeFrom(String forwardedFor) {
        return given().contentType(ContentType.JSON)
                .header("X-Forwarded-For", forwardedFor)
                .body(Map.of("args", List.of()))
                .post("/api/rpc/authChallenge");
    }

    @Test
    void spoofedForwardedHeaderFromAnUntrustedPeerIsIgnored() {
        // Two calls claiming to be two different clients via X-Forwarded-For — but the real TCP
        // peer (the test's own loopback connection) is NOT in the trusted-proxies list, so both
        // headers must be ignored and both calls must count against the SAME (raw-peer-keyed)
        // bucket: the second call already exhausts the 2-token bucket regardless of the spoofed
        // header value — exactly the live DoS the independent critique reproduced (27 x 200 then
        // 13 x 429 from a single caller, exhausting the shared bucket for every other player).
        assertThat(authChallengeFrom("203.0.113.30").statusCode()).isEqualTo(200);
        assertThat(authChallengeFrom("203.0.113.40").statusCode()).isEqualTo(200);
        Response third = authChallengeFrom("203.0.113.50");
        assertThat(third.statusCode()).as(third.asString()).isEqualTo(429);
    }
}
