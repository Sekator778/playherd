package crowdvsmarket.engine.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlayerIdentityTest {

    @Test
    void shortDisplayNameHasFirst6EllipsisLast4() {
        String address = "xch1qpzry9x8gf2tvdw0s3jn54khce6mua7lmqqqxw";
        PlayerIdentity identity = new PlayerIdentity("p1", "satoshi", address);
        String expected = "satoshi (" + address.substring(0, 6) + "…" + address.substring(address.length() - 4) + ")";
        assertThat(identity.displayName()).isEqualTo(expected);
    }

    @Test
    void shortAddressIsShownInFull() {
        PlayerIdentity identity = new PlayerIdentity("p1", "bob", "xch1short");
        assertThat(identity.displayName()).isEqualTo("bob (xch1short)");
    }

    @Test
    void rejectsBlankNicknameOrAddress() {
        assertThatThrownBy(() -> new PlayerIdentity("p1", " ", "xch1short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayerIdentity("p1", "bob", " ")).isInstanceOf(IllegalArgumentException.class);
    }
}
