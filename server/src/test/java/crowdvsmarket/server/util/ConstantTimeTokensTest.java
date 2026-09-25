package crowdvsmarket.server.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConstantTimeTokensTest {

    @Test
    void equalStringsMatch() {
        assertThat(ConstantTimeTokens.equals("Bearer abc123", "Bearer abc123")).isTrue();
    }

    @Test
    void differentLengthsDoNotMatch() {
        assertThat(ConstantTimeTokens.equals("short", "much-much-longer-token")).isFalse();
    }

    @Test
    void sameLengthDifferentContentDoesNotMatch() {
        assertThat(ConstantTimeTokens.equals("Bearer aaaaaaaa", "Bearer bbbbbbbb")).isFalse();
    }

    @Test
    void emptyStringsMatch() {
        assertThat(ConstantTimeTokens.equals("", "")).isTrue();
    }

    @Test
    void nullIsNeverEqualToANonNullString() {
        assertThat(ConstantTimeTokens.equals(null, "x")).isFalse();
        assertThat(ConstantTimeTokens.equals("x", null)).isFalse();
    }

    @Test
    void bothNullAreEqual() {
        assertThat(ConstantTimeTokens.equals(null, null)).isTrue();
    }
}
