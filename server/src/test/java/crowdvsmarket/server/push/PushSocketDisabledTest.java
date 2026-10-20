package crowdvsmarket.server.push;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code herd.push.enabled=false}: the upgrade is refused (404) and the game publishes nothing - the app polls as before. */
@QuarkusTest
@TestProfile(PushSocketDisabledTest.Off.class)
class PushSocketDisabledTest {

    public static class Off implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("herd.push.enabled", "false");
        }
    }

    @TestHTTPResource("/api/ws")
    URL url;

    @Test
    void theUpgradeIsRefused() {
        URI uri = URI.create(url.toString().replaceFirst("^http", "ws"));

        assertThatThrownBy(() -> WsTestClient.connect(uri, null)).hasStackTraceContaining("404");
    }
}
