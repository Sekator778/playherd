package crowdvsmarket.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What {@link AuthService} lets into the log of a client-supplied address (security review L5). */
class AuthServiceLoggableTest {

    @Test
    void aBech32AddressIsLoggedAsItIs() {
        String address = "txch1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq";
        assertThat(AuthService.loggable(address)).isEqualTo(address);
    }

    @Test
    void anythingElseIsCutAndStrippedOfLineBreaksAndControlCharacters() {
        String forged = "txch1abc\n2026-10-22 ERROR forged line \u001b[31m and a lot more text after it";
        String logged = AuthService.loggable(forged);

        assertThat(logged).doesNotContain("\n").doesNotContain("\u001b").doesNotContain("forged line");
        assertThat(logged).startsWith("(malformed, " + forged.length() + " chars) ");
        assertThat(AuthService.loggable("x".repeat(5000)).length()).isLessThan(60);
        assertThat(AuthService.loggable(null)).isEqualTo("null");
    }
}
