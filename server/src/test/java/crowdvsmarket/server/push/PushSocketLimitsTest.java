package crowdvsmarket.server.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The limits of {@code /api/ws}: sockets per address (closed 1013 after the upgrade) and upgrade attempts per address (HTTP 429). */
@QuarkusTest
@TestProfile(PushSocketLimitsTest.Tiny.class)
class PushSocketLimitsTest {

    public static class Tiny implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "herd.push.max-per-ip", "2",
                    "herd.push.upgrade.capacity", "4",
                    "herd.push.upgrade.refill-per-sec", "0");
        }
    }

    @TestHTTPResource("/api/ws")
    URL url;

    @Test
    void theThirdSocketOfOneAddressIsClosedAndTheFifthUpgradeAttemptIsRefused() {
        URI uri = URI.create(url.toString().replaceFirst("^http", "ws"));
        WsTestClient first = WsTestClient.connect(uri, null);
        WsTestClient second = WsTestClient.connect(uri, null);
        first.awaitMessage("\"t\":\"hello\"");
        second.awaitMessage("\"t\":\"hello\"");

        WsTestClient third = WsTestClient.connect(uri, null);
        await().atMost(Duration.ofSeconds(15)).until(() -> third.closeCode != -1);
        assertThat(third.closeCode).isEqualTo(1013);
        assertThat(third.closeReason).isEqualTo("ip-limit");
        assertThat(third.has("\"t\":\"hello\"")).isFalse();

        WsTestClient fourth = WsTestClient.connect(uri, null); // the fourth and last upgrade of the bucket
        await().atMost(Duration.ofSeconds(15)).until(() -> fourth.closeCode != -1);

        assertThatThrownBy(() -> WsTestClient.connect(uri, null))
                .hasStackTraceContaining("429");

        first.close();
        second.close();
    }
}
