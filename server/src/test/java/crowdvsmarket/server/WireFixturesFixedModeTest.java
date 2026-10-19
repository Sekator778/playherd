package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Betting;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.LiveRpcTestBase;
import crowdvsmarket.server.testsupport.RpcClient;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import crowdvsmarket.server.testsupport.WireFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The wire of the fixed-payout mode (Р-106): what the server answers for rounds and positions that hold fixed-payout bets, held against
 * {@code web/src/api/wire/*.fixed.json} by shape, like {@link WireFixturesTest} (same rules, same {@code -Dherd.wire.write=true}).
 * The answers about percentage-mode bets are the files of that test and did not change: the {@code mode} key is left out for them.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class WireFixturesFixedModeTest extends LiveRpcTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALICE = "txch1alice0wire0fixed00000000000000000000000000000000000000q2k7ar";
    private static final String BOB = "txch1bob0wire0fixed0000000000000000000000000000000000000000q5h3xm";

    private record Taken(String name, String method, List<?> args, JsonNode body) {
    }

    private final List<Taken> taken = new ArrayList<>();

    private void capture(String name, Session session, String method, Object... args) throws Exception {
        Response response = rpc(session, method, args);
        assertThat(response.statusCode()).as("%s: %s", name, response.asString()).isEqualTo(200);
        taken.add(new Taken(name, method, Arrays.asList(args), JSON.readTree(response.asString())));
    }

    private void placeFixed(String playerId, Market market, Enums.Direction direction, String stake) {
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        gameService.placeBet(playerId, new Betting.PlaceBetRequest(betting.roundId(), direction, 0, stake, Enums.BetMode.FIXED),
                betting.startMs() - 1);
    }

    private void settleRunning(Market market, String open, String close) {
        RoundWindow running = roundProgress.findNext(market.id(), Interval.HOURLY).orElseThrow();
        prices.script(market, running, open, close, open, close);
        prices.setPrice(market, close);
        scheduler.tick(running.endMs());
    }

    @Test
    void theAnswersAboutFixedPayoutBetsHaveTheShapeOfTheirCommittedFixtures() throws Exception {
        long now = System.currentTimeMillis();
        long monthStart = YearMonth.from(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)).atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long start = Math.max(now - 20 * 60_000L, monthStart + 60_000L);
        Market market = registry.list(new NewMarket("T_WIRE_FIXED", MarketKind.PERP, Category.CRYPTO, "WFX", "Fixedcoin", null,
                null, "synthetic", "WFX", null, 25, 1, null), now);
        for (Interval interval : Interval.values()) {
            roundProgress.seed(market.id(), interval, calendar.windowContaining(market.id(), interval, start));
        }
        prices.setPrice(market, "100.000");
        prices.setSoFar(market, "100.000", "100.500");

        gameService.registerOrTouchPlayer(ALICE, start - 5_000L);
        String bobId = gameService.registerOrTouchPlayer(BOB, start - 5_000L).player().playerId();
        Session alice = RpcClient.signInAs(chainSvcClient, ALICE);

        placeFixed(alice.playerId(), market, Enums.Direction.UP, "200.000");     // round A: settled when we look
        placeFixed(bobId, market, Enums.Direction.DOWN, "200.000");
        settleRunning(market, "100.000", "100.000");
        placeFixed(alice.playerId(), market, Enums.Direction.UP, "300.000");     // round B: running
        placeFixed(bobId, market, Enums.Direction.DOWN, "100.000");
        RoundWindow settled = roundProgress.findNext(market.id(), Interval.HOURLY).orElseThrow();
        settleRunning(market, "100.000", "101.000");
        placeFixed(alice.playerId(), market, Enums.Direction.UP, "250.000");     // round C: open for bets

        RoundWindow running = roundProgress.findNext(market.id(), Interval.HOURLY).orElseThrow();
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        prices.setSoFar(market, "101.000", "101.500");

        capture("round.betting.fixed", alice, "round", betting.roundId());
        capture("round.running.fixed", alice, "round", running.roundId());
        capture("round.settled.fixed", alice, "round", settled.roundId());
        capture("myPositions.fixed", alice, "myPositions");
        capture("unseenResults.fixed", alice, "unseenResults");

        assertThat(taken.get(0).body().path("myBet").path("mode").asText()).isEqualTo("FIXED");
        assertThat(taken.get(0).body().path("recentBets").get(0).path("mode").asText()).isEqualTo("FIXED");
        assertThat(taken.get(1).body().path("positions").get(0).path("mode").asText()).isEqualTo("FIXED");
        assertThat(taken.get(2).body().path("positions")).allSatisfy(p -> {
            assertThat(p.path("mode").asText()).isEqualTo("FIXED");
            assertThat(p.path("liquidated").asBoolean()).isFalse();
        });
        assertThat(taken.get(3).body()).allSatisfy(p -> assertThat(p.path("mode").asText()).isEqualTo("FIXED"));
        assertThat(taken.get(4).body().get(0).path("mode").asText()).isEqualTo("FIXED");

        WireFixtures fixtures = WireFixtures.fromSystemProperties();
        for (Taken answer : taken) {
            fixtures.handle(answer.name(), answer.method(), answer.args(), 200, answer.body());
        }
        fixtures.failOnProblems();
    }
}
