package crowdvsmarket.server.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What of an {@link IllegalArgumentException}'s message reaches a client (security review L3). */
class ApiExceptionMapperTest {

    @Test
    void javaInternalsAreReplacedAndOurOwnTextStays() {
        assertThat(ApiExceptionMapper.clientSafeMessage(
                "No enum constant crowdvsmarket.server.dto.Enums.Interval.NOPE")).isEqualTo("invalid request");
        assertThat(ApiExceptionMapper.clientSafeMessage("Cannot deserialize crowdvsmarket.server.dto.Betting$X"))
                .isEqualTo("invalid request");
        assertThat(ApiExceptionMapper.clientSafeMessage("java.lang.NumberFormatException: x")).isEqualTo("invalid request");
        assertThat(ApiExceptionMapper.clientSafeMessage(null)).isEqualTo("invalid request");
        assertThat(ApiExceptionMapper.clientSafeMessage("x".repeat(500))).isEqualTo("invalid request");
        assertThat(ApiExceptionMapper.clientSafeMessage("nickname must be 3 to 20 characters"))
                .isEqualTo("nickname must be 3 to 20 characters");
    }
}
