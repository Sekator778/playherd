package crowdvsmarket.server;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.geoip.CountryLookup;
import crowdvsmarket.server.geoip.TinyMmdb;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.persistence.FeedbackRepository;
import crowdvsmarket.server.testsupport.LogCapture;
import crowdvsmarket.server.testsupport.RpcClient;
import crowdvsmarket.server.testsupport.RpcClient.Session;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Р-81: {@code submitFeedback} over real HTTP — the contract the web client codes against
 * (docs/research/server_design.md §22) — and the operator's {@code GET /api/admin/feedback}.
 *
 * <p>The application runs as it does behind nginx: {@code X-Forwarded-For} is honoured from the loopback peer a test
 * connects from, so every test speaks for an address of its own (the per-address budget is real, 5 messages, and lasts
 * the whole class). The country database is a tiny MMDB written by {@link TinyMmdb} and given as {@code
 * herd.geoip.mmdb-path}: the real lookup, the real reader, no network. {@code 192.0.2.0/24} is DE, {@code
 * 198.51.100.0/24} FR, {@code 203.0.113.0/24} JP, {@code 2001:db8::/32} BR, {@code 203.0.114.0/24} is what DB-IP calls
 * "no country" ({@code ZZ}).
 */
@QuarkusTest
@TestProfile(FeedbackRpcTest.Profile.class)
class FeedbackRpcTest {

    static Path tinyDatabase() {
        try {
            return TinyMmdb.ipv6()
                    .country("192.0.2.0/24", "DE")
                    .country("198.51.100.0/24", "FR")
                    .country("203.0.113.0/24", "JP")
                    .country("203.0.114.0/24", "ZZ")
                    .country("2001:db8::/32", "BR")
                    .writeTo(Path.of("target", "feedback-test", "tiny-country.mmdb").toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "herd.geoip.enabled", "true",
                    "herd.geoip.mmdb-path", tinyDatabase().toString(),
                    "herd.bots.count", "0",
                    "quarkus.scheduler.enabled", "false",
                    "quarkus.http.proxy.proxy-address-forwarding", "true",
                    "quarkus.http.proxy.allow-forwarded", "false",
                    "quarkus.http.proxy.allow-x-forwarded", "true",
                    "quarkus.http.proxy.trusted-proxies", "127.0.0.1");
        }
    }

    @InjectMock
    ChainSvcClient chainSvcClient;
    @Inject
    Db db;
    @Inject
    FeedbackRepository repository;
    @Inject
    CountryLookup countries;
    @ConfigProperty(name = "herd.operator.token")
    String operatorToken;

    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    /** An address nobody has used yet (198.18.0.0/15, outside the tiny database): its own bucket, no country. */
    private static String freshAddress() {
        int n = NEXT_ADDRESS.getAndIncrement();
        return "198.18." + (n / 250) + "." + (n % 250 + 1);
    }

    @BeforeEach
    void aClassOfItsOwnWithTheCountryDatabaseLoaded() {
        db.tx(c -> db.update(c, "DELETE FROM feedback"));
        // the database is loaded by a thread in the background: the first test waits for it
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
                .until(() -> countries.countryOf("192.0.2.1") != null);
    }

    // --- helpers ---

    private static Map<String, Object> request(String message, String... fieldsAndValues) {
        Map<String, Object> body = new HashMap<>();
        body.put("message", message);
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            body.put(fieldsAndValues[i], fieldsAndValues[i + 1]);
        }
        return body;
    }

    private Response submit(String address, Session session, Object request) {
        var spec = given().contentType(ContentType.JSON);
        if (address != null) {
            spec = spec.header("X-Forwarded-For", address);
        }
        if (session != null) {
            spec = spec.cookie("herd_session", session.cookie());
        }
        java.util.List<Object> args = new ArrayList<>();
        args.add(request);
        return spec.body(Map.of("args", args)).post("/api/rpc/submitFeedback");
    }

    private Response submitRaw(String address, String rawBody) {
        return given().contentType(ContentType.JSON).header("X-Forwarded-For", address).body(rawBody)
                .post("/api/rpc/submitFeedback");
    }

    private record Row(long id, long receivedAtMs, String message, String playerId, String country, String timeZone,
                       String language, String page, String client) {
    }

    private Row row(long id) {
        return db.read(c -> db.queryOne(c, "SELECT * FROM feedback WHERE id = ?",
                rs -> new Row(rs.getLong("id"), rs.getLong("received_at_ms"), rs.getString("message"),
                        rs.getString("player_id"), rs.getString("country"), rs.getString("time_zone"),
                        rs.getString("language"), rs.getString("page"), rs.getString("client")), id));
    }

    private long count(String sql, Object... params) {
        return db.read(c -> db.queryOne(c, sql, rs -> rs.getLong("n"), params));
    }

    private long accepted(String address, Session session, Object request) {
        Response response = submit(address, session, request);
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        return response.jsonPath().getLong("id");
    }

    private static void assertError(Response response, int status, String code, String messagePart) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(status);
        assertThat(response.jsonPath().getString("error.code")).isEqualTo(code);
        assertThat(response.jsonPath().getString("error.message")).contains(messagePart);
    }

    // --- the answer and what is stored ---

    @Test
    void aMessageIsStoredWithWhatTheClientSentAndTheAnswerIsItsId() {
        long before = System.currentTimeMillis();

        Response response = submit(freshAddress(), null, request("Great game, the sound is too loud.",
                "timeZone", "Europe/Berlin", "language", "de-DE", "page", "/room/BTC", "client", "web"));

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        long id = response.jsonPath().getLong("id");
        assertThat(response.asString()).as("exactly {\"id\": n}").isEqualTo("{\"id\":" + id + "}");
        Row row = row(id);
        assertThat(row.message()).isEqualTo("Great game, the sound is too loud.");
        assertThat(row.timeZone()).isEqualTo("Europe/Berlin");
        assertThat(row.language()).isEqualTo("de-DE");
        assertThat(row.page()).isEqualTo("/room/BTC");
        assertThat(row.client()).isEqualTo("web");
        assertThat(row.playerId()).isNull();
        assertThat(row.country()).as("198.18.x.y is not in the tiny database").isNull();
        assertThat(row.receivedAtMs()).isBetween(before, System.currentTimeMillis());
    }

    @Test
    void theOptionalFieldsMayBeLeftOutOrNull() {
        long onlyAMessage = accepted(freshAddress(), null, request("only words"));
        Map<String, Object> nulls = request("nulls");
        nulls.put("timeZone", null);
        nulls.put("language", null);
        nulls.put("page", null);
        nulls.put("client", null);
        long withNulls = accepted(freshAddress(), null, nulls);

        for (long id : List.of(onlyAMessage, withNulls)) {
            Row row = row(id);
            assertThat(row.timeZone()).isNull();
            assertThat(row.language()).isNull();
            assertThat(row.page()).isNull();
            assertThat(row.client()).isNull();
        }
    }

    @Test
    void aNewMessageGetsANewId() {
        long first = accepted(freshAddress(), null, request("one"));
        long second = accepted(freshAddress(), null, request("two"));

        assertThat(second).isGreaterThan(first);
        assertThat(count("SELECT COUNT(*) AS n FROM feedback")).isEqualTo(2);
    }

    // --- the message ---

    @Test
    void aBlankMessageIsRefusedWithBadRequest() {
        assertError(submit(freshAddress(), null, request("")), 400, "BAD_REQUEST", "empty");
        assertError(submit(freshAddress(), null, request("   \n\t ")), 400, "BAD_REQUEST", "empty");
        assertError(submit(freshAddress(), null, request("\u0000\u0000")), 400, "BAD_REQUEST", "empty");
        assertError(submit(freshAddress(), null, request(null)), 400, "BAD_REQUEST", "required");
        assertError(submit(freshAddress(), null, Map.of()), 400, "BAD_REQUEST", "required");
        assertThat(count("SELECT COUNT(*) AS n FROM feedback")).isZero();
    }

    @Test
    void aMessageOfMoreThanTwoThousandCharactersIsRefusedAndTwoThousandIsTaken() {
        Response tooLong = submit(freshAddress(), null, request("x".repeat(2001)));
        assertError(tooLong, 400, "BAD_REQUEST", "too long");
        assertThat(tooLong.jsonPath().getString("error.message")).contains("2000");
        long id = accepted(freshAddress(), null, request("y".repeat(2000)));

        assertThat(row(id).message()).hasSize(2000);
        assertThat(count("SELECT COUNT(*) AS n FROM feedback")).isEqualTo(1);
    }

    @Test
    void nulCharactersAreDroppedWhichAPostgresTextWouldRefuse() {
        long id = accepted(freshAddress(), null, request("  he\u0000llo\u0000  "));

        assertThat(row(id).message()).isEqualTo("hello");
    }

    @Test
    void newlinesAndUnicodeInAMessageSurviveTheTrip() {
        String text = "Первая строка\nвторая — 日本語 ✓ 😀";

        long id = accepted(freshAddress(), null, request(text));

        assertThat(row(id).message()).isEqualTo(text);
    }

    @Test
    void aRequestWithoutAMessageObjectIsABadRequestNotAServerError() {
        assertError(submitRaw(freshAddress(), "{\"args\":[]}"), 400, "BAD_REQUEST", "missing request body");
        assertError(submitRaw(freshAddress(), "{\"args\":[null]}"), 400, "BAD_REQUEST", "missing request body");
        assertError(submitRaw(freshAddress(), "{}"), 400, "BAD_REQUEST", "missing request body");
        assertError(submitRaw(freshAddress(), "{\"args\":[\"just words\"]}"), 400, "BAD_REQUEST", "invalid request body");
        assertError(submitRaw(freshAddress(), "{\"args\":[{\"message\":[1,2]}]}"), 400, "BAD_REQUEST", "invalid request body");
        assertError(submitRaw(freshAddress(), "{not json"), 400, "BAD_REQUEST", "invalid JSON body");
        assertThat(count("SELECT COUNT(*) AS n FROM feedback")).isZero();
    }

    @Test
    void aStructureWhereTextIsExpectedIsAMalformedRequestNotACleanedField() {
        assertError(submitRaw(freshAddress(), "{\"args\":[{\"message\":\"m\",\"timeZone\":{\"zone\":1}}]}"), 400, "BAD_REQUEST",
                "invalid request body");
        assertError(submitRaw(freshAddress(), "{\"args\":[{\"message\":\"m\",\"page\":[\"/a\"]}]}"), 400, "BAD_REQUEST",
                "invalid request body");
        assertThat(count("SELECT COUNT(*) AS n FROM feedback")).isZero();
    }

    @Test
    void unknownFieldsInTheRequestAreIgnored() {
        Map<String, Object> body = request("with extras");
        body.put("email", "me@example.org");
        body.put("name", "Alice");

        long id = accepted(freshAddress(), null, body);

        assertThat(row(id).message()).isEqualTo("with extras");
    }

    // --- the optional fields are cleaned, never a reason to refuse ---

    @Test
    void anOptionalFieldThatDoesNotFitIsStoredAsNullAndTheMessageIsTaken() {
        long id = accepted(freshAddress(), null, request("m", "timeZone", "z".repeat(65), "language", "l".repeat(36),
                "page", "room/BTC", "client", "desktop"));

        Row row = row(id);
        assertThat(row.message()).isEqualTo("m");
        assertThat(row.timeZone()).isNull();
        assertThat(row.language()).isNull();
        assertThat(row.page()).isNull();
        assertThat(row.client()).isNull();
    }

    @Test
    void theOptionalFieldsAtTheirLimitsAreKept() {
        long id = accepted(freshAddress(), null, request("m", "timeZone", "z".repeat(64), "language", "l".repeat(35),
                "page", "/" + "p".repeat(199), "client", "pwa"));

        Row row = row(id);
        assertThat(row.timeZone()).hasSize(64);
        assertThat(row.language()).hasSize(35);
        assertThat(row.page()).hasSize(200);
        assertThat(row.client()).isEqualTo("pwa");
    }

    @Test
    void controlCharactersTurnTheOptionalFieldIntoNull() {
        long id = accepted(freshAddress(), null, request("m", "timeZone", "Europe/\nBerlin", "language", "de\u0000",
                "page", "/a\u001bb", "client", "w\u0007eb"));

        Row row = row(id);
        assertThat(row.timeZone()).isNull();
        assertThat(row.language()).isNull();
        assertThat(row.page()).isNull();
        assertThat(row.client()).isNull();
    }

    @Test
    void theThreeClientsAreKept() {
        for (String client : List.of("web", "app", "pwa")) {
            assertThat(row(accepted(freshAddress(), null, request("m", "client", client))).client()).isEqualTo(client);
        }
    }

    // --- who sent it ---

    @Test
    void aSignedInSenderIsStoredWithTheirPlayerIdAndAnAnonymousOneWithout() {
        Session player = RpcClient.signIn(chainSvcClient, "fb");

        long signedIn = accepted(freshAddress(), player, request("from a player"));
        long anonymous = accepted(freshAddress(), null, request("from a visitor"));
        long staleSession = accepted(freshAddress(), new Session("p-nobody", "no-such-session-cookie"),
                request("from a visitor with an old cookie"));

        assertThat(row(signedIn).playerId()).isEqualTo(player.playerId());
        assertThat(row(anonymous).playerId()).isNull();
        assertThat(row(staleSession).playerId()).as("a session the server does not know is no player").isNull();
    }

    @Test
    void afterSigningOutTheNextMessageIsAnonymous() throws Exception {
        Session player = RpcClient.signIn(chainSvcClient, "fbout");
        assertThat(row(accepted(freshAddress(), player, request("in"))).playerId()).isEqualTo(player.playerId());

        RpcClient.ok(player, "signOut");

        assertThat(row(accepted(freshAddress(), player, request("out"))).playerId()).isNull();
    }

    // --- the country, from the address a proxy forwards ---

    @Test
    void theCountryComesFromTheForwardedAddress() {
        assertThat(row(accepted("192.0.2.10", null, request("a"))).country()).isEqualTo("DE");
        assertThat(row(accepted("198.51.100.10", null, request("b"))).country()).isEqualTo("FR");
        assertThat(row(accepted("203.0.113.10", null, request("c"))).country()).isEqualTo("JP");
        assertThat(row(accepted("2001:db8::5", null, request("d"))).country()).as("IPv6, as nginx forwards it").isEqualTo("BR");
        assertThat(row(accepted("192.0.2.11, 10.1.2.3", null, request("e"))).country())
                .as("the first element of the header is the visitor").isEqualTo("DE");
    }

    @Test
    void anAddressTheDatabaseDoesNotKnowOrKnowsAsNoCountryOrIsPrivateHasNoCountry() {
        assertThat(row(accepted("8.8.8.8", null, request("not in the file"))).country()).isNull();
        assertThat(row(accepted("203.0.114.10", null, request("zz"))).country()).as("DB-IP's code for none").isNull();
        assertThat(row(accepted("10.1.2.3", null, request("private"))).country()).isNull();
        assertThat(row(accepted("192.168.1.20", null, request("private too"))).country()).isNull();
        assertThat(row(accepted("fd00::7", null, request("unique local"))).country()).isNull();
    }

    @Test
    void withoutAForwardedAddressTheLoopbackPeerHasNoCountry() {
        Response response = given().contentType(ContentType.JSON)
                .body(Map.of("args", List.of(request("straight to the server")))).post("/api/rpc/submitFeedback");

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        assertThat(row(response.jsonPath().getLong("id")).country()).isNull();
    }

    @Test
    void theTableHasNoColumnForAnAddress() {
        List<String> columns = db.read(c -> db.queryList(c, "SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'feedback' ORDER BY ordinal_position", rs -> rs.getString("column_name")));

        assertThat(columns).containsExactly("id", "received_at_ms", "message", "player_id", "country", "time_zone",
                "language", "page", "client");
    }

    // --- the address and the text are not logged, the address is not returned ---

    @Test
    void neitherTheAddressNorTheTextAreLoggedAndTheAddressIsNotReturned() {
        Response response;
        List<String> logged;
        try (LogCapture log = LogCapture.start()) {
            response = submit("198.51.100.77", null, request("SECRET-WORDS-OF-THE-SENDER", "page", "/room/BTC"));
            logged = List.copyOf(log.lines());
        }

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        assertThat(response.asString()).doesNotContain("198.51.100.77");
        assertThat(response.headers().asList().toString()).doesNotContain("198.51.100.77");
        assertThat(logged).as("the one line of the accepted message").anyMatch(
                line -> line.contains("feedback") && line.contains("FR") && line.contains("26 characters"));
        assertThat(logged).noneMatch(line -> line.contains("198.51.100.77"));
        assertThat(logged).noneMatch(line -> line.contains("SECRET-WORDS"));
    }

    // --- the budget of one address ---

    @Test
    void anAddressMayLeaveFiveMessagesInABurstAndTheSixthIsRateLimited() {
        String address = freshAddress();
        for (int i = 1; i <= 5; i++) {
            accepted(address, null, request("message " + i));
        }

        Response sixth = submit(address, null, request("message 6"));

        assertError(sixth, 429, "RATE_LIMITED", "too many requests");
        assertThat(count("SELECT COUNT(*) AS n FROM feedback WHERE message = ?", "message 6")).isZero();
        assertThat(count("SELECT COUNT(*) AS n FROM feedback")).isEqualTo(5);

        // another address has a bucket of its own — and so does the same address for the other limited methods
        accepted(freshAddress(), null, request("someone else"));
        Response challenge = given().contentType(ContentType.JSON).header("X-Forwarded-For", address)
                .body(Map.of("args", List.of())).post("/api/rpc/authChallenge");
        assertThat(challenge.statusCode()).as(challenge.asString()).isEqualTo(200);
    }

    @Test
    void theBudgetIsTakenBeforeTheMessageIsLookedAtSoRefusedRequestsCountToo() {
        String address = freshAddress();
        for (int i = 1; i <= 5; i++) {
            assertThat(submit(address, null, request("  ")).statusCode()).isEqualTo(400);
        }

        assertError(submit(address, null, request("a perfectly good message")), 429, "RATE_LIMITED", "too many");
    }

    @Test
    void feedbackHasNoEffectOnTheBudgetOfTheOtherLimitedMethods() {
        String address = freshAddress();
        for (int i = 1; i <= 6; i++) {
            submit(address, null, request("flood " + i));
        }

        for (int i = 0; i < 3; i++) {
            Response challenge = given().contentType(ContentType.JSON).header("X-Forwarded-For", address)
                    .body(Map.of("args", List.of())).post("/api/rpc/authChallenge");
            assertThat(challenge.statusCode()).as(challenge.asString()).isEqualTo(200);
        }
    }

    // --- the operator's listing ---

    private Response listing(String token, String query) {
        var spec = given();
        if (token != null) {
            spec = spec.header("Authorization", token);
        }
        return spec.get("/api/admin/feedback" + (query == null ? "" : "?" + query));
    }

    private Response asOperator(String query) {
        return listing("Bearer " + operatorToken, query);
    }

    @Test
    void theListingNeedsTheOperatorToken() {
        assertThat(listing(null, null).statusCode()).isEqualTo(401);
        assertThat(listing("Bearer wrong-token", null).statusCode()).isEqualTo(401);
        assertThat(listing(operatorToken, null).statusCode()).as("no Bearer prefix").isEqualTo(401);
        assertThat(listing("Basic " + operatorToken, null).statusCode()).isEqualTo(401);
        assertThat(asOperator(null).statusCode()).isEqualTo(200);
    }

    @Test
    void theListingIsNewestFirstWithTheNicknameAndTheRoundsPlayedOfASignedInSender() throws Exception {
        Session player = RpcClient.signIn(chainSvcClient, "fbl");
        RpcClient.ok(player, "setNickname", "Feedback Fan");
        for (int i = 1; i <= 3; i++) {
            final int n = i;
            db.tx(c -> db.update(c, "INSERT INTO player_round_history (round_id, player_id, interval, settled_at_ms, "
                    + "direction, leverage, stake_mojos, liquidated, direction_correct, payout_mojos) "
                    + "VALUES (?, ?, 'HOURLY', ?, 'UP', 2, 1000, FALSE, TRUE, 2000)", "fb-round-" + n, player.playerId(),
                    1_000L * n));
        }
        long base = System.currentTimeMillis() + 10L * 365 * 24 * 3_600_000L; // newer than anything a test writes with the real clock
        long oldest = repository.insert(base + 1, new FeedbackRepository.NewFeedback("oldest, anonymous", null, "DE",
                "Europe/Berlin", "de-DE", "/lobby", "web"));
        long middle = repository.insert(base + 2, new FeedbackRepository.NewFeedback("middle, nothing known", null, null,
                null, null, null, null));
        long newestFirstWritten = repository.insert(base + 3, new FeedbackRepository.NewFeedback("newest, a player",
                player.playerId(), "FR", "Asia/Tokyo", "ja", "/room/ETH", "app"));
        long newestSecondWritten = repository.insert(base + 3, new FeedbackRepository.NewFeedback("same instant, later id",
                null, "JP", null, null, null, "pwa"));

        Response response = asOperator("sinceMs=" + base);

        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        List<Map<String, Object>> items = response.jsonPath().getList("$");
        assertThat(items).extracting(item -> ((Number) item.get("id")).longValue())
                .containsExactly(newestSecondWritten, newestFirstWritten, middle, oldest);
        assertThat(items.get(0).keySet()).containsExactlyInAnyOrder("id", "receivedAtMs", "message", "country", "timeZone",
                "language", "page", "client", "playerId", "nickname", "roundsPlayed");

        Map<String, Object> player3 = items.get(1);
        assertThat(player3).containsEntry("message", "newest, a player").containsEntry("country", "FR")
                .containsEntry("timeZone", "Asia/Tokyo").containsEntry("language", "ja").containsEntry("page", "/room/ETH")
                .containsEntry("client", "app").containsEntry("playerId", player.playerId())
                .containsEntry("nickname", "Feedback Fan").containsEntry("roundsPlayed", 3);
        assertThat(((Number) player3.get("receivedAtMs")).longValue()).isEqualTo(base + 3);

        Map<String, Object> anonymous = items.get(3);
        assertThat(anonymous).containsEntry("message", "oldest, anonymous").containsEntry("country", "DE")
                .containsEntry("playerId", null).containsEntry("nickname", null).containsEntry("roundsPlayed", null);

        Map<String, Object> nothing = items.get(2);
        assertThat(nothing).containsEntry("country", null).containsEntry("timeZone", null).containsEntry("language", null)
                .containsEntry("page", null).containsEntry("client", null);
    }

    @Test
    void aSignedInSenderWithNoNicknameAndNoRoundsHasNullAndZeroAndOneWhoseRowIsGoneStillShows() throws Exception {
        Session player = RpcClient.signIn(chainSvcClient, "fbz");
        long base = System.currentTimeMillis() + 11L * 365 * 24 * 3_600_000L;
        repository.insert(base, new FeedbackRepository.NewFeedback("fresh player", player.playerId(), null, null, null,
                null, null));
        repository.insert(base + 1, new FeedbackRepository.NewFeedback("a player who is gone", "p-ghost", null, null, null,
                null, null));

        List<Map<String, Object>> items = asOperator("sinceMs=" + base).jsonPath().getList("$");

        assertThat(items).hasSize(2);
        assertThat(items.get(1)).containsEntry("playerId", player.playerId()).containsEntry("nickname", null)
                .containsEntry("roundsPlayed", 0);
        assertThat(items.get(0)).containsEntry("playerId", "p-ghost").containsEntry("nickname", null)
                .containsEntry("roundsPlayed", 0);
    }

    @Test
    void theListingTakesASinceAndALimitAndAnEmptyAnswerIsAnEmptyList() {
        long base = System.currentTimeMillis() + 12L * 365 * 24 * 3_600_000L;
        for (int i = 0; i < 5; i++) {
            repository.insert(base + i, new FeedbackRepository.NewFeedback("m" + i, null, null, null, null, null, null));
        }

        assertThat(messages(asOperator("sinceMs=" + base))).containsExactly("m4", "m3", "m2", "m1", "m0");
        assertThat(messages(asOperator("sinceMs=" + (base + 2)))).as("since is inclusive").containsExactly("m4", "m3", "m2");
        assertThat(messages(asOperator("sinceMs=" + base + "&limit=2"))).as("the newest two").containsExactly("m4", "m3");
        assertThat(messages(asOperator("limit=2&sinceMs=" + (base + 4)))).containsExactly("m4");
        assertThat(messages(asOperator("sinceMs=" + (base + 5)))).isEmpty();
        assertThat(asOperator("sinceMs=" + (base + 5)).asString()).isEqualTo("[]");
        assertThat(messages(asOperator("sinceMs=" + base + "&limit=0"))).as("0 is the default, 200").hasSize(5);
        assertThat(messages(asOperator("sinceMs=" + base + "&limit=-1"))).hasSize(5);
        assertThat(messages(asOperator("sinceMs=" + base + "&limit=100000"))).as("clamped to 1000").hasSize(5);
        assertThat(messages(asOperator("sinceMs=-5&limit=1"))).as("a negative since is all").containsExactly("m4");
        assertThat(messages(asOperator("sinceMs=&limit="))).as("empty values are absent").isNotEmpty();
    }

    @Test
    void aSinceOrALimitThatIsNotANumberIsABadRequest() {
        assertError(asOperator("sinceMs=yesterday"), 400, "BAD_REQUEST", "sinceMs");
        assertError(asOperator("limit=many"), 400, "BAD_REQUEST", "limit");
        assertError(asOperator("sinceMs=1.5"), 400, "BAD_REQUEST", "sinceMs");
    }

    @Test
    void aMessageSubmittedOverRpcShowsUpInTheListingWithItsCountryAndPlayer() throws Exception {
        Session player = RpcClient.signIn(chainSvcClient, "fbe2e");
        RpcClient.ok(player, "setNickname", "End To End");
        long id = accepted("203.0.113.44", player, request("end to end", "timeZone", "Asia/Tokyo", "language", "ja-JP",
                "page", "/profile", "client", "app"));

        List<Map<String, Object>> items = asOperator("limit=1000").jsonPath().getList("$");

        Map<String, Object> mine = items.stream().filter(item -> ((Number) item.get("id")).longValue() == id).findFirst()
                .orElseThrow();
        assertThat(mine).containsEntry("message", "end to end").containsEntry("country", "JP")
                .containsEntry("timeZone", "Asia/Tokyo").containsEntry("language", "ja-JP").containsEntry("page", "/profile")
                .containsEntry("client", "app").containsEntry("playerId", player.playerId())
                .containsEntry("nickname", "End To End").containsEntry("roundsPlayed", 0);
    }

    private static List<String> messages(Response response) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
        return response.jsonPath().getList("message");
    }
}
