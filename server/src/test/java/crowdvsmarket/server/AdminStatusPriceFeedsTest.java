package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * The operator's status page lists every price feed (D-76): which exchange it is, whether it is a polled feed or a
 * stream, and how the stream is doing — {@code idle}, {@code connected}, {@code silent}, {@code rest-fallback}, or {@code
 * disabled} for an exchange switched off. Memory only, so the page answers whatever is wrong elsewhere. The states
 * themselves are tested with the sources ({@code KrakenPriceSourceTest}, {@code StreamSupervisorTest}); here it is the
 * wire shape of the operator's endpoint — which is not part of {@code contract.ts}.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class AdminStatusPriceFeedsTest {

    @ConfigProperty(name = "herd.operator.token")
    String operatorToken;

    private Response status() {
        return given().header("Authorization", "Bearer " + operatorToken).get("/api/admin/status");
    }

    @Test
    void theStatusListsEveryFeedWithItsExchangeModeAndState() {
        Response response = status();

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        List<Map<String, Object>> feeds = response.jsonPath().getList("priceFeeds");
        assertThat(feeds).isNotNull().isNotEmpty();
        for (Map<String, Object> feed : feeds) {
            assertThat(feed.keySet()).as(feed.toString())
                    .containsExactlyInAnyOrder("feed", "venue", "mode", "state", "lastMessageAtMs", "restReads");
            assertThat(feed.get("mode")).isIn("poll", "stream");
        }
        assertThat(feeds).extracting(feed -> feed.get("feed")).contains("coinbase", "kraken", "hyperliquid:spot");
        Map<String, Object> kraken = feeds.stream().filter(feed -> "kraken".equals(feed.get("feed"))).findFirst().orElseThrow();
        assertThat(kraken.get("venue")).isEqualTo("kraken");
        assertThat(kraken.get("mode")).isEqualTo("stream");
        assertThat(kraken.get("state")).as("nothing has polled it in this application: the stream is closed")
                .isEqualTo("idle");
        assertThat(kraken.get("lastMessageAtMs")).isNull();
        Map<String, Object> coinbase = feeds.stream().filter(feed -> "coinbase".equals(feed.get("feed"))).findFirst().orElseThrow();
        assertThat(coinbase.get("venue")).isEqualTo("coinbase");
    }
}
