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
 * Task item 2 (BLOCKER security fix, docs/reviews/2026-09-26-independent-critique.md): behind
 * nginx, {@code RpcResource#clientIp} used to read the raw TCP peer address, which is always
 * nginx's own container address — every real player on the stand shared ONE rate-limit bucket per
 * method. Fixed via Quarkus's own {@code quarkus.http.proxy.*} forwarded-header support
 * (application.properties): {@code X-Forwarded-For} is honored only when the raw TCP peer is
 * itself a trusted proxy ({@code quarkus.http.proxy.trusted-proxies}, the compose network subnet
 * {@code 172.28.77.0/24} fixed in docker-compose.yml — the only other member of that network
 * besides {@code db}/{@code server} themselves is {@code web}, nginx). {@code clientIp()} itself
 * is unchanged — it still just reads {@code request.remoteAddress().host()}; the trust decision
 * and header parsing both happen transparently one layer down, in Quarkus/Vert.x.
 *
 * <p>See {@link RateLimitSpoofedProxyTest} for the companion "untrusted peer, header ignored"
 * case — a JUnit test client always connects from loopback, so which peer counts as "trusted" has
 * to differ per test class/profile: this class trusts the loopback peer a RestAssured call
 * actually connects from, the other one uses the application's real default trust config (which a
 * test's loopback peer is never part of).
 */
@QuarkusTest
@TestProfile(RateLimitTrustedProxyTest.TrustedPeerProfile.class)
class RateLimitTrustedProxyTest {

    /** Tiny, no-refill bucket (same trick as {@link RateLimitTest}) + trusts the loopback address
     *  a RestAssured call actually connects from, so {@code X-Forwarded-For} is honored here. */
    public static class TrustedPeerProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "herd.ratelimit.capacity", "2",
                    "herd.ratelimit.refill-per-sec", "0",
                    "quarkus.http.proxy.proxy-address-forwarding", "true",
                    "quarkus.http.proxy.allow-forwarded", "false",
                    "quarkus.http.proxy.allow-x-forwarded", "true",
                    "quarkus.http.proxy.trusted-proxies", "127.0.0.1");
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
    void twoClientsBehindTheTrustedProxyGetSeparateBuckets() {
        // Client A exhausts its own 2-token bucket (keyed by its own X-Forwarded-For value)...
        assertThat(authChallengeFrom("203.0.113.10").statusCode()).isEqualTo(200);
        assertThat(authChallengeFrom("203.0.113.10").statusCode()).isEqualTo(200);
        Response aThrottled = authChallengeFrom("203.0.113.10");
        assertThat(aThrottled.statusCode()).as(aThrottled.asString()).isEqualTo(429);

        // ...but a second, different client behind the very same trusted proxy (a different
        // X-Forwarded-For value, same raw TCP peer 127.0.0.1) is completely unaffected — the fix
        // this test exists for: before it, both would have shared one bucket keyed by 127.0.0.1.
        assertThat(authChallengeFrom("203.0.113.20").statusCode()).isEqualTo(200);
        assertThat(authChallengeFrom("203.0.113.20").statusCode()).isEqualTo(200);
        Response bThrottled = authChallengeFrom("203.0.113.20");
        assertThat(bThrottled.statusCode()).as(bThrottled.asString()).isEqualTo(429);
    }
}
