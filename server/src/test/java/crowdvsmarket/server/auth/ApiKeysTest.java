package crowdvsmarket.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Р-106: the shape of a key, its hash and its hint. */
class ApiKeysTest {

    @Test
    void aGeneratedKeyHasTheShapeAndIsDifferentEveryTime() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String key = ApiKeys.generate();
            assertThat(key).matches("herd_[A-Za-z0-9_-]{43}");
            assertThat(ApiKeys.wellFormed(key)).isTrue();
            assertThat(seen.add(key)).as("a key repeated").isTrue();
        }
    }

    @Test
    void theHashIsTheSha256InHexAndTheHintShowsNothingUsable() {
        // SHA-256("abc"), the standard test vector
        assertThat(ApiKeys.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        String key = "herd_" + "A".repeat(39) + "wxyz";
        assertThat(ApiKeys.hint(key)).isEqualTo("herd_...wxyz");
        assertThat(ApiKeys.hash(key)).hasSize(64).doesNotContain(key);
    }

    @Test
    void whatIsNotShapedLikeAKeyIsRefusedBeforeAnyLookup() {
        assertThat(ApiKeys.wellFormed(null)).isFalse();
        assertThat(ApiKeys.wellFormed("")).isFalse();
        assertThat(ApiKeys.wellFormed("herd_short")).isFalse();
        assertThat(ApiKeys.wellFormed("xxxx_" + "A".repeat(43))).isFalse();
        assertThat(ApiKeys.wellFormed("herd_" + "A".repeat(42) + "!")).isFalse();
        assertThat(ApiKeys.wellFormed("herd_" + "A".repeat(44))).isFalse();
        assertThat(ApiKeys.wellFormed("herd_" + "A".repeat(43))).isTrue();
    }
}
