package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.events.EventStore;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A sign-in that meets an engine that is not ready is answered 503 {@code ENGINE_NOT_READY} — "nothing was changed,
 * please try again" — BEFORE its one-time challenge is consumed (review of the core, finding 6). It used to be burned
 * first: once the engine had recovered, the same signed challenge got 401 {@code CHALLENGE_EXPIRED_OR_UNKNOWN}, and
 * the player a second wallet prompt for a request that had been told to simply retry.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class SignInDuringAnOutageTest extends LiveGameTestBase {

    @InjectSpy
    EventStore eventStoreSpy;

    @AfterEach
    void heal() {
        Mockito.reset(eventStoreSpy);
        rebuild.rebuild();
    }

    private Response rpc(String method, String body) {
        return given().contentType("application/json").body(body).post("/api/rpc/" + method);
    }

    @Test
    void theSameSignedChallengeWorksOnceTheEngineIsBackAfterA503() {
        Mockito.when(chainSvcClient.authVerify(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ChainSvcClient.AuthVerifyResult(true, null));
        String nonce = rpc("authChallenge", "{\"args\":[]}").jsonPath().getString("nonce");
        String body = "{\"args\":[{\"nonce\":\"" + nonce + "\",\"address\":\"txch1outage"
                + UUID.randomUUID().toString().replace("-", "") + "\",\"publicKey\":\"0x" + "ab".repeat(48)
                + "\",\"signature\":\"0x" + "cd".repeat(96) + "\"}]}";
        doThrow(new IllegalStateException("db flapping")).when(eventStoreSpy).loadAll();
        gameService.runExclusively(() -> rebuild.recoverAfterFailure(new IllegalStateException("the settlement failed")));
        assertThat(rebuild.isReady()).isFalse();

        Response during = rpc("authVerify", body);

        assertThat(during.statusCode()).as(during.asString()).isEqualTo(503);
        assertThat(during.jsonPath().getString("error.code")).isEqualTo("ENGINE_NOT_READY");
        Mockito.verify(chainSvcClient, Mockito.never()).authVerify(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()); // refused before the signature is even checked

        Mockito.reset(eventStoreSpy);
        rebuild.rebuild();
        Response after = rpc("authVerify", body);

        assertThat(after.statusCode()).as("the challenge was not consumed: " + after.asString()).isEqualTo(200);
        assertThat(after.jsonPath().getString("player.playerId")).isNotBlank();
    }
}
