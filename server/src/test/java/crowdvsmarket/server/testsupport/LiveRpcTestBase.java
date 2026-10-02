package crowdvsmarket.server.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.restassured.response.Response;

/**
 * {@link LiveGameTestBase} plus the RPC layer over real HTTP ({@link RpcClient}). An HTTP request carries the
 * REAL clock, unlike {@code scheduler.tick(now)}: a test that bets over RPC seeds its series so that the
 * window open for betting starts well ahead of the request.
 */
public abstract class LiveRpcTestBase extends LiveGameTestBase {

    protected Response rpc(Session session, String method, Object... args) {
        return RpcClient.rpc(session, method, args);
    }

    protected JsonNode ok(Response response) throws Exception {
        return RpcClient.ok(response);
    }

    protected JsonNode ok(Session session, String method, Object... args) throws Exception {
        return RpcClient.ok(session, method, args);
    }

    protected Session signIn(String label) {
        return RpcClient.signIn(chainSvcClient, label);
    }
}
