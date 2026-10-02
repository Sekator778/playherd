package crowdvsmarket.server.testsupport;

import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The wire fixtures: {@code web/src/api/wire/<name>.json}, one file per RPC answer, each
 * {@code {"method": "<rpc>", "args": [...], "response": <the server's JSON answer>}} — and, on an error, a
 * {@code "status"} with the HTTP status between {@code args} and {@code response}. They are what the web tests run
 * {@code RealApi} and the pages over, instead of over what contract.ts says the server should say (parity review,
 * finding 14: 1331 green web tests said nothing about the real wire). The server's own test ({@code
 * WireFixturesTest}) is their only writer and their guard:
 *
 * <ul>
 *   <li><b>Guard (the default).</b> The live answer is compared with the committed file by SHAPE ({@link
 *       WireShape}: the same keys, recursively, and the same JSON type per key; {@code null} matches anything, an
 *       array is the union of its elements) and the test fails on any difference, saying where and how to regenerate.
 *       The method, the HTTP status and — for an error — the error code are compared exactly; the args by shape.
 *   <li><b>Writer ({@code -Dherd.wire.write=true}).</b> The file is written instead: pretty-printed (two spaces, one
 *       element per line), the keys in the order the server wrote them (which is the order of the DTO's components, so
 *       stable), a newline at the end.
 * </ul>
 *
 * Ids, addresses and timestamps change from run to run and are no concern of the guard; a regenerated file differs
 * from the committed one in those and in nothing else, unless the wire changed.
 */
public final class WireFixtures {

    /** Where the files live, relative to the module the tests run in ({@code server/}) — the same convention as
     *  {@link ContractTs#CONTRACT}. */
    public static final Path DIR = Path.of("../web/src/api/wire");

    /** The property that switches the test from guarding to writing. */
    public static final String WRITE_PROPERTY = "herd.wire.write";

    /** The property that points the test at another folder than {@link #DIR} — to try the guard on a copy of the
     *  files that was changed on purpose, or to regenerate into a scratch folder and diff. */
    public static final String DIR_PROPERTY = "herd.wire.dir";

    /** What to run to regenerate the files, said in every failure. */
    static final String REGENERATE = "mvn -B -s ~/.m2/settings-oss.xml -f pom.xml -pl server -am test "
            + "-Dtest=WireFixturesTest -Dherd.wire.write=true -Dsurefire.failIfNoSpecifiedTests=false";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;
    private final boolean write;
    private final List<String> problems = new ArrayList<>();
    private final List<String> written = new ArrayList<>();

    public WireFixtures(Path dir, boolean write) {
        this.dir = dir;
        this.write = write;
    }

    /** The fixtures of the web client's folder ({@link #DIR}, or the folder {@link #DIR_PROPERTY} names), in the
     *  mode the system property asks for. */
    public static WireFixtures fromSystemProperties() {
        String dir = System.getProperty(DIR_PROPERTY);
        return new WireFixtures(dir == null || dir.isBlank() ? DIR : Path.of(dir), Boolean.getBoolean(WRITE_PROPERTY));
    }

    public boolean writing() {
        return write;
    }

    private Path fileOf(String name) {
        return dir.resolve(name + ".json");
    }

    /**
     * Handles one answer: writes the file, or compares the answer with it and remembers every difference (so one run
     * reports all of them; {@link #failOnProblems} fails).
     *
     * @param status the HTTP status of the answer; 200 is not written, anything else is
     * @param body   the answer's JSON body, as the server sent it
     */
    public void handle(String name, String method, List<?> args, int status, JsonNode body) {
        ObjectNode file = JSON.createObjectNode();
        file.put("method", method);
        file.set("args", JSON.valueToTree(args));
        if (status != 200) {
            file.put("status", status);
        }
        file.set("response", body);
        if (write) {
            write(name, file);
        } else {
            compare(name, file);
        }
    }

    private void write(String name, ObjectNode file) {
        try {
            Files.createDirectories(dir);
            Files.writeString(fileOf(name), pretty(file) + "\n", StandardCharsets.UTF_8);
            written.add(name);
        } catch (IOException e) {
            problems.add(fileOf(name) + " could not be written: " + e);
        }
    }

    /** {@code node} as the fixtures print it: two-space indent, one array element per line, {@code "key": value}. */
    static String pretty(JsonNode node) {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"))
                .withSeparators(Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                        .withObjectEmptySeparator("")
                        .withArrayEmptySeparator(""));
        try {
            return JSON.writer(printer).writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void compare(String name, ObjectNode live) {
        Path path = fileOf(name);
        if (!Files.exists(path)) {
            problems.add(path + " is missing — the guard has nothing to compare the server's answer with");
            return;
        }
        JsonNode committed;
        try {
            committed = JSON.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            problems.add(path + " is not valid JSON: " + e.getMessage());
            return;
        }
        List<String> differences = new ArrayList<>();
        if (!committed.path("method").asText().equals(live.path("method").asText())) {
            differences.add("$.method: the committed file says \"" + committed.path("method").asText()
                    + "\", the test calls \"" + live.path("method").asText() + "\"");
        }
        int committedStatus = committed.path("status").asInt(200);
        int liveStatus = live.path("status").asInt(200);
        if (committedStatus != liveStatus) {
            differences.add("$.status: the committed file says " + committedStatus + ", the server answered "
                    + liveStatus);
        }
        String committedCode = committed.path("response").path("error").path("code").asText("");
        String liveCode = live.path("response").path("error").path("code").asText("");
        if (!committedCode.equals(liveCode)) {
            differences.add("$.response.error.code: the committed file says \"" + committedCode
                    + "\", the server answered \"" + liveCode + "\"");
        }
        // the args are positional — a tuple, not a list of alike elements: same count, each place the same shape
        JsonNode committedArgs = committed.path("args");
        JsonNode liveArgs = live.path("args");
        if (committedArgs.size() != liveArgs.size()) {
            differences.add("$.args: the committed file has " + committedArgs.size() + " argument(s), the test passes "
                    + liveArgs.size());
        } else {
            for (int i = 0; i < liveArgs.size(); i++) {
                differences.addAll(prefixed("$.args[" + i + "]", WireShape.differences(
                        WireShape.of(committedArgs.get(i)), WireShape.of(liveArgs.get(i)))));
            }
        }
        differences.addAll(prefixed("$.response", WireShape.differences(WireShape.of(committed.path("response")),
                WireShape.of(live.path("response")))));
        if (!differences.isEmpty()) {
            problems.add(path + " is not what the server answers now:\n      " + String.join("\n      ", differences));
        }
    }

    /** {@code differences} of a shape rooted at {@code $}, re-rooted at {@code root} ({@code $.response.…}). */
    private static List<String> prefixed(String root, List<String> differences) {
        return differences.stream().map(d -> root + d.substring(1)).toList();
    }

    /** The names written so far (a writing run). */
    public List<String> written() {
        return List.copyOf(written);
    }

    /** What is wrong so far, one entry per file. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Fails the test when any file was missing, different or not writable. */
    public void failOnProblems() {
        if (problems.isEmpty()) {
            return;
        }
        fail("The wire fixtures (" + dir + ") are not what the server answers now — the web tests run over those "
                + "files, so they would go on describing a wire that is gone.%n    %s%n"
                + "If the change is intended, regenerate them and commit them together with it (review the diff: "
                + "only ids, addresses and times should differ besides your change):%n    %s",
                String.join("\n    ", problems), REGENERATE);
    }
}
