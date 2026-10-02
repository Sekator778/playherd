package crowdvsmarket.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The files of the wire fixtures and how the guard treats them ({@link WireFixtures}): what the writer prints, what
 * the guard accepts, and the message of every way it fails — the method, the HTTP status, an error code, a key, a
 * type, a file that is not there.
 */
class WireFixturesFilesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(text, e);
        }
    }

    private String read(String name) throws IOException {
        return Files.readString(dir.resolve(name + ".json"));
    }

    private void writeOnce(String name, String method, List<?> args, int status, String body) {
        WireFixtures writer = new WireFixtures(dir, true);
        writer.handle(name, method, args, status, json(body));
        assertThat(writer.problems()).isEmpty();
    }

    @Test
    void theWriterPrintsTheFileTheWebTestsReadAndEndsItWithANewline() throws IOException {
        writeOnce("myPositions", "myPositions", List.of(), 200, "[{\"marketId\":\"BTC\",\"size\":null,\"stake\":\"100.000\"}]");

        assertThat(read("myPositions")).isEqualTo("""
                {
                  "method": "myPositions",
                  "args": [],
                  "response": [
                    {
                      "marketId": "BTC",
                      "size": null,
                      "stake": "100.000"
                    }
                  ]
                }
                """);
    }

    @Test
    void anErrorFileCarriesItsStatusBetweenTheArgsAndTheResponseAndKeepsTheKeysInTheServersOrder() throws IOException {
        writeOnce("error.marketNotFound", "referencePrice", List.of("NOPE"), 404,
                "{\"error\":{\"code\":\"MARKET_NOT_FOUND\",\"message\":\"unknown market NOPE\"}}");

        assertThat(read("error.marketNotFound")).isEqualTo("""
                {
                  "method": "referencePrice",
                  "args": [
                    "NOPE"
                  ],
                  "status": 404,
                  "response": {
                    "error": {
                      "code": "MARKET_NOT_FOUND",
                      "message": "unknown market NOPE"
                    }
                  }
                }
                """);
    }

    @Test
    void emptyArraysAndObjectsAreWrittenCompactlyAndArgsKeepTheirNulls() throws IOException {
        writeOnce("rounds.hourly", "rounds", java.util.Arrays.asList("HOURLY", null), 200,
                "{\"positions\":[],\"extra\":{}}");

        assertThat(read("rounds.hourly")).contains("\"args\": [\n    \"HOURLY\",\n    null\n  ]")
                .contains("\"positions\": []").contains("\"extra\": {}");
    }

    @Test
    void theGuardAcceptsAnAnswerOfTheSameShapeWhateverItsValues() {
        writeOnce("myAccount", "myAccount", List.of(), 200, "{\"playerId\":\"p1\",\"balance\":\"10000.000\",\"inPlay\":null}");

        WireFixtures guard = new WireFixtures(dir, false);
        guard.handle("myAccount", "myAccount", List.of(), 200,
                json("{\"playerId\":\"other\",\"balance\":\"5.500\",\"inPlay\":\"100.000\"}"));

        assertThat(guard.problems()).isEmpty();
        guard.failOnProblems();
    }

    @Test
    void theGuardFailsOnAKeyAndATypeSayingWhichFileWhereAndHowToRegenerate() {
        writeOnce("myAccount", "myAccount", List.of(), 200, "{\"playerId\":\"p1\",\"balance\":\"10000.000\"}");

        WireFixtures guard = new WireFixtures(dir, false);
        guard.handle("myAccount", "myAccount", List.of(), 200,
                json("{\"playerId\":\"p1\",\"balance\":10000,\"currentSeason\":\"2026-10\"}"));

        assertThat(guard.problems()).hasSize(1);
        assertThatThrownBy(guard::failOnProblems)
                .hasMessageContaining(dir.resolve("myAccount.json").toString())
                .hasMessageContaining("$.response.balance: the committed file has string, the live answer has number")
                .hasMessageContaining("$.response.currentSeason: in the live answer, not in the committed file")
                .hasMessageContaining("-Dherd.wire.write=true")
                .hasMessageContaining("-Dtest=WireFixturesTest");
    }

    @Test
    void theGuardFailsOnAMissingFile() {
        WireFixtures guard = new WireFixtures(dir, false);
        guard.handle("home", "home", List.of(), 200, json("{}"));

        assertThatThrownBy(guard::failOnProblems).hasMessageContaining("home.json is missing");
    }

    @Test
    void theMethodTheStatusAndAnErrorCodeAreComparedExactly() {
        writeOnce("error.notSignedIn", "myAccount", List.of(), 401,
                "{\"error\":{\"code\":\"NOT_SIGNED_IN\",\"message\":\"not signed in\"}}");

        WireFixtures guard = new WireFixtures(dir, false);
        guard.handle("error.notSignedIn", "myPositions", List.of(), 403,
                json("{\"error\":{\"code\":\"FORBIDDEN\",\"message\":\"nope\"}}"));

        assertThat(guard.problems()).hasSize(1);
        String problem = guard.problems().get(0);
        assertThat(problem).contains("$.method: the committed file says \"myAccount\", the test calls \"myPositions\"")
                .contains("$.status: the committed file says 401, the server answered 403")
                .contains("$.response.error.code: the committed file says \"NOT_SIGNED_IN\", the server answered \"FORBIDDEN\"");
    }

    @Test
    void aSuccessThatTurnsIntoAnErrorIsCaughtByTheStatusAlone() {
        writeOnce("myAccount", "myAccount", List.of(), 200, "{\"balance\":\"1.000\"}");

        WireFixtures guard = new WireFixtures(dir, false);
        guard.handle("myAccount", "myAccount", List.of(), 503,
                json("{\"balance\":\"2.000\"}"));

        assertThat(guard.problems().get(0)).contains("$.status: the committed file says 200, the server answered 503");
    }

    @Test
    void theArgsAreComparedByShapeSoARoundIdThatChangesEveryRunIsNoDifference() {
        writeOnce("round.running", "round", List.of("WPR-1h-1759400000000"), 200, "{\"phase\":\"RUNNING\"}");

        WireFixtures guard = new WireFixtures(dir, false);
        guard.handle("round.running", "round", List.of("WPR-1h-1759400060000"), 200, json("{\"phase\":\"RUNNING\"}"));
        assertThat(guard.problems()).isEmpty();

        guard.handle("round.running", "round", List.of("WPR-1h-1", "an-argument-more"), 200,
                json("{\"phase\":\"RUNNING\"}"));
        assertThat(guard.problems()).hasSize(1);
        assertThat(guard.problems().get(0)).contains("$.args: the committed file has 1 argument(s), the test passes 2");

        guard.handle("round.running", "round", List.of(7), 200, json("{\"phase\":\"RUNNING\"}"));
        assertThat(guard.problems()).hasSize(2);
        assertThat(guard.problems().get(1))
                .contains("$.args[0]: the committed file has string, the live answer has number");
    }

    @Test
    void theWriterReportsWhatItWroteAndAGuardWritesNothing() throws IOException {
        WireFixtures writer = new WireFixtures(dir, true);
        writer.handle("a", "a", List.of(), 200, json("{}"));
        writer.handle("b", "b", List.of(), 200, json("{}"));

        assertThat(writer.written()).containsExactly("a", "b");
        assertThat(writer.writing()).isTrue();

        WireFixtures guard = new WireFixtures(dir, false);
        guard.handle("c", "c", List.of(), 200, json("{}"));
        assertThat(guard.written()).isEmpty();
        assertThat(Files.exists(dir.resolve("c.json"))).isFalse();
    }

    @Test
    void theFilesOfTheTreeAreExactlyTheNamesTheWebLoaderListsAndTheServerWrites() throws IOException {
        Path loader = Path.of("../web/src/api/wireSource.ts");
        assertThat(Files.exists(loader)).as("missing %s — run from a full checkout", loader).isTrue();
        java.util.regex.Matcher list = java.util.regex.Pattern.compile("WIRE_NAMES\\s*=\\s*\\[([^\\]]*)\\]")
                .matcher(Files.readString(loader));
        assertThat(list.find()).as("`WIRE_NAMES = [...]` in wireSource.ts").isTrue();
        java.util.Set<String> listed = new java.util.TreeSet<>();
        java.util.regex.Matcher name = java.util.regex.Pattern.compile("\"([^\"]+)\"").matcher(list.group(1));
        while (name.find()) {
            listed.add(name.group(1));
        }
        assertThat(listed).as("the names wireSource.ts lists").hasSizeGreaterThanOrEqualTo(26);

        java.util.Set<String> written = new java.util.TreeSet<>();
        if (Files.isDirectory(WireFixtures.DIR)) {
            try (var files = Files.list(WireFixtures.DIR)) {
                files.map(f -> f.getFileName().toString()).filter(f -> f.endsWith(".json"))
                        .forEach(f -> written.add(f.substring(0, f.length() - ".json".length())));
            }
        }
        assertThat(written).as("the files in web/src/api/wire/ and the names web/src/api/wireSource.ts loads: a name "
                + "the web loads that the server writes no file for makes the web tests fail on a missing file; a file "
                + "nobody loads is dead weight — add the capture to WireFixturesTest and regenerate").isEqualTo(listed);
    }

    @Test
    void theFilesOfTheTreeAreWhatTheWebLoaderReads() throws IOException {
        // the committed fixtures, whichever are there, have the keys web/src/api/wireSource.ts reads
        Path committed = WireFixtures.DIR;
        if (!Files.isDirectory(committed)) {
            return; // not generated yet: WireFixturesTest says so
        }
        try (var files = Files.list(committed)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                JsonNode node = JSON.readTree(Files.readString(file));
                assertThat(node.fieldNames()).toIterable().as(file.getFileName().toString())
                        .contains("method", "args", "response");
                assertThat(node.get("args").isArray()).as(file + " args").isTrue();
                assertThat(Files.readString(file)).as(file + " ends with a newline").endsWith("}\n");
                if (file.getFileName().toString().startsWith("error.")) {
                    assertThat(node.path("status").asInt()).as(file + " status").isGreaterThanOrEqualTo(400);
                    assertThat(node.path("response").path("error").path("code").asText()).as(file + " code").isNotEmpty();
                } else {
                    assertThat(node.has("status")).as(file + " has no status on a success").isFalse();
                }
            }
        }
    }
}
