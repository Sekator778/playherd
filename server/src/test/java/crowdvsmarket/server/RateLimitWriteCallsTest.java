package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Security review 2026-10-22 (H4): the calls that write for free - bot and key management, {@code setNickname}, {@code
 * acknowledgeResults} - and the per-address daily cap of {@code submitFeedback} are limited per address, before the session is
 * even looked at. Its own profile with tiny, no-refill buckets (the other tests share one address and the test profile opens the
 * real ones wide).
 */
@QuarkusTest
@TestProfile(RateLimitWriteCallsTest.Tiny.class)
class RateLimitWriteCallsTest {

    public static class Tiny implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.ofEntries(
                    Map.entry("herd.ratelimit.manage.capacity", "2"),
                    Map.entry("herd.ratelimit.manage.refill-per-minute", "0"),
                    Map.entry("herd.ratelimit.nickname.capacity", "3"),
                    Map.entry("herd.ratelimit.nickname.refill-per-minute", "0"),
                    Map.entry("herd.ratelimit.ack.capacity", "4"),
                    Map.entry("herd.ratelimit.ack.refill-per-second", "0"),
                    Map.entry("herd.feedback.rate-limit.per-ip-per-day", "2"));
        }
    }

    private Response call(String method, List<Object> args) {
        return given().contentType(ContentType.JSON).body(Map.of("args", args)).post("/api/rpc/" + method);
    }

    private void assertLimitedAfter(int allowed, String method, List<Object> args) {
        for (int i = 1; i <= allowed; i++) {
            Response r = call(method, args);
            assertThat(r.statusCode()).as("%s call %d: %s", method, i, r.asString()).isNotEqualTo(429);
        }
        Response over = call(method, args);
        assertThat(over.statusCode()).as(over.asString()).isEqualTo(429);
        assertThat(over.jsonPath().getString("error.code")).isEqualTo("RATE_LIMITED");
    }

    @Test
    void nicknameAndAcknowledgementHaveABudgetEach() {
        assertLimitedAfter(3, "setNickname", List.of("someone"));
        assertLimitedAfter(4, "acknowledgeResults", List.of(List.of("r1")));
    }

    @Test
    void botAndKeyManagementShareOneBudget() {
        // createBot twice, then the third call of a different management method is refused: one bucket for the three
        assertThat(call("createBot", List.of()).statusCode()).isNotEqualTo(429);
        assertThat(call("regenerateBotKey", List.of("p-x")).statusCode()).isNotEqualTo(429);
        assertThat(call("revokeBotKey", List.of("p-x")).statusCode()).isEqualTo(429);
    }

    @Test
    void oneAddressCannotUseUpMoreThanItsDailyShareOfFeedback() {
        List<Object> args = List.of(Map.of("message", "hello", "page", "/"));
        for (int i = 1; i <= 2; i++) {
            assertThat(call("submitFeedback", args).statusCode()).as("message %d", i).isNotEqualTo(429);
        }
        Response third = call("submitFeedback", args);
        assertThat(third.statusCode()).as(third.asString()).isEqualTo(429);
    }
}
