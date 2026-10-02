package crowdvsmarket.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.config.GameConfig;
import crowdvsmarket.server.dto.Auth;
import crowdvsmarket.server.persistence.AuthChallengeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The text a player signs at sign-in names the domain of THIS deployment ({@code herd.public-domain}, compose's
 * {@code HERD_PUBLIC_DOMAIN}) — it used to say {@code herd.local} whatever the deployment was. The text that is
 * stored with the nonce is the one that was shown, byte for byte: chainsvc verifies the signature against it.
 */
class AuthServiceDomainTest {

    private GameConfig config;
    private AuthChallengeRepository challenges;
    private AuthService service;

    @BeforeEach
    void anAuthServiceOfPlainObjects() {
        config = mock(GameConfig.class);
        when(config.challengeTtlMs()).thenReturn(300_000L);
        challenges = mock(AuthChallengeRepository.class);
        service = new AuthService();
        service.config = config;
        service.challenges = challenges;
    }

    @Test
    void theChallengeNamesTheConfiguredDomain() {
        when(config.publicDomain()).thenReturn("herd.example.org");

        Auth.AuthChallengeDto challenge = service.challenge(new Auth.AuthChallengeRequest("xch1abc"));

        assertThat(challenge.message()).startsWith("Herd sign-in\n");
        assertThat(challenge.message()).contains("\nDomain: herd.example.org\n");
        assertThat(challenge.message()).doesNotContain("herd.local");
        assertThat(challenge.message()).contains("\nNetwork: testnet11\n").contains("\nAddress: xch1abc\n")
                .contains("\nNonce: " + challenge.nonce() + "\n");
    }

    @Test
    void theDefaultDomainIsHerdLocalAndWhitespaceAroundItIsDropped() {
        when(config.publicDomain()).thenReturn("herd.local");
        assertThat(service.challenge(null).message()).contains("\nDomain: herd.local\n").contains("\nAddress: ?\n");

        when(config.publicDomain()).thenReturn("  herd.example.org \n");
        assertThat(service.challenge(new Auth.AuthChallengeRequest(null)).message())
                .contains("\nDomain: herd.example.org\n");
    }

    @Test
    void theStoredMessageIsTheOneThePlayerWasShown() {
        when(config.publicDomain()).thenReturn("herd.example.org");

        Auth.AuthChallengeDto challenge = service.challenge(new Auth.AuthChallengeRequest("xch1abc"));

        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(challenges).insert(eq(challenge.nonce()), stored.capture(), eq("xch1abc"), eq(challenge.expiresAtMs()),
                anyLong());
        assertThat(stored.getValue()).isEqualTo(challenge.message());
    }
}
