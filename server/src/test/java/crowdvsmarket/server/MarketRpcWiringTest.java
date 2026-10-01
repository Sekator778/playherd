package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveGameTestBase;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The RPC layer of the markets, over real HTTP: {@code rounds(interval, marketId?)},
 * {@code crowdAccount(marketId?)}, {@code referencePrice(marketId)} and {@code previewLiquidation} with its
 * optional {@code marketId} answer in the field names of web/src/api/contract.ts ({@code marketId},
 * {@code asset}, {@code holdCapital}), take BTC when the market is left out, and the new error codes come
 * out as 404 / 409 / 422 / 503 with their code in the usual envelope. (The new RPCs {@code markets},
 * {@code priceSeries}, {@code myPositions}, {@code crowdOverview} are the next slice's.)
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class MarketRpcWiringTest extends LiveGameTestBase {

    @Inject
    ObjectMapper mapper;

    private Response rpc(String session, String method, Object... args) {
        var request = given().contentType(ContentType.JSON).body(Map.of("args", Arrays.asList(args)));
        if (session != null) {
            request = request.cookie("herd_session", session);
        }
        return request.post("/api/rpc/" + method);
    }

    private JsonNode json(Response response) throws Exception {
        return mapper.readTree(response.asString());
    }

    private String signIn(String label) {
        org.mockito.Mockito.when(chainSvcClient.authVerify(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ChainSvcClient.AuthVerifyResult(true, null));
        Response challenge = given().contentType(ContentType.JSON).body(Map.of("args", List.of()))
                .post("/api/rpc/authChallenge");
        String nonce = challenge.jsonPath().getString("nonce");
        Response verify = given().contentType(ContentType.JSON).body(Map.of("args", List.of(Map.of(
                "nonce", nonce, "address", "txch1rpc" + label + "a".repeat(40), "publicKey", "0x" + "ab".repeat(48),
                "signature", "0x" + "cd".repeat(96))))).post("/api/rpc/authVerify");
        assertThat(verify.statusCode()).as(verify.asString()).isEqualTo(200);
        return verify.getCookie("herd_session");
    }

    @Test
    void roundsAndCrowdAccountTakeTheMarketAndDefaultToBtc() throws Exception {
        Market market = list("T_RPC", MarketKind.PERP, Category.CRYPTO, "RPC", 25, 1);
        prices.setPrice(market, "2500.000");
        alignTo(market, Interval.HOURLY, roundProgress.next("BTC", Interval.HOURLY));

        JsonNode pair = json(rpc(null, "rounds", "HOURLY", market.id()));
        assertThat(pair.path("betting").path("marketId").asText()).isEqualTo("T_RPC");
        assertThat(pair.path("betting").path("asset").asText()).isEqualTo("RPC");
        assertThat(pair.path("betting").path("phase").asText()).isEqualTo("BETTING");
        assertThat(pair.path("betting").path("roundId").asText()).startsWith("T_RPC-1h-");
        assertThat(pair.path("running").path("marketId").asText()).isEqualTo("T_RPC");
        // the argument left out, or null, is BTC, whose round ids keep the legacy form
        for (JsonNode btcPair : List.of(json(rpc(null, "rounds", "HOURLY")), json(rpc(null, "rounds", "HOURLY",
                (Object) null)))) {
            assertThat(btcPair.path("betting").path("marketId").asText()).isEqualTo("BTC");
            assertThat(btcPair.path("betting").path("asset").asText()).isEqualTo("BTC");
            assertThat(btcPair.path("betting").path("roundId").asText()).startsWith("1h-");
        }

        RoundWindow running = roundProgress.next(market.id(), Interval.HOURLY);
        scheduler.tick(running.endMs());   // one round resolved: the chart has a point
        JsonNode crowd = json(rpc(null, "crowdAccount", market.id()));
        assertThat(crowd.path("marketId").asText()).isEqualTo("T_RPC");
        assertThat(crowd.path("symbol").asText()).isEqualTo("RPC");
        assertThat(crowd.path("capital").asText()).isEqualTo("100000.00");
        JsonNode point = crowd.path("history").get(crowd.path("history").size() - 1);
        assertThat(point.has("holdCapital")).isTrue();
        assertThat(point.has("btcHoldCapital")).isFalse();
        assertThat(point.has("antiCrowdCapital")).isTrue();
        assertThat(json(rpc(null, "crowdAccount")).path("marketId").asText()).isEqualTo("BTC");
        // `home` still describes BTC
        assertThat(json(rpc(null, "home")).path("hourly").path("betting").path("marketId").asText()).isEqualTo("BTC");
    }

    @Test
    void referencePriceAndPreviewSpeakTheMarketsUnitsAndTheNewErrorCodesAreInTheEnvelope() throws Exception {
        Market odds = list("T_RPCODDS", MarketKind.ODDS, Category.POLITICS, "ODD", 10, 4);
        prices.setPrice(odds, "0.400000");

        JsonNode reference = json(rpc(null, "referencePrice", odds.id()));
        assertThat(reference.path("price").asText()).isEqualTo("0.400000");
        assertThat(reference.path("asOfMs").asLong()).isPositive();
        assertThat(rpc(null, "referencePrice").statusCode()).isEqualTo(400);
        Response unknown = rpc(null, "referencePrice", "NOPE");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.jsonPath().getString("error.code")).isEqualTo("MARKET_NOT_FOUND");

        Response preview = rpc(null, "previewLiquidation", Map.of("marketId", odds.id(), "direction", "UP",
                "leverage", 10, "stake", "200.000", "referencePrice", "0.4"));
        assertThat(preview.statusCode()).as(preview.asString()).isEqualTo(200);
        assertThat(preview.jsonPath().getString("entryPriceUsed")).isEqualTo("0.400000");
        assertThat(preview.jsonPath().getString("liquidationPrice")).isEqualTo("0.378947");
        Response tooMuch = rpc(null, "previewLiquidation", Map.of("marketId", odds.id(), "direction", "UP",
                "leverage", 11, "stake", "200.000"));
        assertThat(tooMuch.statusCode()).isEqualTo(422);
        assertThat(tooMuch.jsonPath().getString("error.code")).isEqualTo("INVALID_LEVERAGE");
        // a body without a market is BTC's
        Response btcPreview = rpc(null, "previewLiquidation", Map.of("direction", "UP", "leverage", 10,
                "stake", "100.000", "referencePrice", "50000"));
        assertThat(btcPreview.statusCode()).as(btcPreview.asString()).isEqualTo(200);
        assertThat(btcPreview.jsonPath().getString("entryPriceUsed")).isEqualTo("50000.00");

        // no tick for the market: 503 PRICE_UNAVAILABLE
        prices.clearLatest(odds);
        Response noPrice = rpc(null, "referencePrice", odds.id());
        assertThat(noPrice.statusCode()).isEqualTo(503);
        assertThat(noPrice.jsonPath().getString("error.code")).isEqualTo("PRICE_UNAVAILABLE");
    }

    @Test
    void placeBetOverRpcOnAMarketAndItsErrorCodes() throws Exception {
        Market market = list("T_RPCBET", MarketKind.PERP, Category.CRYPTO, "RBT", 25, 1);
        prices.setPrice(market, "2500.000");
        // an HTTP request carries the real clock: the running window must end at least 30 s from now, so
        // that the window open for betting starts well ahead of the request
        roundProgress.seed(market.id(), Interval.HOURLY,
                calendar.windowContaining(market.id(), Interval.HOURLY, System.currentTimeMillis() + 30_000L));
        String session = signIn("bet");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);

        Response tooHigh = rpc(session, "placeBet", Map.of("roundId", betting.roundId(), "direction", "UP",
                "leverage", 30, "stake", "100.000"));
        assertThat(tooHigh.statusCode()).as(tooHigh.asString()).isEqualTo(422);
        assertThat(tooHigh.jsonPath().getString("error.code")).isEqualTo("INVALID_LEVERAGE");
        assertThat(tooHigh.jsonPath().getString("error.message")).contains("25");

        Response ghost = rpc(session, "placeBet", Map.of("roundId", "GHOST-1h-60000", "direction", "UP",
                "leverage", 2, "stake", "100.000"));
        assertThat(ghost.statusCode()).isEqualTo(404);
        assertThat(ghost.jsonPath().getString("error.code")).isEqualTo("MARKET_NOT_FOUND");

        registry.close(market.id(), "closed by the source", System.currentTimeMillis());
        Response closed = rpc(session, "placeBet", Map.of("roundId", betting.roundId(), "direction", "UP",
                "leverage", 2, "stake", "100.000"));
        assertThat(closed.statusCode()).isEqualTo(409);
        assertThat(closed.jsonPath().getString("error.code")).isEqualTo("MARKET_CLOSED");
        Response closedRounds = rpc(null, "rounds", "HOURLY", market.id());
        assertThat(closedRounds.statusCode()).isEqualTo(409);
        assertThat(closedRounds.jsonPath().getString("error.code")).isEqualTo("MARKET_CLOSED");
    }
}
