package crowdvsmarket.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.InjectMock;
import io.restassured.response.Response;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;

/**
 * What the real-time tests of the whole server need ({@link LiveServerProfile}: live mode, synthetic prices, the
 * scheduler running, rounds of 6 and 12 seconds): the RPC layer over HTTP, the chain service mocked (it accepts
 * every signature and every oracle record), and polling helpers with generous timeouts — the machine running
 * the tests may be busy, so nothing here sleeps for a fixed time and asserts on what it slept through: every
 * wait is "until this is true, at most that long".
 */
public abstract class LiveServerTestBase {

    protected static final Duration BOOT_TIMEOUT = Duration.ofSeconds(120);
    protected static final Duration ROUND_TIMEOUT = Duration.ofSeconds(90);
    protected static final Duration POLL = Duration.ofMillis(250);

    @InjectMock
    protected ChainSvcClient chainSvcClient;

    @BeforeEach
    protected void chainAcceptsEverything() {
        org.mockito.Mockito.when(chainSvcClient.oracleRecords(org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(inv -> {
                    List<ChainSvcClient.OracleRecord> records = inv.getArgument(0);
                    List<Long> seqs = records.stream().map(ChainSvcClient.OracleRecord::seq).toList();
                    return new ChainSvcClient.OracleRecordsResult(seqs.size(), seqs);
                });
        org.mockito.Mockito.when(chainSvcClient.oracleStatus()).thenReturn(null);
        org.mockito.Mockito.when(chainSvcClient.health()).thenReturn(true);
    }

    protected Session signIn(String label) {
        return RpcClient.signIn(chainSvcClient, label);
    }

    protected static JsonNode ok(Session session, String method, Object... args) throws Exception {
        return RpcClient.ok(session, method, args);
    }

    protected static Response rpc(Session session, String method, Object... args) {
        return RpcClient.rpc(session, method, args);
    }

    // ---------------------------------------------------------------------------------------
    // reading the lobby
    // ---------------------------------------------------------------------------------------

    protected static JsonNode marketIn(JsonNode markets, String id) {
        for (JsonNode market : markets.path("markets")) {
            if (market.path("id").asText().equals(id)) {
                return market;
            }
        }
        return null;
    }

    protected static JsonNode seriesIn(JsonNode market, String interval) {
        for (JsonNode series : market.path("series")) {
            if (series.path("interval").asText().equals(interval)) {
                return series;
            }
        }
        return null;
    }

    /** The market's entry of a fresh {@code markets} answer, {@code null} while it is not listed. */
    protected JsonNode market(String id) throws Exception {
        return marketIn(ok(null, "markets"), id);
    }

    /** After boot: every market is listed by the catalog, has a price and both its series running. */
    protected void awaitMarketsListedWithPrices(String... ids) {
        await().atMost(BOOT_TIMEOUT).pollInterval(POLL).untilAsserted(() -> {
            JsonNode markets = ok(null, "markets");
            for (String id : ids) {
                JsonNode market = marketIn(markets, id);
                assertThat(market).as("market %s is listed", id).isNotNull();
                assertThat(market.path("price").isNull()).as("market %s has a price", id).isFalse();
                assertThat(market.path("series")).as("market %s runs its series", id).hasSize(2);
            }
        });
    }

    /** The series of {@code interval} of the market, as soon as its betting round has at least {@code marginMs}
     *  left before it closes. */
    protected JsonNode awaitBettingWindow(String marketId, String interval, long marginMs) {
        JsonNode[] found = new JsonNode[1];
        await().atMost(BOOT_TIMEOUT).pollInterval(Duration.ofMillis(100)).until(() -> {
            try {
                JsonNode series = seriesIn(market(marketId), interval);
                if (series != null
                        && series.path("bettingClosesAtMs").asLong() - System.currentTimeMillis() >= marginMs) {
                    found[0] = series;
                    return true;
                }
            } catch (AssertionError notYet) {
                // a 503 while the engine is still starting: look again
            }
            return false;
        });
        return found[0];
    }

    // ---------------------------------------------------------------------------------------
    // the player's own side
    // ---------------------------------------------------------------------------------------

    protected static Map<String, Object> bet(String roundId, String direction, int leverage, String stake) {
        return Map.of("roundId", roundId, "direction", direction, "leverage", leverage, "stake", stake);
    }

    protected static List<JsonNode> positions(Session session) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        ok(session, "myPositions").forEach(out::add);
        return out;
    }

    protected static BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.path(field).asText());
    }

    /** The settled round, as soon as the scheduler has settled it. */
    protected JsonNode awaitSettled(Session viewer, String roundId) {
        JsonNode[] settled = new JsonNode[1];
        await().atMost(ROUND_TIMEOUT).pollInterval(POLL).until(() -> {
            Response response = rpc(viewer, "round", roundId);
            if (response.statusCode() != 200) {
                return false; // not a round the server knows yet
            }
            JsonNode round = RpcClient.ok(response);
            if ("SETTLED".equals(round.path("phase").asText())) {
                settled[0] = round;
                return true;
            }
            return false;
        });
        return settled[0];
    }

    /** The round if it is settled right now, {@code null} otherwise (never settled, or not yet). */
    protected JsonNode settledOrNull(String roundId) throws Exception {
        Response response = rpc(null, "round", roundId);
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode round = RpcClient.ok(response);
        return "SETTLED".equals(round.path("phase").asText()) ? round : null;
    }
}
