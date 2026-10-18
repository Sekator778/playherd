package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.prices.PricePoint;
import crowdvsmarket.server.service.MarketViewService;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.LiveRpcTestBase;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The RPC layer of the markets read model over real HTTP — {@code markets}, {@code priceSeries}, {@code
 * myPositions}, {@code crowdOverview} (docs/research/markets_architecture.md §8): the exact field names of
 * web/src/api/contract.ts (no field missing, none extra, absent values as explicit {@code null}), who needs a
 * session, the errors, that the polled reads are not rate limited, and what is answered while a failed recovery
 * keeps the engine from being trusted. The logic behind is tested on plain objects in {@code
 * MarketViewServiceTest} and {@code PriceSeriesServiceTest}.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class MarketsRpcTest extends LiveRpcTestBase {

    @Inject
    MarketViewService marketView;
    @InjectSpy
    PlayerDirectory playersSpy;

    @AfterEach
    void healTheEngine() {
        Mockito.reset(playersSpy);
        rebuild.rebuild(); // whatever a test left half-way: the next one starts on a ready engine
    }

    private static void assertKeys(JsonNode node, String... keys) {
        Set<String> found = new TreeSet<>();
        Iterator<String> names = node.fieldNames();
        names.forEachRemaining(found::add);
        assertThat(found).as("the fields of %s", node).isEqualTo(new TreeSet<>(Set.of(keys)));
    }

    private JsonNode marketNode(JsonNode markets, String id) {
        for (JsonNode market : markets.path("markets")) {
            if (market.path("id").asText().equals(id)) {
                return market;
            }
        }
        throw new AssertionError("no market " + id + " in " + markets);
    }

    /** Both series of the market run at windows whose betting round starts at least 30 s from now (an HTTP
     *  request carries the real clock). */
    private void seedAhead(Market market) {
        for (Interval interval : Interval.values()) {
            roundProgress.seed(market.id(), interval,
                    calendar.windowContaining(market.id(), interval, System.currentTimeMillis() + 30_000L));
        }
    }

    private Map<String, Object> bet(RoundWindow window, String direction, int leverage, String stake) {
        return Map.of("roundId", window.roundId(), "direction", direction, "leverage", leverage, "stake", stake);
    }

    // ---------------------------------------------------------------------------------------
    // markets
    // ---------------------------------------------------------------------------------------

    @Test
    void marketsAnswersInTheFieldNamesOfContractTsWithAbsentValuesAsExplicitNulls() throws Exception {
        Market perp = list("T_MR_PERP", MarketKind.PERP, Category.CRYPTO, "MRP", 25, 1);
        Market odds = list("T_MR_ODDS", MarketKind.ODDS, Category.POLITICS, "MRO", 10, 4);
        long now = System.currentTimeMillis();
        prices.setQuote(perp, "2500.123", now - 500L, 0.0125, false);
        prices.setQuote(odds, "0.5", now - 500L, null, true);
        seedAhead(perp);
        seedAhead(odds);
        Session player = signIn("keys");
        RoundWindow perpBetting = roundProgress.bettingWindow(perp.id(), Interval.HOURLY);
        ok(rpc(player, "placeBet", bet(perpBetting, "UP", 5, "300.000")));
        marketView.invalidate();

        JsonNode answer = ok(null, "markets");

        assertKeys(answer, "serverTimeMs", "categories", "markets", "economy");
        JsonNode economy = answer.path("economy");
        assertKeys(economy, "networkFeePlay", "herdFeeGuardPercent", "stakeTiers");
        assertThat(economy.path("networkFeePlay").asText()).isEqualTo("0.1");
        assertThat(economy.path("herdFeeGuardPercent").asText()).isEqualTo("20");
        assertThat(economy.path("stakeTiers")).hasSize(4);
        assertThat(economy.path("stakeTiers").get(0).path("upToPlay").asLong()).isEqualTo(250);
        assertThat(economy.path("stakeTiers").get(0).path("divisor").asInt()).isEqualTo(1);
        assertThat(economy.path("stakeTiers").get(2).path("upToPlay").asLong()).isEqualTo(1200);
        assertThat(economy.path("stakeTiers").get(2).path("divisor").asInt()).isEqualTo(5);
        assertThat(economy.path("stakeTiers").get(3).get("upToPlay").isNull()).isTrue();
        assertThat(economy.path("stakeTiers").get(3).path("divisor").asInt()).isEqualTo(10);
        assertThat(answer.path("serverTimeMs").asLong()).isBetween(now, now + 60_000L);
        for (JsonNode category : answer.path("categories")) {
            assertKeys(category, "id", "title", "marketCount");
        }
        JsonNode node = marketNode(answer, "T_MR_PERP");
        assertKeys(node, "id", "kind", "category", "symbol", "title", "subtitle", "iconUrl", "maxLeverage",
                "priceDecimals", "status", "endsAtMs", "eventStartsAtMs", "closedAtMs", "closeReason", "oracleIndex",
                "source", "backupSource", "price", "series", "crowd");
        assertThat(node.path("kind").asText()).isEqualTo("PERP");
        assertThat(node.path("category").asText()).isEqualTo("crypto");
        assertThat(node.path("symbol").asText()).isEqualTo("MRP");
        assertThat(node.path("maxLeverage").asInt()).isEqualTo(25);
        assertThat(node.path("priceDecimals").asInt()).isEqualTo(3);
        assertThat(node.path("status").asText()).isEqualTo("ACTIVE");
        for (String nullField : List.of("subtitle", "iconUrl", "endsAtMs", "eventStartsAtMs", "closedAtMs", "closeReason",
                "backupSource")) {
            assertThat(node.get(nullField).isNull()).as(nullField + " is an explicit null").isTrue();
        }
        assertThat(node.path("source").asText()).isEqualTo("synthetic");
        assertThat(node.path("oracleIndex").asInt()).isEqualTo(perp.oracleIndex());

        JsonNode price = node.path("price");
        assertKeys(price, "price", "asOfMs", "change24h", "stale", "venue");
        assertThat(price.get("venue").isNull()).as("the market's own exchange: no venue named").isTrue();
        assertThat(price.path("price").asText()).isEqualTo("2500.123");
        assertThat(price.path("asOfMs").asLong()).isEqualTo(now - 500L);
        assertThat(price.path("change24h").asDouble()).isEqualTo(0.0125);
        assertThat(price.path("stale").asBoolean()).isFalse();
        JsonNode oddsPrice = marketNode(answer, "T_MR_ODDS").path("price");
        assertThat(oddsPrice.path("price").asText()).isEqualTo("0.500000");
        assertThat(oddsPrice.get("change24h").isNull()).isTrue();
        assertThat(oddsPrice.path("stale").asBoolean()).isTrue();

        JsonNode series = node.path("series");
        assertThat(series).hasSize(2);
        assertKeys(series.get(0), "interval", "durationSec", "bettingRoundId", "bettingClosesAtMs", "runningRoundId",
                "runningEndsAtMs", "participantCount", "upStake", "downStake", "majorityDirection", "maxLeverage", "herdFeeBp", "feeFactor");
        assertThat(series.get(0).path("feeFactor").asText()).as("k of the 3-minute round").isEqualTo("1");
        assertThat(series.get(1).path("feeFactor").asText()).as("k of the 15-minute round of a price market").isEqualTo("2.2");
        assertThat(series.get(0).path("interval").asText()).isEqualTo("HOURLY");
        assertThat(series.get(0).path("durationSec").asLong()).isEqualTo(60);
        assertThat(series.get(1).path("interval").asText()).isEqualTo("DAILY");
        assertThat(series.get(1).path("durationSec").asLong()).isEqualTo(120);
        assertThat(series.get(0).path("bettingRoundId").asText()).isEqualTo(perpBetting.roundId());
        assertThat(series.get(0).path("bettingClosesAtMs").asLong()).isEqualTo(perpBetting.startMs());
        assertThat(series.get(0).path("participantCount").asInt()).isEqualTo(1);
        assertThat(series.get(0).path("upStake").asText()).isEqualTo("300.000");
        assertThat(series.get(0).path("downStake").asText()).isEqualTo("0.000");
        assertThat(series.get(0).path("majorityDirection").asText()).isEqualTo("UP");
        assertThat(series.get(1).path("participantCount").asInt()).isZero();
        assertThat(series.get(1).get("majorityDirection").isNull()).as("a tie, 0 vs 0, is null").isTrue();

        JsonNode crowd = node.path("crowd");
        assertKeys(crowd, "capital", "returnPct", "roundsTraded", "roundsWon", "lastOutcome");
        assertThat(crowd.path("capital").asText()).isEqualTo("100000.00");
        assertThat(crowd.path("returnPct").asDouble()).isZero();
        assertThat(crowd.get("lastOutcome").isNull()).isTrue();
    }

    @Test
    void aMarketWithABackupExchangeNamesItAndAPriceFromTheBackupSaysSo() throws Exception {
        Market market = listWithBackup("T_MR_BKP", "MRK", "kraken", "MRK/USD");
        long now = System.currentTimeMillis();
        prices.setQuote(market, "100.5", now - 500L, 0.01, false, "kraken");
        seedAhead(market);
        marketView.invalidate();

        JsonNode node = marketNode(ok(null, "markets"), "T_MR_BKP");

        assertThat(node.path("source").asText()).as("the market's primary exchange").isEqualTo("coinbase");
        assertThat(node.path("backupSource").asText()).isEqualTo("kraken");
        assertThat(node.path("price").path("venue").asText()).as("the shown price is Kraken's").isEqualTo("kraken");
        assertThat(node.path("price").path("price").asText()).isEqualTo("100.50");

        // the same market with the primary's own quote: no venue named
        prices.setQuote(market, "100.4", now - 400L, 0.01, false);
        marketView.invalidate();
        JsonNode primary = marketNode(ok(null, "markets"), "T_MR_BKP");
        assertThat(primary.path("backupSource").asText()).isEqualTo("kraken");
        assertThat(primary.path("price").get("venue").isNull()).isTrue();
    }

    @Test
    void theMarketsCategoriesAreOnlyTheNonEmptyOnesInOrder() throws Exception {
        list("T_MR_CAT", MarketKind.ODDS, Category.SPORTS, "MRC", 10, 4);
        marketView.invalidate();

        JsonNode answer = ok(null, "markets");

        List<String> ids = new ArrayList<>();
        answer.path("categories").forEach(c -> ids.add(c.path("id").asText()));
        List<String> order = Category.ORDER.stream().map(Category::id).toList();
        assertThat(ids).isSubsetOf(order).isSortedAccordingTo(java.util.Comparator.comparingInt(order::indexOf));
        assertThat(ids).contains("crypto", "sports");
        for (JsonNode category : answer.path("categories")) {
            long active = registry.active().stream().filter(m -> m.category().equals(category.path("id").asText())).count();
            assertThat(category.path("marketCount").asLong()).isEqualTo(active).isPositive();
        }
    }

    @Test
    void aGameMarketCarriesItsKickOffFromTheCatalogsNote() throws Exception {
        registry.list(new crowdvsmarket.server.markets.NewMarket("T_MR_GAME", MarketKind.ODDS, Category.SPORTS, "GAM",
                "Team A vs Team B", "Team A · League", "https://img/a.png", "synthetic", "tok",
                "{\"gameStartTime\":\"2026-10-01 18:45:00+00\",\"gameId\":\"123\"}", 10, 4, null),
                System.currentTimeMillis());
        marketView.invalidate();

        JsonNode game = marketNode(ok(null, "markets"), "T_MR_GAME");

        assertThat(game.path("eventStartsAtMs").asLong()).isEqualTo(java.time.Instant.parse("2026-10-01T18:45:00Z").toEpochMilli());
        assertThat(game.get("endsAtMs").isNull()).as("a game's source end date is the kick-off, not an end").isTrue();
        assertThat(game.path("subtitle").asText()).isEqualTo("Team A · League");
        assertThat(game.path("iconUrl").asText()).isEqualTo("https://img/a.png");
    }

    @Test
    void aClosedMarketIsListedWithItsReasonAndNoSeries() throws Exception {
        Market market = list("T_MR_CLOSED", MarketKind.ODDS, Category.POLITICS, "MRX", 10, 4);
        long closedAt = System.currentTimeMillis();
        registry.close(market.id(), "The source market ended", closedAt);
        marketView.invalidate();

        JsonNode node = marketNode(ok(null, "markets"), "T_MR_CLOSED");

        assertThat(node.path("status").asText()).isEqualTo("CLOSED");
        assertThat(node.path("closedAtMs").asLong()).isEqualTo(closedAt);
        assertThat(node.path("closeReason").asText()).isEqualTo("The source market ended");
        assertThat(node.path("series")).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // priceSeries
    // ---------------------------------------------------------------------------------------

    @Test
    void priceSeriesIsPublicAndTakesSinceExclusive() throws Exception {
        Market odds = list("T_MR_TICKS", MarketKind.ODDS, Category.POLITICS, "MRT", 10, 4);
        long now = System.currentTimeMillis();
        List<PricePoint> ticks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ticks.add(new PricePoint(now - (20 - i) * 1_000L, new BigDecimal("0.5").add(new BigDecimal("0.0005").multiply(
                    BigDecimal.valueOf(i)))));
        }
        prices.setSeries(odds, ticks);

        JsonNode all = ok(null, "priceSeries", odds.id());
        assertKeys(all, "marketId", "priceDecimals", "ticks");
        assertThat(all.path("marketId").asText()).isEqualTo("T_MR_TICKS");
        assertThat(all.path("priceDecimals").asInt()).isEqualTo(6);
        assertThat(all.path("ticks")).hasSize(20);
        assertKeys(all.path("ticks").get(0), "atMs", "price");
        assertThat(all.path("ticks").get(0).path("price").asText()).isEqualTo("0.500000");
        assertThat(all.path("ticks").get(19).path("price").asText()).isEqualTo("0.509500");

        long since = ticks.get(14).atMs();
        JsonNode later = ok(null, "priceSeries", odds.id(), since);
        assertThat(later.path("ticks")).as("strictly after sinceMs").hasSize(5);
        assertThat(later.path("ticks").get(0).path("atMs").asLong()).isEqualTo(ticks.get(15).atMs());
        assertThat(ok(null, "priceSeries", odds.id(), ticks.get(19).atMs()).path("ticks")).isEmpty();
        assertThat(ok(rpc(null, "priceSeries", odds.id(), null)).path("ticks")).as("a JSON null since is none").hasSize(20);
    }

    @Test
    void priceSeriesErrors() {
        Response missing = rpc(null, "priceSeries");
        assertThat(missing.statusCode()).isEqualTo(400);

        Response unknown = rpc(null, "priceSeries", "NOPE");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.jsonPath().getString("error.code")).isEqualTo("MARKET_NOT_FOUND");

        for (Object badSince : List.<Object>of("abc", 12.5, true, Map.of("a", 1))) {
            Response bad = rpc(null, "priceSeries", "BTC", badSince);
            assertThat(bad.statusCode()).as("since=" + badSince + " -> " + bad.asString()).isEqualTo(400);
            assertThat(bad.jsonPath().getString("error.code")).isEqualTo("BAD_REQUEST");
        }
    }

    // ---------------------------------------------------------------------------------------
    // myPositions, inPlay
    // ---------------------------------------------------------------------------------------

    @Test
    void myPositionsNeedsASession() {
        Response anonymous = rpc(null, "myPositions");

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.jsonPath().getString("error.code")).isEqualTo("NOT_SIGNED_IN");
    }

    @Test
    void myPositionsListsTheSignedInPlayersOwnBetsAndTheirStakesAreWhatIsInPlay() throws Exception {
        Market perp = list("T_MR_POS1", MarketKind.PERP, Category.CRYPTO, "MP1", 25, 1);
        Market odds = list("T_MR_POS2", MarketKind.ODDS, Category.POLITICS, "MP2", 10, 4);
        prices.setPrice(perp, "2500.000");
        prices.setPrice(odds, "0.4");
        seedAhead(perp);
        seedAhead(odds);
        Session me = signIn("pos");
        Session other = signIn("pos2");
        RoundWindow perpBetting = roundProgress.bettingWindow(perp.id(), Interval.HOURLY);
        RoundWindow oddsBetting = roundProgress.bettingWindow(odds.id(), Interval.DAILY);
        ok(rpc(me, "placeBet", bet(perpBetting, "UP", 5, "250.000")));
        ok(rpc(me, "placeBet", bet(oddsBetting, "DOWN", 3, "125.500")));
        ok(rpc(other, "placeBet", bet(perpBetting, "DOWN", 2, "999.000")));

        JsonNode positions = ok(me, "myPositions");

        assertThat(positions.isArray()).isTrue();
        assertThat(positions).hasSize(2);
        for (JsonNode position : positions) {
            assertKeys(position, "marketId", "symbol", "roundId", "interval", "durationSec", "phase", "direction",
                    "leverage", "stake", "entryPrice", "liquidationPrice", "size", "liquidated", "untilMs");
            assertThat(position.path("phase").asText()).isEqualTo("BETTING");
            assertThat(position.get("entryPrice").isNull()).isTrue();
            assertThat(position.get("liquidationPrice").isNull()).isTrue();
            assertThat(position.get("size").isNull()).isTrue();
            assertThat(position.path("liquidated").asBoolean()).isFalse();
        }
        JsonNode first = positions.get(0);
        JsonNode second = positions.get(1);
        assertThat(first.path("untilMs").asLong()).as("soonest first").isLessThanOrEqualTo(second.path("untilMs").asLong());
        JsonNode perpPosition = first.path("marketId").asText().equals("T_MR_POS1") ? first : second;
        assertThat(perpPosition.path("symbol").asText()).isEqualTo("MP1");
        assertThat(perpPosition.path("roundId").asText()).isEqualTo(perpBetting.roundId());
        assertThat(perpPosition.path("interval").asText()).isEqualTo("HOURLY");
        assertThat(perpPosition.path("durationSec").asLong()).isEqualTo(60);
        assertThat(perpPosition.path("direction").asText()).isEqualTo("UP");
        assertThat(perpPosition.path("leverage").asInt()).isEqualTo(5);
        assertThat(perpPosition.path("stake").asText()).isEqualTo("250.000");
        assertThat(perpPosition.path("untilMs").asLong()).isEqualTo(perpBetting.startMs());

        // PlayerAccountDto.inPlay is the sum of the stakes this lists — for each player their own
        BigDecimal listed = BigDecimal.ZERO;
        for (JsonNode position : positions) {
            listed = listed.add(new BigDecimal(position.path("stake").asText()));
        }
        assertThat(listed).isEqualByComparingTo("375.500");
        assertThat(new BigDecimal(ok(me, "myAccount").path("inPlay").asText())).isEqualByComparingTo(listed);
        assertThat(ok(other, "myPositions")).hasSize(1);
        assertThat(new BigDecimal(ok(other, "myAccount").path("inPlay").asText())).isEqualByComparingTo("999.000");
    }

    // ---------------------------------------------------------------------------------------
    // crowdOverview
    // ---------------------------------------------------------------------------------------

    @Test
    void crowdOverviewIsPublicAndHasARowPerMarketWithTotals() throws Exception {
        Market market = list("T_MR_CROWD", MarketKind.PERP, Category.CRYPTO, "MRW", 25, 1);

        JsonNode overview = ok(null, "crowdOverview");

        assertKeys(overview, "totalCapital", "totalStartingCapital", "returnPct", "markets");
        JsonNode row = null;
        for (JsonNode candidate : overview.path("markets")) {
            assertKeys(candidate, "marketId", "symbol", "title", "status", "category", "capital", "returnPct",
                    "roundsTraded", "roundsWon");
            if (candidate.path("marketId").asText().equals(market.id())) {
                row = candidate;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("symbol").asText()).isEqualTo("MRW");
        assertThat(row.path("title").asText()).isEqualTo("MRW test market");
        assertThat(row.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(row.path("category").asText()).isEqualTo("crypto");
        assertThat(row.path("capital").asText()).isEqualTo("100000.00");
        assertThat(row.path("roundsTraded").asInt()).isZero();
        BigDecimal starting = new BigDecimal(overview.path("totalStartingCapital").asText());
        assertThat(starting).isEqualByComparingTo(new BigDecimal("100000.00").multiply(BigDecimal.valueOf(overview.path("markets").size())));
        assertThat(overview.path("totalCapital").asText()).matches("\\d+\\.\\d{2}");
    }

    // ---------------------------------------------------------------------------------------
    // not rate limited, and what a failed recovery leaves open
    // ---------------------------------------------------------------------------------------

    @Test
    void thePolledReadsAreNotRateLimited() throws Exception {
        Session me = signIn("poll");
        // the limit of a rate-limited method is 30 calls in a burst: go well past it with each polled read
        for (int i = 0; i < 45; i++) {
            assertThat(rpc(null, "markets").statusCode()).as("markets #%d", i).isEqualTo(200);
            assertThat(rpc(null, "priceSeries", "BTC").statusCode()).as("priceSeries #%d", i).isEqualTo(200);
            assertThat(rpc(null, "crowdOverview").statusCode()).as("crowdOverview #%d", i).isEqualTo(200);
            assertThat(rpc(me, "myPositions").statusCode()).as("myPositions #%d", i).isEqualTo(200);
        }
    }

    @Test
    void whileAFailedRecoveryHoldsTheEngineBackTheEngineBackedReadsAre503AndTheRestIsAnswered() throws Exception {
        Session me = signIn("down");
        doThrow(new IllegalStateException("db down while reading the players")).when(playersSpy).reload();
        gameService.runExclusively(() -> rebuild.recoverAfterFailure(new IllegalStateException("a failed settlement")));
        assertThat(rebuild.isReady()).isFalse();
        marketView.invalidate();

        for (Response down : List.of(rpc(null, "markets"), rpc(me, "myPositions"), rpc(null, "crowdOverview"))) {
            assertThat(down.statusCode()).as(down.asString()).isEqualTo(503);
            assertThat(down.jsonPath().getString("error.code")).isEqualTo("ENGINE_NOT_READY");
        }
        // what needs no engine
        assertThat(rpc(null, "priceSeries", "BTC").statusCode()).isEqualTo(200);
        assertThat(rpc(null, "serverInfo").statusCode()).isEqualTo(200);

        Mockito.reset(playersSpy);
        rebuild.rebuild();
        assertThat(rpc(null, "markets").statusCode()).isEqualTo(200);
    }
}
