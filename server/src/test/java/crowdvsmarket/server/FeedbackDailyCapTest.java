package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.FeedbackRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Р-81: the cap on everyone together — {@code herd.feedback.max-per-day}, here 3 — over the last 24 hours. A cap of the day
 * is a count of rows, so a message older than a day is not in it; a refusal is {@code 429 RATE_LIMITED} like the one for a
 * single address, and it stores nothing. Every request comes from an address of its own, so no per-address bucket gets in
 * the way (that is {@code FeedbackRpcTest}).
 */
@QuarkusTest
@TestProfile(FeedbackDailyCapTest.Profile.class)
class FeedbackDailyCapTest {

    private static final long HOUR = 3_600_000L;

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "herd.feedback.max-per-day", "3",
                    "herd.bots.count", "0",
                    "quarkus.scheduler.enabled", "false",
                    "quarkus.http.proxy.proxy-address-forwarding", "true",
                    "quarkus.http.proxy.allow-forwarded", "false",
                    "quarkus.http.proxy.allow-x-forwarded", "true",
                    "quarkus.http.proxy.trusted-proxies", "127.0.0.1");
        }
    }

    @Inject
    Db db;
    @Inject
    FeedbackRepository repository;

    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @BeforeEach
    void anEmptyDay() {
        db.tx(c -> db.update(c, "DELETE FROM feedback"));
    }

    private static Response submit(String message) {
        int n = NEXT_ADDRESS.getAndIncrement();
        return given().contentType(ContentType.JSON).header("X-Forwarded-For", "198.18." + (n / 250) + "." + (n % 250 + 1))
                .body(Map.of("args", List.of(Map.of("message", message)))).post("/api/rpc/submitFeedback");
    }

    private long rows() {
        return db.read(c -> db.queryOne(c, "SELECT COUNT(*) AS n FROM feedback", rs -> rs.getLong("n")));
    }

    private static FeedbackRepository.NewFeedback message(String text) {
        return new FeedbackRepository.NewFeedback(text, null, null, null, null, null, null);
    }

    @Test
    void theFourthMessageOfTheDayIsRateLimitedFromAnyAddressAndStoresNothing() {
        for (int i = 1; i <= 3; i++) {
            Response ok = submit("message " + i);
            assertThat(ok.statusCode()).as(ok.asString()).isEqualTo(200);
        }

        Response fourth = submit("message 4");

        assertThat(fourth.statusCode()).as(fourth.asString()).isEqualTo(429);
        assertThat(fourth.jsonPath().getString("error.code")).isEqualTo("RATE_LIMITED");
        assertThat(rows()).isEqualTo(3);
        assertThat(submit("message 5").statusCode()).as("still shut").isEqualTo(429);
        assertThat(rows()).isEqualTo(3);
    }

    @Test
    void onlyTheLastTwentyFourHoursCount() {
        long now = System.currentTimeMillis();
        repository.insert(now - 25 * HOUR, message("yesterday, outside the day"));
        repository.insert(now - 48 * HOUR, message("the day before"));
        repository.insert(now - 23 * HOUR, message("inside the day"));
        repository.insert(now - 2 * HOUR, message("inside too"));

        Response third = submit("the third of the day");
        Response fourth = submit("the fourth");

        assertThat(third.statusCode()).as("two messages of the last 24 hours: one place left").isEqualTo(200);
        assertThat(fourth.statusCode()).as(fourth.asString()).isEqualTo(429);
        assertThat(fourth.jsonPath().getString("error.code")).isEqualTo("RATE_LIMITED");
    }

    @Test
    void whenTheOldestMessageOfTheDayIsADayOldThereIsRoomAgain() {
        long now = System.currentTimeMillis();
        repository.insert(now - 24 * HOUR - 1_000, message("a day and a second ago"));
        repository.insert(now - 3 * HOUR, message("a"));
        repository.insert(now - 2 * HOUR, message("b"));
        repository.insert(now - 1 * HOUR, message("c"));

        assertThat(submit("full").statusCode()).isEqualTo(429);

        // the oldest of the three drops out of the day: simulate by moving it back
        db.tx(c -> db.update(c, "UPDATE feedback SET received_at_ms = ? WHERE message = 'a'", now - 25 * HOUR));

        assertThat(submit("room again").statusCode()).isEqualTo(200);
        assertThat(submit("and full again").statusCode()).isEqualTo(429);
    }
}
