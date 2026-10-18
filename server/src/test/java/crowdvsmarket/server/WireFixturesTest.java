package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.chain.OracleStatusCache;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.markets.NewMarket;
import crowdvsmarket.server.prices.PricePoint;
import crowdvsmarket.server.service.MarketViewService;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.LiveRpcTestBase;
import crowdvsmarket.server.testsupport.RpcClient;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import crowdvsmarket.server.testsupport.WireFixtures;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The WIRE: what the server really answers over HTTP, held against {@code web/src/api/wire/*.json} — the files the
 * web tests run {@code RealApi} and every page over (parity review, finding 14: 1331 green web tests said nothing
 * about the real wire, and the Status page printed "NaN SEC" for a server that sent {@code roundDurationsSec} in
 * lower case). {@link WireFixtures} has the rules: by default each answer is compared with its committed file BY
 * SHAPE — the same keys, recursively, the same JSON type per key, {@code null} matching anything — and the test
 * fails on any difference, with the command that regenerates the files; with {@code -Dherd.wire.write=true} the
 * files are written instead.
 *
 * <p><b>The scenario</b> is small and deterministic: scripted prices, no network, the scheduler off and the test
 * holding the clock ({@code scheduler.tick(now)} at times of its own; only the HTTP calls carry the real one).
 * Four markets: BTC, a PERP of three price digits ("WPR"), an ODDS market ("BRA", politics, the one that stays
 * open) and an ODDS game ("GER", closed at the end). Three players: Alice (signed in over HTTP — the player of
 * every {@code my*} call), Bob (who is liquidated) and Vera (a veteran: ten rounds in a row, so that the ratings are
 * not empty). In the series of WPR, one round after another, each round with bets:
 * <ol>
 *   <li>ten rounds of Vera, each a little higher, each won;
 *   <li>a round in which Alice, alone, is liquidated;
 *   <li>THE settled round: Alice wins, Bob (25x) is liquidated, the crowd trades with the majority and wins;
 *   <li>the round that RUNS when the answers are taken — Bob DOWN, Vera UP (on BTC: Alice UP, Bob DOWN);
 *   <li>the round OPEN FOR BETS — Vera DOWN, Bob UP (Alice: UP on BTC and on BRA) — and Alice's bet on the game,
 *       whose market is then closed: its running and betting rounds are cancelled with a refund (the cancelled
 *       round, the cancellations log, a closed market in {@code markets}).
 * </ol>
 * (Alice has no bet in the rounds of WPR that the answers are about — {@code rounds}, {@code round} — and the bets
 * she has in play are alike, 250 PLAY at 5x, two on BTC and one on BRA: the web test of the positions strip adds
 * a running and a waiting bet of its own on the room's market, finds a card by its market, and would mix up
 * anything else.)
 * and then every RPC the web client calls is called, one file each; the error envelopes last, the last of them with
 * the engine held back by a failed recovery.
 *
 * <p>Besides the shapes, the test checks that the scenario told its story (the settled round has a liquidated and a
 * winning position, the ratings are not empty, a round is cancelled with a refund, …): the guard compares only
 * shapes, and an array that comes back empty has no shape to compare — a scenario that quietly stopped producing
 * its data would otherwise leave the elements of those arrays unguarded.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class WireFixturesTest extends LiveRpcTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ALICE = "txch1alice0wire0player0000000000000000000000000000000000000q2k7ar";
    private static final String BOB = "txch1bob0wire0player000000000000000000000000000000000000000q5h3xm";
    private static final String VERA = "txch1vera0wire0player00000000000000000000000000000000000000q8d4wn";
    private static final String CAROL = "txch1carol0wire0player0000000000000000000000000000000000000q9m2ztu";
    private static final String CHAIN_LAUNCHER = "c5cb79a5ea20fb66a29c63867806dec4d1120fa002b386c6033fec09b9cdeb20";

    @Inject
    MarketViewService marketView;
    @InjectSpy
    PlayerDirectory playersSpy;
    @InjectMock
    OracleStatusCache oracleStatusCache;

    @AfterEach
    void healTheEngine() {
        Mockito.reset(playersSpy);
        rebuild.rebuild(); // the last capture leaves the engine held back: the next test starts on a ready one
    }

    /** The answers taken, by file name — for the checks on the story. */
    private final Map<String, JsonNode> answers = new LinkedHashMap<>();

    // ---------------------------------------------------------------------------------------
    // the story
    // ---------------------------------------------------------------------------------------

    private void place(String playerId, Market market, Enums.Direction direction, int leverage, String stake) {
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        gameService.placeBet(playerId, bet(betting, direction, leverage, stake), betting.startMs() - 1);
    }

    /**
     * One step of the clock: the round that is running settles now. It is scripted to run from {@code open} to
     * {@code close} (low and high as given), the price of the market stands at {@code close} from here on — the
     * unscripted window of the other series that ends at the same boundary closes at it too, so that both series
     * agree on the boundary (Р-40) — and the scheduler ticks at the end of the round: every series of every market
     * is brought to the next window, and the round that was open for betting is now the running one.
     */
    private void settleRunning(Market market, String open, String close, String low, String high) {
        RoundWindow running = roundProgress.findNext(market.id(), Interval.HOURLY).orElseThrow();
        prices.script(market, running, open, close, low, high);
        prices.setPrice(market, close);
        scheduler.tick(running.endMs());
    }

    private record Story(Session alice, String aliceId, Market perp, RoundWindow settled, RoundWindow cancelled) {
    }

    private Story playTheStory() throws Exception {
        long now = System.currentTimeMillis();
        // The story is played on the test's own clock, in the past: the closing of the game's market at the end
        // carries the real clock, so everything before it must be older than that for the ledger and the history to
        // be in order, as they are in real life (sequence and time grow together). Not before the start of this
        // month either: the season of every bet must be the season of the ratings the test asks for.
        long monthStart = YearMonth.from(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC)).atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long start = Math.max(now - 20 * 60_000L, monthStart + 60_000L);
        Market btc = registry.require("BTC");
        // priced by Coinbase, with Kraken behind it (Р-76): the wire shows a market that has a backup exchange
        Market perp = registry.list(new NewMarket("T_WIRE_PERP", MarketKind.PERP, Category.CRYPTO, "WPR", "Wirecoin",
                null, null, "coinbase", "WPR-USD", "{\"backup\":{\"source\":\"kraken\",\"ref\":\"WPR/USD\"}}", 25, 1,
                null), now);
        Market odds = registry.list(new NewMarket("T_WIRE_ODDS", MarketKind.ODDS, Category.POLITICS, "BRA",
                "Will Lula win the Brazilian election?", "Yes · Brazil election", "https://img.example/lula.png",
                "polymarket", "tok-lula", null, 10, 4, now + 30L * 86_400_000L), now);
        Market game = registry.list(new NewMarket("T_WIRE_GAME", MarketKind.ODDS, Category.SPORTS, "GER",
                "Germany vs France", "Germany · UEFA Nations League", "https://img.example/ger.png", "polymarket",
                "tok-ger", "{\"gameStartTime\":\"2026-10-03 18:45:00+00\"}", 10, 4, null), now);
        for (Market market : List.of(btc, perp, odds, game)) { // one window for everybody: the series run in step
            for (Interval interval : Interval.values()) {
                roundProgress.seed(market.id(), interval, calendar.windowContaining(market.id(), interval, start));
            }
        }

        prices.setPrice(btc, "84000.50");
        prices.setSoFar(btc, "84000.50", "84120.25");
        prices.setPrice(perp, "2500.000");
        prices.setVenue(perp, "coinbase"); // every round of the market runs on its primary exchange
        prices.setSoFar(perp, "2412.000", "2418.500");
        prices.setQuote(odds, "0.5230", now - 500L, 0.0098, false);
        prices.setQuote(game, "0.4100", now - 500L, -0.012, false);
        List<PricePoint> ticks = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            ticks.add(new PricePoint(now - (24 - i) * 20_000L, new BigDecimal("2400.000").add(new BigDecimal(i % 7 + i / 3))));
        }
        prices.setSeries(perp, ticks);
        when(oracleStatusCache.get()).thenReturn(new ChainSvcClient.OracleStatus(CHAIN_LAUNCHER, "testnet11", 38L,
                4_735_705L, 2, null, "9999990000000", null, List.of(), 41L, 2, 3, "300000000"));

        // the players are registered on the story's clock; Alice's sign-in over HTTP then only finds her
        gameService.registerOrTouchPlayer(ALICE, start - 5_000L);
        String bobId = gameService.registerOrTouchPlayer(BOB, start - 5_000L).player().playerId();
        String veraId = gameService.registerOrTouchPlayer(VERA, start - 5_000L).player().playerId();
        Session alice = RpcClient.signInAs(chainSvcClient, ALICE);
        gameService.setNickname(alice.playerId(), "Alice_Wire");
        gameService.setNickname(bobId, "Bob_Wire");
        gameService.setNickname(veraId, "Vera_Wire");

        // Each step settles the round that is running and leaves the round that was open for bets running; a round
        // is bet on while it is open, one step before it settles. The first running round has no bets.
        place(veraId, perp, Enums.Direction.UP, 2, "100.000");                      // round 1
        settleRunning(perp, "2500.000", "2500.000", "2500.000", "2500.000");
        for (int i = 1; i <= 10; i++) {                                            // rounds 1-10: Vera, each won
            if (i < 10) {
                place(veraId, perp, Enums.Direction.UP, 2, "100.000");              // rounds 2-10
            } else {
                place(alice.playerId(), perp, Enums.Direction.UP, 25, "100.000");   // round 11: Alice, alone
            }
            String open = (2500 + 5 * (i - 1)) + ".000";
            String close = (2500 + 5 * i) + ".000";
            settleRunning(perp, open, close, open, close);
        }
        RoundWindow settled = roundProgress.bettingWindow(perp.id(), Interval.HOURLY); // round 12: THE settled round
        place(alice.playerId(), perp, Enums.Direction.UP, 5, "300.000");
        place(bobId, perp, Enums.Direction.UP, 25, "100.000");
        settleRunning(perp, "2550.000", "2400.000", "2400.000", "2550.000");        // round 11: Alice is liquidated
        place(bobId, perp, Enums.Direction.DOWN, 10, "200.000");                    // round 13: running when we look
        place(veraId, perp, Enums.Direction.UP, 4, "120.000");
        place(alice.playerId(), btc, Enums.Direction.UP, 5, "250.000");
        place(bobId, btc, Enums.Direction.DOWN, 4, "100.000");
        settleRunning(perp, "2400.000", "2412.000", "2300.000", "2415.000");        // round 12: Bob is liquidated, Alice wins
        place(veraId, perp, Enums.Direction.DOWN, 3, "400.000");                    // round 14: open for bets when we look
        place(bobId, perp, Enums.Direction.UP, 5, "150.000");
        place(alice.playerId(), btc, Enums.Direction.UP, 5, "250.000");
        place(alice.playerId(), odds, Enums.Direction.UP, 5, "250.000");
        place(alice.playerId(), game, Enums.Direction.UP, 4, "100.000");            // the game's market is closed next
        RoundWindow cancelled = roundProgress.bettingWindow(game.id(), Interval.HOURLY);
        registry.close(game.id(), "The source market ended", System.currentTimeMillis());
        return new Story(alice, alice.playerId(), perp, settled, cancelled);
    }

    // ---------------------------------------------------------------------------------------
    // the capture
    // ---------------------------------------------------------------------------------------

    /** One answer taken, until the fixtures are handled. */
    private record Taken(String name, String method, List<?> args, int status, JsonNode body) {
    }

    private final List<Taken> taken = new ArrayList<>();

    private void capture(String name, Session session, String method, Object... args) throws Exception {
        Response response = rpc(session, method, args);
        assertThat(response.statusCode()).as("%s: %s", name, response.asString()).isEqualTo(200);
        keep(new Taken(name, method, Arrays.asList(args), 200, JSON.readTree(response.asString())));
    }

    private void captureError(String name, Session session, int status, String code, String method, Object... args)
            throws Exception {
        Response response = rpc(session, method, args);
        assertThat(response.statusCode()).as("%s: %s", name, response.asString()).isEqualTo(status);
        JsonNode body = JSON.readTree(response.asString());
        assertThat(body.path("error").path("code").asText()).as(name).isEqualTo(code);
        keep(new Taken(name, method, Arrays.asList(args), status, body));
    }

    private void keep(Taken answer) {
        taken.add(answer);
        answers.put(answer.name(), answer.body());
    }

    @Test
    void everyAnswerOfTheServerHasTheShapeOfItsCommittedFixture() throws Exception {
        Story story = playTheStory();
        WireFixtures fixtures = WireFixtures.fromSystemProperties();
        Session alice = story.alice();
        RoundWindow running = roundProgress.findNext(story.perp().id(), Interval.HOURLY).orElseThrow();
        RoundWindow betting = roundProgress.bettingWindow(story.perp().id(), Interval.HOURLY);
        // the lobby shows Kraken's price for WPR: Coinbase has no fresh tick (the backup's, with its venue named)
        prices.setQuote(story.perp(), "2412.000", System.currentTimeMillis() - 400L, 0.0123, false, "kraken");
        marketView.invalidate();

        capture("serverInfo", null, "serverInfo");
        capture("supply", null, "supply");
        capture("markets", null, "markets");
        capture("rounds.hourly", alice, "rounds", "HOURLY", story.perp().id());
        capture("round.betting", alice, "round", betting.roundId());
        capture("round.running", alice, "round", running.roundId());
        capture("round.settled", alice, "round", story.settled().roundId());
        capture("round.cancelled", alice, "round", story.cancelled().roundId());
        capture("betFeed", null, "betFeed", betting.roundId(), null);
        capture("myPositions", alice, "myPositions");
        capture("myAccount", alice, "myAccount");
        capture("myLedger", alice, "myLedger", Map.of("limit", 50)); // three entries per bet since the fees: the default page of 20 no longer reaches the starter pack
        capture("mySeasonRecord", alice, "mySeasonRecord", (Object) null);
        capture("crowdAccount", null, "crowdAccount", story.perp().id());
        capture("crowdOverview", null, "crowdOverview");
        capture("ratings", null, "ratings", (Object) null);
        capture("publicProfile", alice, "publicProfile", story.aliceId()); // as herself: the complete view; anyone else sees only the wins (Р-90)
        capture("cancellations", null, "cancellations", (Object) null);
        capture("unseenResults", alice, "unseenResults");
        capture("priceSeries", null, "priceSeries", story.perp().id(), null);
        capture("referencePrice", null, "referencePrice", story.perp().id());
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("marketId", story.perp().id());
        preview.put("direction", "UP");
        preview.put("leverage", 10);
        preview.put("stake", "250.000");
        capture("previewLiquidation", null, "previewLiquidation", preview);
        capture("home", alice, "home");
        capture("currentPlayer", alice, "currentPlayer");
        // the sign-in itself, of a player the server has not seen: what a first sign-in answers — a new player, and the pack it
        // credited (AuthSessionDto.starterPackGranted: the web app tells a player about a pack on it)
        RpcClient.SignIn signIn = RpcClient.signInWithAnswer(chainSvcClient, CAROL);
        keep(new Taken("authVerify", "authVerify", List.of(signIn.request()), 200, signIn.answer()));

        captureError("error.notSignedIn", null, 401, "NOT_SIGNED_IN", "myAccount");
        captureError("error.marketNotFound", null, 404, "MARKET_NOT_FOUND", "referencePrice", "NOPE");
        doThrow(new IllegalStateException("db down while reading the players")).when(playersSpy).reload();
        gameService.runExclusively(() -> rebuild.recoverAfterFailure(new IllegalStateException("a failed settlement")));
        assertThat(rebuild.isReady()).as("the engine is held back for the last answer").isFalse();
        marketView.invalidate();
        captureError("error.engineNotReady", null, 503, "ENGINE_NOT_READY", "markets");

        // Writing: the story must hold before anything is written (the files of a scenario that broke are no
        // fixtures). Guarding: the shapes first — a renamed key would otherwise surface as an obscure failure of
        // the story, and the list of what differs is the message that matters.
        if (fixtures.writing()) {
            theScenarioToldItsStory(story, betting, running);
        }
        for (Taken answer : taken) {
            fixtures.handle(answer.name(), answer.method(), answer.args(), answer.status(), answer.body());
        }
        fixtures.failOnProblems();
        if (fixtures.writing()) {
            System.out.println("wire fixtures written (" + fixtures.written().size() + ") to "
                    + System.getProperty(WireFixtures.DIR_PROPERTY, WireFixtures.DIR.toString())
                    + " — review the diff and commit them with the change that needed them");
        } else {
            theScenarioToldItsStory(story, betting, running);
        }
    }

    // ---------------------------------------------------------------------------------------
    // the story checks: the answers hold the data the fixtures are for
    // ---------------------------------------------------------------------------------------

    private static List<JsonNode> elements(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out;
    }

    private void theScenarioToldItsStory(Story story, RoundWindow betting, RoundWindow running) {
        JsonNode info = answers.get("serverInfo");
        assertThat(info.path("engineReady").asBoolean()).isTrue();
        assertThat(info.path("roundDurationsSec").path("HOURLY").asLong()).isEqualTo(60);
        assertThat(info.path("roundDurationsSec").path("DAILY").asLong()).isEqualTo(120);
        assertThat(info.path("oracle").path("lastConfirmedSeq").asLong()).as("the oracle's status is there").isEqualTo(38);
        assertThat(info.path("oracle").path("firstSeq").isNumber()).as("records were queued").isTrue();

        List<JsonNode> markets = elements(answers.get("markets").path("markets"));
        assertThat(markets).extracting(m -> m.path("id").asText())
                .containsExactly("BTC", "T_WIRE_PERP", "T_WIRE_ODDS", "T_WIRE_GAME");
        JsonNode perp = markets.get(1);
        assertThat(perp.path("priceDecimals").asInt()).isEqualTo(3);
        assertThat(perp.path("kind").asText()).isEqualTo("PERP");
        assertThat(perp.path("source").asText()).as("the primary exchange").isEqualTo("coinbase");
        assertThat(perp.path("backupSource").asText()).as("the exchange behind it").isEqualTo("kraken");
        assertThat(perp.path("price").path("venue").asText()).as("the shown price is the backup's").isEqualTo("kraken");
        assertThat(markets.get(0).path("backupSource").isNull()).as("BTC has no backup: null, never left out").isTrue();
        assertThat(markets.get(0).path("price").path("venue").isNull()).isTrue();
        assertThat(markets.get(2).path("backupSource").isNull()).isTrue();
        assertThat(perp.path("series")).hasSize(2);
        assertThat(perp.path("crowd").path("roundsTraded").asInt()).isPositive();
        assertThat(perp.path("crowd").path("lastOutcome").asText()).as("the crowd won the settled round").isEqualTo("WIN");
        assertThat(perp.path("series").get(0).path("majorityDirection").asText()).isEqualTo("DOWN");
        JsonNode odds = markets.get(2);
        assertThat(odds.path("priceDecimals").asInt()).isEqualTo(6);
        assertThat(odds.path("source").asText()).isEqualTo("polymarket");
        assertThat(odds.path("endsAtMs").isNumber()).isTrue();
        JsonNode game = markets.get(3);
        assertThat(game.path("status").asText()).isEqualTo("CLOSED");
        assertThat(game.path("closeReason").asText()).isEqualTo("The source market ended");
        assertThat(game.path("closedAtMs").isNumber()).isTrue();
        assertThat(game.path("eventStartsAtMs").isNumber()).as("a game has its kick-off").isTrue();
        assertThat(game.path("series")).as("a closed market runs no rounds").isEmpty();

        JsonNode pair = answers.get("rounds.hourly");
        assertThat(pair.path("running").path("phase").asText()).isEqualTo("RUNNING");
        assertThat(pair.path("betting").path("phase").asText()).isEqualTo("BETTING");
        for (String name : List.of("rounds.hourly", "round.betting", "round.running", "round.settled", "round.cancelled")) {
            JsonNode round = name.equals("rounds.hourly") ? pair.path("running") : answers.get(name);
            assertThat(round.path("kind").asText()).as(name + ".kind").isIn("PERP", "ODDS");
            assertThat(round.path("priceDecimals").isInt()).as(name + ".priceDecimals").isTrue();
            assertThat(round.path("source").asText()).as(name + ".source").isIn("hyperliquid", "coinbase", "kraken",
                    "polymarket", "synthetic");
        }

        JsonNode roundBetting = answers.get("round.betting");
        assertThat(roundBetting.path("roundId").asText()).isEqualTo(betting.roundId());
        assertThat(roundBetting.path("participantCount").asInt()).isEqualTo(2);
        assertThat(roundBetting.path("recentBets")).hasSize(2);
        assertThat(answers.get("betFeed").path("entries")).hasSize(2);

        JsonNode roundRunning = answers.get("round.running");
        assertThat(roundRunning.path("roundId").asText()).isEqualTo(running.roundId());
        assertThat(roundRunning.path("openPrice").asText()).isEqualTo("2412.000");
        assertThat(roundRunning.path("positions")).hasSize(2);
        assertThat(roundRunning.path("crowd").isObject()).as("the crowd's position, DOWN: 200 against 120").isTrue();

        JsonNode roundSettled = answers.get("round.settled");
        List<JsonNode> positions = elements(roundSettled.path("positions"));
        assertThat(positions).hasSize(2);
        assertThat(positions).filteredOn(p -> p.path("liquidated").asBoolean()).as("one position is liquidated").hasSize(1);
        assertThat(positions).filteredOn(p -> !p.path("liquidated").asBoolean()
                && new BigDecimal(p.path("payout").asText()).compareTo(new BigDecimal(p.path("stake").asText())) > 0)
                .as("one position wins").hasSize(1);
        assertThat(roundSettled.path("crowd").path("traded").asBoolean()).isTrue();
        assertThat(roundSettled.path("resultsHash").asText()).startsWith("0x");
        assertThat(roundSettled.path("oracleAnchor").isObject()).as("the round's record is queued").isTrue();
        assertThat(roundSettled.path("source").asText()).as("the exchange the round ran on").isEqualTo("coinbase");
        assertThat(answers.get("round.running").path("source").asText()).isEqualTo("coinbase");
        assertThat(answers.get("round.betting").path("source").asText()).isEqualTo("coinbase");
        assertThat(roundResults.canonicalJson(story.settled().roundId()).orElseThrow())
                .as("the venue is in the document the hash covers").contains("\"venue\":\"coinbase\"");

        JsonNode roundCancelled = answers.get("round.cancelled");
        assertThat(roundCancelled.path("kind").asText()).isEqualTo("ODDS");
        assertThat(roundCancelled.path("priceDecimals").asInt()).isEqualTo(6);
        assertThat(roundCancelled.path("source").asText()).isEqualTo("polymarket");
        assertThat(roundCancelled.path("resultsHash").asText()).as("a cancelled round has its hash").startsWith("0x");
        assertThat(roundCancelled.path("cancellation").path("refundedBetCount").asInt()).isEqualTo(1);
        assertThat(roundCancelled.path("cancellation").path("marketId").asText()).isEqualTo("T_WIRE_GAME");
        assertThat(roundCancelled.path("cancellation").path("asset").asText()).isEqualTo("GER");

        List<JsonNode> cancellations = elements(answers.get("cancellations").path("items"));
        assertThat(cancellations).isNotEmpty().allSatisfy(c -> {
            assertThat(c.path("marketId").asText()).isEqualTo("T_WIRE_GAME");
            assertThat(c.path("asset").asText()).isEqualTo("GER");
        });

        List<JsonNode> myPositions = elements(answers.get("myPositions"));
        assertThat(myPositions).extracting(p -> p.path("phase").asText()).contains("RUNNING", "BETTING");
        assertThat(myPositions).filteredOn(p -> p.path("phase").asText().equals("RUNNING"))
                .allSatisfy(p -> assertThat(p.path("entryPrice").isTextual()).isTrue());
        List<JsonNode> ledger = elements(answers.get("myLedger").path("items"));
        assertThat(ledger).extracting(e -> e.path("type").asText())
                .contains("STARTER_PACK", "STAKE_DEBIT", "SETTLEMENT", "STAKE_REFUND", "FEE_HERD", "FEE_NETWORK");
        assertThat(answers.get("myAccount").path("inPlay").asText()).isNotEqualTo("0.000");
        assertThat(answers.get("mySeasonRecord").path("roundsPlayed").asInt()).isEqualTo(2);

        assertThat(elements(answers.get("ratings").path("rich"))).as("Vera played ten rounds").hasSize(1);
        assertThat(elements(answers.get("ratings").path("prophets"))).hasSize(1);
        List<JsonNode> history = elements(answers.get("publicProfile").path("history"));
        assertThat(history).hasSize(2);
        assertThat(history).extracting(h -> h.path("liquidated").asBoolean()).containsExactlyInAnyOrder(true, false);
        assertThat(history).allSatisfy(h -> assertThat(h.path("kind").asText()).isEqualTo("PERP"));
        assertThat(history).as("a bet placed under the economy rules keeps its fees in the history").allSatisfy(
                h -> assertThat(h.path("fees").asText()).matches("\\d+\\.\\d{3}"));
        assertThat(answers.get("markets").path("economy").path("stakeTiers")).hasSize(4);
        assertThat(answers.get("publicProfile").path("streak").path("current").asInt()).isEqualTo(1);
        assertThat(elements(answers.get("unseenResults"))).hasSize(2);
        assertThat(elements(answers.get("crowdAccount").path("history"))).isNotEmpty();
        assertThat(elements(answers.get("crowdOverview").path("markets"))).hasSize(4);
        assertThat(elements(answers.get("priceSeries").path("ticks"))).isNotEmpty();
        assertThat(answers.get("priceSeries").path("ticks").get(0).path("price").asText()).matches("\\d+\\.\\d{3}");
        assertThat(answers.get("previewLiquidation").path("liquidationPrice").asText()).matches("\\d+\\.\\d{3}");
        assertThat(answers.get("referencePrice").path("price").asText()).isEqualTo("2412.000");
        assertThat(answers.get("home").path("hourly").path("betting").path("myBet").isObject())
                .as("Alice's bet on BTC").isTrue();
        assertThat(answers.get("home").path("hourly").path("running").path("positions")).as("Alice and Bob on BTC")
                .hasSize(2);
        assertThat(answers.get("currentPlayer").path("nickname").asText()).isEqualTo("Alice_Wire");
        JsonNode signedIn = answers.get("authVerify");
        assertThat(signedIn.path("isNewPlayer").asBoolean()).as("a first sign-in registers the player").isTrue();
        assertThat(signedIn.path("starterPackGranted").asBoolean()).as("…and credits the starter pack").isTrue();
        assertThat(signedIn.path("player").path("playerId").asText()).isNotEmpty();
        assertThat(answers.get("error.engineNotReady").path("error").path("message").asText()).isNotEmpty();
    }
}
