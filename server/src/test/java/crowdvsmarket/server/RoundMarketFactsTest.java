package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.dto.Rounds;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.service.MarketViewService;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.LiveRpcTestBase;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a round says about its market (contract.ts {@code RoundBase}, parity review finding 3): {@code kind}, {@code
 * priceDecimals} and {@code source}, on every round of every phase — also on a round whose market has left {@code
 * markets()} (a market is listed for ten minutes after it closes; its rounds stay readable for good, and a round page
 * must print their prices and name their source without it). And the other fields a cancelled round and its place in
 * the log gained: {@code resultsHash}, and the market and symbol on the cancellation itself. The shapes of the whole
 * answers are {@code WireFixturesTest}'s; this is about where the values come from.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class RoundMarketFactsTest extends LiveRpcTestBase {

    @Inject
    MarketViewService marketView;

    private Market perp(String id) {
        return registry.list(new NewMarket(id, MarketKind.PERP, Category.CRYPTO, "RMF", "Round facts perp", null, null,
                "hyperliquid", "RMF", null, 25, 1, null), System.currentTimeMillis());
    }

    private Market odds(String id) {
        return registry.list(new NewMarket(id, MarketKind.ODDS, Category.SPORTS, "RMO", "Round facts game", "Yes · game",
                null, "polymarket", "tok-" + id, null, 10, 4, null), System.currentTimeMillis());
    }

    private static void assertFacts(JsonNode round, String kind, int priceDecimals, String source) {
        assertThat(round.path("kind").asText()).as("kind of %s", round.path("roundId").asText()).isEqualTo(kind);
        assertThat(round.path("priceDecimals").asInt()).as("priceDecimals of %s", round.path("roundId").asText())
                .isEqualTo(priceDecimals);
        assertThat(round.path("source").asText()).as("source of %s", round.path("roundId").asText()).isEqualTo(source);
    }

    @Test
    void everyPhaseOfARoundCarriesItsMarketsKindScaleAndSource() throws Exception {
        Market market = perp("T_RMF_PERP");
        prices.setPrice(market, "2500.000");
        prices.setSoFar(market, "2500.000", "2504.000");
        String player = signUp("facts");
        RoundWindow running = roundProgress.findNext(market.id(), Interval.HOURLY).orElseThrow();
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 5, "100.000"), betting.startMs() - 1);

        assertFacts(ok(null, "round", betting.roundId()), "PERP", 3, "hyperliquid");
        assertFacts(ok(null, "round", running.roundId()), "PERP", 3, "hyperliquid");
        JsonNode pair = ok(null, "rounds", "HOURLY", market.id());
        assertFacts(pair.path("running"), "PERP", 3, "hyperliquid");
        assertFacts(pair.path("betting"), "PERP", 3, "hyperliquid");

        // settled: the round after the running one runs, then settles
        prices.script(market, running, "2500.000", "2500.000", "2500.000", "2500.000");
        scheduler.tick(running.endMs());
        prices.script(market, betting, "2500.000", "2510.000", "2500.000", "2510.000");
        scheduler.tick(betting.endMs());
        JsonNode settled = ok(null, "round", betting.roundId());
        assertThat(settled.path("phase").asText()).isEqualTo("SETTLED");
        assertFacts(settled, "PERP", 3, "hyperliquid");
        assertThat(settled.path("openPrice").asText()).matches("\\d+\\.\\d{3}");

        // cancelled by the operator
        RoundWindow next = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        gameService.placeBet(player, bet(next, Enums.Direction.DOWN, 3, "100.000"), next.startMs() - 1);
        gameService.cancelRoundById(next.roundId(), "operator test", next.startMs() - 1);
        JsonNode cancelled = ok(null, "round", next.roundId());
        assertThat(cancelled.path("phase").asText()).isEqualTo("CANCELLED");
        assertFacts(cancelled, "PERP", 3, "hyperliquid");
    }

    @Test
    void aRoundOfAMarketThatLeftTheListStillCarriesItsKindScaleAndSource() throws Exception {
        Market market = odds("T_RMF_GONE");
        prices.setPrice(market, "0.5230");
        String player = signUp("gone");
        RoundWindow running = roundProgress.findNext(market.id(), Interval.HOURLY).orElseThrow();
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 4, "100.000"), betting.startMs() - 1);
        scheduler.tick(running.endMs());
        prices.script(market, betting, "0.5230", "0.5300", "0.5200", "0.5310");
        scheduler.tick(betting.endMs());
        RoundWindow open = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        gameService.placeBet(player, bet(open, Enums.Direction.UP, 2, "100.000"), open.startMs() - 1);

        // the market closes — eleven minutes ago, as far as the lobby is concerned: it is no longer listed
        registry.close(market.id(), "The source market ended", System.currentTimeMillis() - 11 * 60_000L);
        marketView.invalidate();
        List<String> listed = new ArrayList<>();
        ok(null, "markets").path("markets").forEach(m -> listed.add(m.path("id").asText()));
        assertThat(listed).as("gone from markets()").doesNotContain(market.id());

        JsonNode settled = ok(null, "round", betting.roundId());
        assertThat(settled.path("phase").asText()).isEqualTo("SETTLED");
        assertFacts(settled, "ODDS", 6, "polymarket");
        assertThat(settled.path("closePrice").asText()).isEqualTo("0.530000");
        JsonNode cancelled = ok(null, "round", open.roundId());
        assertThat(cancelled.path("phase").asText()).isEqualTo("CANCELLED");
        assertFacts(cancelled, "ODDS", 6, "polymarket");
        assertThat(cancelled.path("cancellation").path("reason").asText()).isEqualTo("market closed: The source market ended");
        // and the player's history row says what the market was
        JsonNode row = ok(null, "publicProfile", player).path("history").get(0);
        assertThat(row.path("kind").asText()).isEqualTo("ODDS");
        assertThat(row.path("asset").asText()).isEqualTo("RMO");
    }

    @Test
    void aCancelledRoundCarriesItsResultsHashAndTheCancellationNamesItsMarket() throws Exception {
        Market market = perp("T_RMF_CANCEL");
        prices.setPrice(market, "2500.000");
        String player = signUp("cancel");
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        gameService.placeBet(player, bet(betting, Enums.Direction.UP, 5, "100.000"), betting.startMs() - 1);

        Rounds.CancellationDto returned = gameService.cancelRoundById(betting.roundId(), "operator test",
                betting.startMs() - 1);

        assertThat(returned.marketId()).as("the operator's answer too").isEqualTo(market.id());
        assertThat(returned.asset()).isEqualTo("RMF");
        assertThat(returned.refundedBetCount()).isEqualTo(1);

        JsonNode round = ok(null, "round", betting.roundId());
        String storedHash = db.read(c -> db.queryOne(c, "SELECT results_hash FROM round_results WHERE round_id = ?",
                rs -> rs.getString("results_hash"), betting.roundId()));
        assertThat(storedHash).startsWith("0x").hasSize(66);
        assertThat(round.path("resultsHash").asText()).as("the hash the row has, and results.json is verified by")
                .isEqualTo(storedHash);
        assertThat(round.path("cancellation").path("marketId").asText()).isEqualTo(market.id());
        assertThat(round.path("cancellation").path("asset").asText()).isEqualTo("RMF");

        JsonNode logged = null;
        for (JsonNode item : ok(null, "cancellations", new java.util.LinkedHashMap<>()).path("items")) {
            if (item.path("roundId").asText().equals(betting.roundId())) {
                logged = item;
            }
        }
        assertThat(logged).as("the cancellations log lists it").isNotNull();
        assertThat(logged.path("marketId").asText()).isEqualTo(market.id());
        assertThat(logged.path("asset").asText()).isEqualTo("RMF");
    }
}
