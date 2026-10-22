package crowdvsmarket.server.push;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which {@code Origin} of a WebSocket upgrade {@link PushUpgradeCheck} lets through (security review L4). */
class PushUpgradeOriginTest {

    private static boolean allowed(String origin, String host) {
        return PushUpgradeCheck.originAllowed(origin, host, "playherd.app");
    }

    @Test
    void noOriginIsANonBrowserClientAndIsAllowed() {
        assertThat(allowed(null, "playherd.app")).isTrue();
        assertThat(allowed("", null)).isTrue();
    }

    @Test
    void theSitesOwnOriginAndLocalOnesAreAllowed() {
        assertThat(allowed("https://playherd.app", "playherd.app")).isTrue();
        assertThat(allowed("https://www.playherd.app", null)).isTrue();
        assertThat(allowed("HTTPS://PlayHerd.app:443", null)).isTrue();
        assertThat(allowed("http://localhost:5173", null)).isTrue();
        assertThat(allowed("http://127.0.0.1:8081", null)).isTrue();
        assertThat(allowed("https://tunnel.example", "tunnel.example:443")).as("the host the request came to").isTrue();
    }

    @Test
    void anotherSiteAndMalformedOriginsAreRefused() {
        assertThat(allowed("https://evil.example", "playherd.app")).isFalse();
        assertThat(allowed("https://playherd.app.evil.example", "playherd.app")).isFalse();
        assertThat(allowed("https://evilplayherd.app", null)).isFalse();
        assertThat(allowed("null", "playherd.app")).isFalse();
        assertThat(allowed("not a uri", null)).isFalse();
    }
}
