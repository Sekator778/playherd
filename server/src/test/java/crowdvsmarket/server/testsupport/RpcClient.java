package crowdvsmarket.server.testsupport;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import crowdvsmarket.server.chain.ChainSvcClient;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The RPC layer over real HTTP, for any {@code @QuarkusTest}: {@code POST /api/rpc/<method>} with {@code
 * {"args":[...]}} and a session cookie, JSON parsing, and a sign-in through the real {@code authChallenge}/
 * {@code authVerify} (chainsvc's signature check mocked to accept — verifying a signature is chainsvc's job,
 * FIXED INTERFACE 3). Static, no application beans: tests of every mode use it.
 */
public final class RpcClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RpcClient() {
    }

    /** A signed-in player: the id the server knows them by, and the session cookie that proves it. */
    public record Session(String playerId, String cookie) {
    }

    public static Response rpc(Session session, String method, Object... args) {
        var request = given().contentType(ContentType.JSON).body(Map.of("args", Arrays.asList(args)));
        if (session != null) {
            request = request.cookie("herd_session", session.cookie());
        }
        return request.post("/api/rpc/" + method);
    }

    /** D-106: the call of an API bot - {@code Authorization: Bearer <key>} instead of a session cookie. */
    public static Response rpcWithKey(String key, String method, Object... args) {
        var request = given().contentType(ContentType.JSON).body(Map.of("args", Arrays.asList(args)));
        if (key != null) {
            request = request.header("Authorization", "Bearer " + key);
        }
        return request.post("/api/rpc/" + method);
    }

    /** The body of a 200 answer as a JSON tree; any other status fails the test with the body in the message. */
    public static JsonNode ok(Response response) throws Exception {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        return JSON.readTree(response.asString());
    }

    public static JsonNode ok(Session session, String method, Object... args) throws Exception {
        return ok(rpc(session, method, args));
    }

    /** A new player: {@code chainSvc} (the test's {@code @InjectMock}) accepts any signature. */
    public static Session signIn(ChainSvcClient chainSvc, String label) {
        return signInAs(chainSvc, "txch1rpc" + label + UUID.randomUUID().toString().replace("-", ""));
    }

    /** The player of {@code address} (new, or the one that address belongs to), signed in through the real
     *  {@code authChallenge}/{@code authVerify}: for a test that wants an address of its own choosing. */
    public static Session signInAs(ChainSvcClient chainSvc, String address) {
        return signInWithAnswer(chainSvc, address).session();
    }

    /** A sign-in with what went over the wire: the session, the {@code authVerify} request (its single argument) and
     *  the server's answer ({@code AuthSessionDto}: player, isNewPlayer, starterPackGranted). */
    public record SignIn(Session session, Map<String, Object> request, JsonNode answer) {
    }

    /** {@link #signInAs}, keeping the request and the answer of {@code authVerify} for a test that is about them. */
    public static SignIn signInWithAnswer(ChainSvcClient chainSvc, String address) {
        org.mockito.Mockito.when(chainSvc.authVerify(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ChainSvcClient.AuthVerifyResult(true, null));
        Response challenge = given().contentType(ContentType.JSON).body(Map.of("args", List.of()))
                .post("/api/rpc/authChallenge");
        String nonce = challenge.jsonPath().getString("nonce");
        Map<String, Object> request = new LinkedHashMap<>(); // in the order of AuthVerifyRequest: the fixtures print it so
        request.put("nonce", nonce);
        request.put("address", address);
        request.put("publicKey", "0x" + "ab".repeat(48));
        request.put("signature", "0x" + "cd".repeat(96));
        Response verify = given().contentType(ContentType.JSON).body(Map.of("args", List.of(request)))
                .post("/api/rpc/authVerify");
        assertThat(verify.statusCode()).as(verify.asString()).isEqualTo(200);
        try {
            JsonNode answer = JSON.readTree(verify.asString());
            return new SignIn(new Session(verify.jsonPath().getString("player.playerId"), verify.getCookie("herd_session")),
                    request, answer);
        } catch (Exception e) {
            throw new IllegalStateException("authVerify did not answer JSON: " + verify.asString(), e);
        }
    }
}
