package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import crowdvsmarket.engine.market.Interval;
import crowdvsmarket.engine.round.RoundWindow;
import crowdvsmarket.server.domain.PlayerDirectory;
import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.markets.Market;
import crowdvsmarket.server.markets.MarketKind;
import crowdvsmarket.server.testsupport.LiveMarketsProfile;
import crowdvsmarket.server.testsupport.LiveRpcTestBase;
import crowdvsmarket.server.testsupport.RpcClient;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Р-106: bots of outside developers. A person creates them (three at most), each is its own player with a starter pack
 * and a key; the key opens the bot through the same {@code /api/rpc} calls, but only the calls a bot needs; the bot cannot
 * withdraw; the bots have a board of their own; the key survives a rebuild of the engine and is not in the journal.
 */
@QuarkusTest
@TestProfile(LiveMarketsProfile.class)
class ApiBotsTest extends LiveRpcTestBase {

    @Inject
    PlayerDirectory directory;

    private JsonNode createBot(Session owner, String name) throws Exception {
        return ok(owner, "createBot", name);
    }

    private static String keyOf(JsonNode created) {
        return created.path("apiKey").asText();
    }

    private static String botIdOf(JsonNode created) {
        return created.path("bot").path("playerId").asText();
    }

    private Response asBot(String key, String method, Object... args) {
        return RpcClient.rpcWithKey(key, method, args);
    }

    private static String errorCode(Response response) {
        return response.jsonPath().getString("error.code");
    }

    private Market market(String id) {
        Market market = list(id, MarketKind.PERP, Category.CRYPTO, "ABT", 25, 1);
        prices.setPrice(market, "2500.000");
        return market;
    }

    /** The series of the market sit at windows whose betting round starts at least 30 s from now (an HTTP request carries the real clock). */
    private void seedAhead(Market market) {
        for (Interval interval : Interval.values()) {
            roundProgress.seed(market.id(), interval,
                    calendar.windowContaining(market.id(), interval, System.currentTimeMillis() + 30_000L));
        }
    }

    // ---------------------------------------------------------------------------------------
    // creating bots and keys
    // ---------------------------------------------------------------------------------------

    @Test
    void aBotIsItsOwnPlayerWithAStarterPackAndAKeyShownOnce() throws Exception {
        Session owner = signIn("owner");
        JsonNode created = createBot(owner, "Alpha Bot");

        String key = keyOf(created);
        String botId = botIdOf(created);
        assertThat(key).matches("herd_[A-Za-z0-9_-]{43}");
        assertThat(botId).isNotEqualTo(owner.playerId());
        assertThat(created.path("bot").path("nickname").asText()).isEqualTo("Alpha Bot");
        assertThat(created.path("bot").path("keyActive").asBoolean()).isTrue();
        assertThat(created.path("bot").path("keyHint").asText()).isEqualTo("herd_..." + key.substring(key.length() - 4));

        // the same player model as a person: a balance with the normal starter pack
        Session person = signIn("peer");
        assertThat(gameService.balanceOf(botId)).isEqualTo(gameService.balanceOf(person.playerId()));
        assertThat(gameService.balanceOf(botId).orElseThrow().isPositive()).isTrue();

        // marked as an API bot; is_bot keeps meaning "the house's own bot"
        PlayerDirectory.Row row = directory.byId(botId).orElseThrow();
        assertThat(row.isApiBot()).isTrue();
        assertThat(row.isBot()).isFalse();
        assertThat(row.ownerId()).isEqualTo(owner.playerId());
        assertThat(directory.bots()).extracting(PlayerDirectory.Row::playerId).doesNotContain(botId);

        // the list never carries the key again, only the hint
        JsonNode listed = ok(owner, "myBots");
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).has("apiKey")).isFalse();
        assertThat(listed.toString()).doesNotContain(key);

        // stored as a hash only
        String stored = db.read(c -> db.queryOne(c, "SELECT api_key_hash FROM players WHERE player_id = ?",
                rs -> rs.getString(1), botId));
        assertThat(stored).matches("[0-9a-f]{64}").isNotEqualTo(key);
        Long keyInRows = db.read(c -> db.queryOne(c, "SELECT COUNT(*) FROM players WHERE api_key_hash = ? OR api_key_hint = ? "
                + "OR nickname = ?", rs -> rs.getLong(1), key, key, key));
        assertThat(keyInRows).isZero();
    }

    @Test
    void aPersonHasAtMostThreeBotsAndOnlyAPersonCreatesThem() throws Exception {
        Session owner = signIn("cap");
        createBot(owner, "Bot One");
        createBot(owner, "Bot Two");
        JsonNode third = createBot(owner, "Bot Three");

        Response fourth = rpc(owner, "createBot", "Bot Four");
        assertThat(fourth.statusCode()).as(fourth.asString()).isEqualTo(409);
        assertThat(errorCode(fourth)).isEqualTo("BOT_LIMIT_REACHED");

        // a revoked key does not free the place: a bot is never deleted
        ok(owner, "revokeBotKey", botIdOf(third));
        assertThat(errorCode(rpc(owner, "createBot", "Bot Five"))).isEqualTo("BOT_LIMIT_REACHED");
        assertThat(ok(owner, "myBots")).hasSize(3);

        // the count is per owner
        Session other = signIn("cap2");
        createBot(other, "Other Bot");
        assertThat(ok(other, "myBots")).hasSize(1);

        // not signed in, or a bad name
        assertThat(errorCode(rpc(null, "createBot", "No Session"))).isEqualTo("NOT_SIGNED_IN");
        Response badName = rpc(other, "createBot", "x");
        assertThat(badName.statusCode()).isEqualTo(400);
        assertThat(errorCode(badName)).isEqualTo("INVALID_NICKNAME");
    }

    @Test
    void ownersSeeOnlyTheirOwnBotsAndCannotTouchSomeoneElses() throws Exception {
        Session alice = signIn("own1");
        Session mallory = signIn("own2");
        JsonNode bot = createBot(alice, "Alice Bot");

        assertThat(ok(mallory, "myBots")).isEmpty();
        Response revoke = rpc(mallory, "revokeBotKey", botIdOf(bot));
        assertThat(revoke.statusCode()).isEqualTo(404);
        Response regenerate = rpc(mallory, "regenerateBotKey", botIdOf(bot));
        assertThat(regenerate.statusCode()).isEqualTo(404);
        Response person = rpc(mallory, "regenerateBotKey", alice.playerId());
        assertThat(person.statusCode()).as("a person is not a bot").isEqualTo(404);
        assertThat(asBot(keyOf(bot), "currentPlayer").statusCode()).as("the key still opens the bot").isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------
    // the key opens the bot
    // ---------------------------------------------------------------------------------------

    @Test
    void aValidKeyActsAsTheBotOnTheSameCallsAPersonUses() throws Exception {
        Market market = market("T_AB_PLAY");
        seedAhead(market);
        Session owner = signIn("play");
        JsonNode created = createBot(owner, "Play Bot");
        String key = keyOf(created);
        String botId = botIdOf(created);

        JsonNode me = RpcClient.ok(asBot(key, "currentPlayer"));
        assertThat(me.path("playerId").asText()).isEqualTo(botId);
        assertThat(me.path("isBot").asBoolean()).isTrue();

        assertThat(RpcClient.ok(asBot(key, "markets")).path("markets")).isNotEmpty();
        JsonNode account = RpcClient.ok(asBot(key, "myAccount"));
        assertThat(account.path("balance").asText()).isNotEmpty();

        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        Map<String, Object> bet = Map.of("roundId", betting.roundId(), "direction", "UP", "leverage", 5, "stake", "300.000");
        JsonNode placed = RpcClient.ok(asBot(key, "placeBet", bet));
        assertThat(placed.path("direction").asText()).isEqualTo("UP");
        assertThat(RpcClient.ok(asBot(key, "myPositions"))).hasSize(1);
        assertThat(RpcClient.ok(asBot(key, "myLedger", Map.of("limit", 50))).path("items")).isNotEmpty();

        // the bet is the bot's own, in the room's feed under a bot badge
        JsonNode feed = RpcClient.ok(asBot(key, "betFeed", betting.roundId(), null));
        assertThat(feed.path("entries")).isNotEmpty();
        assertThat(feed.path("entries").get(0).path("isBot").asBoolean()).isTrue();

        RpcClient.ok(asBot(key, "cancelBet", betting.roundId()));
        assertThat(RpcClient.ok(asBot(key, "myPositions"))).isEmpty();

        // a cookie sent along does not change who acts
        Response both = io.restassured.RestAssured.given().contentType(io.restassured.http.ContentType.JSON)
                .cookie("herd_session", owner.cookie()).header("Authorization", "Bearer " + key)
                .body(Map.of("args", java.util.List.of())).post("/api/rpc/currentPlayer");
        assertThat(both.jsonPath().getString("playerId")).isEqualTo(botId);
    }

    @Test
    void aWrongMalformedMissingOrRevokedKeyIsRefused() throws Exception {
        Session owner = signIn("bad");
        JsonNode created = createBot(owner, "Bad Key Bot");
        String key = keyOf(created);
        String botId = botIdOf(created);

        Response wrong = asBot("herd_" + "A".repeat(43), "myAccount");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(errorCode(wrong)).isEqualTo("API_KEY_INVALID");
        assertThat(errorCode(asBot("not-a-key", "myAccount"))).isEqualTo("API_KEY_INVALID");
        assertThat(errorCode(asBot("", "myAccount"))).isEqualTo("API_KEY_INVALID");
        // no header at all: the call behaves as it always did for a visitor
        Response missing = rpc(null, "myAccount");
        assertThat(missing.statusCode()).isEqualTo(401);
        assertThat(errorCode(missing)).isEqualTo("NOT_SIGNED_IN");
        // another scheme is not ours
        Response basic = io.restassured.RestAssured.given().contentType(io.restassured.http.ContentType.JSON)
                .header("Authorization", "Basic abc").body(Map.of("args", java.util.List.of())).post("/api/rpc/myAccount");
        assertThat(errorCode(basic)).isEqualTo("NOT_SIGNED_IN");

        // a wrong key is refused even on a public call: a bot that sends a bad key hears about it
        assertThat(errorCode(asBot("herd_" + "B".repeat(43), "markets"))).isEqualTo("API_KEY_INVALID");

        // revoked
        assertThat(asBot(key, "myAccount").statusCode()).isEqualTo(200);
        JsonNode revoked = ok(owner, "revokeBotKey", botId);
        assertThat(revoked.path("keyActive").asBoolean()).isFalse();
        assertThat(revoked.path("keyHint").isNull()).isTrue();
        Response afterRevoke = asBot(key, "myAccount");
        assertThat(afterRevoke.statusCode()).isEqualTo(401);
        assertThat(errorCode(afterRevoke)).isEqualTo("API_KEY_INVALID");
        // revoking twice is harmless
        assertThat(ok(owner, "revokeBotKey", botId).path("keyActive").asBoolean()).isFalse();

        // regenerated: the new key opens the bot, the revoked and any older one do not
        JsonNode again = ok(owner, "regenerateBotKey", botId);
        String fresh = keyOf(again);
        assertThat(fresh).isNotEqualTo(key);
        assertThat(asBot(fresh, "myAccount").statusCode()).isEqualTo(200);
        assertThat(asBot(key, "myAccount").statusCode()).isEqualTo(401);
        JsonNode third = ok(owner, "regenerateBotKey", botId);
        assertThat(asBot(fresh, "myAccount").statusCode()).as("regenerating again closes the previous key").isEqualTo(401);
        assertThat(asBot(keyOf(third), "myAccount").statusCode()).isEqualTo(200);
    }

    @Test
    void ownerOnlyCallsAreRefusedWithAKey() throws Exception {
        Session owner = signIn("only");
        JsonNode created = createBot(owner, "Only Bot");
        String key = keyOf(created);
        String botId = botIdOf(created);

        for (Object[] call : new Object[][] {
                {"createBot", new Object[] {"Child Bot"}},
                {"myBots", new Object[] {}},
                {"regenerateBotKey", new Object[] {botId}},
                {"revokeBotKey", new Object[] {botId}},
                {"requestWithdrawal", new Object[] {"100.000"}},
                {"withdrawal", new Object[] {"w-1"}},
                {"requestDeposit", new Object[] {"100.000"}},
                {"myDepositAddress", new Object[] {}},
                {"setNickname", new Object[] {"Renamed Bot"}},
                {"myInvite", new Object[] {}},
                {"signOut", new Object[] {}},
                {"authChallenge", new Object[] {}},
                {"submitFeedback", new Object[] {}},
                {"acknowledgeResults", new Object[] {java.util.List.of()}},
                {"home", new Object[] {}},
                {"nonsense", new Object[] {}}}) {
            Response response = asBot(key, (String) call[0], (Object[]) call[1]);
            assertThat(response.statusCode()).as("%s: %s", call[0], response.asString()).isEqualTo(403);
            assertThat(errorCode(response)).as((String) call[0]).isEqualTo("API_CALL_NOT_ALLOWED");
        }
        assertThat(ok(owner, "myBots")).hasSize(1);
        assertThat(ok(asBot(key, "currentPlayer")).path("nickname").asText()).as("not renamed").isEqualTo("Only Bot");
    }

    @Test
    void anApiBotCannotWithdraw() throws Exception {
        Session owner = signIn("wd");
        String botId = botIdOf(createBot(owner, "Poor Bot"));

        assertThatThrownBy(() -> gameService.requestWithdrawal(botId, "100.000", System.currentTimeMillis()))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(Enums.ApiErrorCode.BOT_WITHDRAWAL_NOT_ALLOWED);
                    assertThat(e.httpStatus()).isEqualTo(403);
                    assertThat(e.getMessage()).contains("cannot withdraw");
                });
        // a person is not affected by the new check: the usual answer for a server that does not send PLAY on-chain
        assertThatThrownBy(() -> gameService.requestWithdrawal(owner.playerId(), "100.000", System.currentTimeMillis()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isNotEqualTo(Enums.ApiErrorCode.BOT_WITHDRAWAL_NOT_ALLOWED));
    }

    // ---------------------------------------------------------------------------------------
    // season pack, journal and rebuild
    // ---------------------------------------------------------------------------------------

    @Test
    void theFirstCallOfANewSeasonCreditsTheSeasonsPackLikeAPersonsSignIn() throws Exception {
        Session owner = signIn("season");
        String botId = botIdOf(createBot(owner, "Season Bot"));
        var before = gameService.balanceOf(botId).orElseThrow();

        long nextSeason = Instant.now().atZone(ZoneOffset.UTC).plusMonths(1).toInstant().toEpochMilli();
        gameService.touchApiBot(botId, nextSeason);
        var after = gameService.balanceOf(botId).orElseThrow();
        assertThat(after.compareTo(before)).as("a pack was credited").isGreaterThan(0);

        gameService.touchApiBot(botId, nextSeason + 1000);
        assertThat(gameService.balanceOf(botId).orElseThrow()).as("only once per season").isEqualTo(after);
    }

    @Test
    void theBotSurvivesARebuildAndTheJournalHasNoKey() throws Exception {
        Session owner = signIn("rebuild");
        JsonNode created = createBot(owner, "Rebuilt Bot");
        String key = keyOf(created);
        String botId = botIdOf(created);
        var balance = gameService.balanceOf(botId).orElseThrow();

        rebuild.rebuild();

        assertThat(gameService.balanceOf(botId)).as("the replay reproduces the bot's account").contains(balance);
        assertThat(RpcClient.ok(asBot(key, "currentPlayer")).path("playerId").asText()).isEqualTo(botId);
        assertThat(ok(owner, "myBots")).hasSize(1);

        // the bot's existence in the journal is the ordinary event of any player; the key (and its hash) is not in it
        Long events = db.read(c -> db.queryOne(c, "SELECT COUNT(*) FROM game_events WHERE event_type = 'PlayerRegistered' "
                + "AND payload->>'playerId' = ?", rs -> rs.getLong(1), botId));
        assertThat(events).isEqualTo(1L);
        String hash = db.read(c -> db.queryOne(c, "SELECT api_key_hash FROM players WHERE player_id = ?", rs -> rs.getString(1), botId));
        Long leaks = db.read(c -> db.queryOne(c, "SELECT COUNT(*) FROM game_events WHERE payload::text LIKE ? OR payload::text LIKE ?",
                rs -> rs.getLong(1), "%" + hash + "%", "%herd\\_%"));
        assertThat(leaks).isZero();
    }

    // ---------------------------------------------------------------------------------------
    // the bots' own board
    // ---------------------------------------------------------------------------------------

    @Test
    void theBotsHaveABoardOfTheirOwnAndThePeoplesBoardsLeaveThemOut() throws Exception {
        Market market = market("T_AB_BOARD");
        String botId = botIdOf(createBot(signIn("board"), "Board Bot"));
        String personId = signUp("boardperson");
        // the same rules as a person's: ten rounds in the season to be listed at all (Р-28)
        for (int i = 0; i < 9; i++) {
            play(market, botId, "2510.000", "2500.000", "2510.000");
            play(market, personId, "2510.000", "2500.000", "2510.000");
        }
        assertThat(ratingIds(ok(null, "botRatings", (Object) null).path("rich"))).as("nine rounds are below the threshold").isEmpty();

        play(market, botId, "2510.000", "2500.000", "2510.000");
        play(market, personId, "2510.000", "2500.000", "2510.000");

        JsonNode bots = ok(null, "botRatings", (Object) null);
        JsonNode people = ok(null, "ratings", (Object) null);
        assertThat(bots.path("roundsThreshold").asInt()).isEqualTo(people.path("roundsThreshold").asInt());
        assertThat(ratingIds(bots.path("rich"))).containsExactly(botId);
        assertThat(ratingIds(bots.path("prophets"))).containsExactly(botId);
        assertThat(bots.path("rich").get(0).path("rank").asInt()).isEqualTo(1);
        assertThat(ratingIds(people.path("rich"))).contains(personId).doesNotContain(botId);
        assertThat(ratingIds(people.path("prophets"))).contains(personId).doesNotContain(botId);
    }

    // ---------------------------------------------------------------------------------------
    // the bet's "mode" (Р-106): absent is today's percentage mode, anything unknown is refused
    // ---------------------------------------------------------------------------------------

    @Test
    void aBetWithoutAModeIsThePercentageModeAndAnUnknownModeIsRefusedNotIgnored() throws Exception {
        Market market = market("T_AB_MODE");
        seedAhead(market);
        String key = keyOf(createBot(signIn("mode"), "Mode Bot"));
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);

        Response none = asBot(key, "placeBet", Map.of("roundId", betting.roundId(), "direction", "UP", "leverage", 2, "stake", "100.000"));
        assertThat(none.statusCode()).as(none.asString()).isEqualTo(200);
        Response percent = asBot(key, "placeBet", Map.of("roundId", betting.roundId(), "direction", "UP", "leverage", 2,
                "stake", "100.000", "mode", "PERCENTAGE"));
        assertThat(percent.statusCode()).as(percent.asString()).isEqualTo(200);
        assertThat(percent.asString()).as("the answer has the same shape either way").isEqualTo(none.asString());

        Response unknown = asBot(key, "placeBet", Map.of("roundId", betting.roundId(), "direction", "UP", "leverage", 2,
                "stake", "100.000", "mode", "TURBO"));
        assertThat(unknown.statusCode()).as(unknown.asString()).isEqualTo(400);
        assertThat(errorCode(unknown)).isEqualTo("BAD_REQUEST");
        assertThat(RpcClient.ok(asBot(key, "myPositions"))).as("the refused bet changed nothing").hasSize(1);
    }

    // ---------------------------------------------------------------------------------------
    // the journal: an API bot is registered by the same event as any player
    // ---------------------------------------------------------------------------------------

    @Test
    void theJournalHoldsTheSameEventForABotAsForAPerson() throws Exception {
        Session owner = signIn("journal");
        String botId = botIdOf(createBot(owner, "Journal Bot"));
        String personEvent = db.read(c -> db.queryOne(c, "SELECT payload::text FROM game_events WHERE event_type = 'PlayerRegistered' "
                + "AND payload->>'playerId' = ?", rs -> rs.getString(1), owner.playerId()));
        String botEvent = db.read(c -> db.queryOne(c, "SELECT payload::text FROM game_events WHERE event_type = 'PlayerRegistered' "
                + "AND payload->>'playerId' = ?", rs -> rs.getString(1), botId));

        java.util.List<String> personFields = new java.util.ArrayList<>();
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(personEvent).fieldNames().forEachRemaining(personFields::add);
        java.util.List<String> botFields = new java.util.ArrayList<>();
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(botEvent).fieldNames().forEachRemaining(botFields::add);
        assertThat(botFields).containsExactlyInAnyOrderElementsOf(personFields);
    }

    private static java.util.List<String> ratingIds(JsonNode entries) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        entries.forEach(e -> ids.add(e.path("player").path("playerId").asText()));
        return ids;
    }

    private void play(Market market, String playerId, String close, String low, String high) {
        RoundWindow betting = roundProgress.bettingWindow(market.id(), Interval.HOURLY);
        gameService.placeBet(playerId, bet(betting, Enums.Direction.UP, 2, "100.000"), betting.startMs() - 1);
        prices.script(market, betting, "2500.000", close, low, high);
        scheduler.tick(betting.endMs());
    }
}
