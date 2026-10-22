package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.RegistrationCapProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The limits of the security review of 2026-10-22 (H4, L3) through the service. */
@QuarkusTest
@TestProfile(RegistrationCapProfile.class)
class SecurityLimitsTest extends LiveGameTestBase {

    @Inject
    EventStore eventStore;

    @BeforeEach
    void freshBooks() {
        crowdvsmarket.server.testsupport.CleanDatabasePerTestClass.resetDatabaseAndMemory();
    }

    private static String wallet() {
        return "txch1sec" + UUID.randomUUID().toString().replace("-", "");
    }

    @Test
    void anIpv6SubnetCountsAsOneAddressAndAllAddressesTogetherHaveADailyCap() {
        long now = System.currentTimeMillis();
        gameService.registerOrTouchPlayer(wallet(), now, "2001:db8:aa:1:1111:2222:3333:4444");
        gameService.registerOrTouchPlayer(wallet(), now, "2001:db8:aa:1::5");
        assertThatThrownBy(() -> gameService.registerOrTouchPlayer(wallet(), now, "2001:db8:aa:1:ffff::1"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.REGISTRATION_LIMIT);
                    assertThat(e.httpStatus()).isEqualTo(429);
                });
        // another /64 and an address nobody could read are other places; the fourth registration of the day is the last
        assertThat(gameService.registerOrTouchPlayer(wallet(), now, "2001:db8:aa:2::1").isNewPlayer()).isTrue();
        assertThat(gameService.registerOrTouchPlayer(wallet(), now, null).isNewPlayer()).isTrue();
        assertThatThrownBy(() -> gameService.registerOrTouchPlayer(wallet(), now, "203.0.113.9"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.REGISTRATION_LIMIT);
                    assertThat(e.getMessage()).contains("per day");
                });
        // a player who exists still signs in, and the next UTC day has room again
        String known = wallet();
        long nextDay = now + 24L * 3_600_000L + 1_000;
        assertThat(gameService.registerOrTouchPlayer(known, nextDay, "203.0.113.9").isNewPlayer()).isTrue();
        assertThat(gameService.registerOrTouchPlayer(known, nextDay + 1, "203.0.113.10").isNewPlayer()).isFalse();
    }

    @Test
    void anUnchangedNicknameWritesNoEventAndAChangedOneDoes() {
        String player = signUp("nick");
        gameService.setNickname(player, "Alpha One");
        long before = eventStore.lastId();
        assertThat(gameService.setNickname(player, "  Alpha One ").nickname()).isEqualTo("Alpha One");
        assertThat(eventStore.lastId()).as("no journal write for the same nickname").isEqualTo(before);
        assertThat(gameService.setNickname(player, "Alpha Two").nickname()).isEqualTo("Alpha Two");
        assertThat(eventStore.lastId()).isGreaterThan(before);
    }

    @Test
    void ackResultsTakesAtMostTwoHundredIds() {
        String player = signUp("ack");
        List<String> ok = Collections.nCopies(200, "none");
        gameService.ackResults(player, ok, System.currentTimeMillis());
        assertThatThrownBy(() -> gameService.ackResults(player, Collections.nCopies(201, "none"), System.currentTimeMillis()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.BAD_REQUEST);
                    assertThat(e.httpStatus()).isEqualTo(400);
                });
    }

    @Test
    void aMissingDirectionIsA400NotAnNpe() {
        assertThatThrownBy(() -> gameService.previewLiquidation(new Betting.LiquidationPreviewRequest(null, null, 5, "10", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
        assertThatThrownBy(() -> gameService.placeBet(signUp("nodir"), new Betting.PlaceBetRequest("BTC-HOURLY-1", null, 5, "10"),
                System.currentTimeMillis()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.httpStatus()).isEqualTo(400));
    }
}
