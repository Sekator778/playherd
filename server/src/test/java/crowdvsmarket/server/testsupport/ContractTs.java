package crowdvsmarket.server.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code web/src/api/contract.ts} as the server's tests read it: the string-literal unions it declares. The
 * server and the web client are two programs that must agree on every word of those unions; the tests that hold
 * the two sides together ({@code ApiErrorCodeContractTest}, {@code ContractUnionsTest}) read them here.
 *
 * <p><b>What is read.</b> The file is first stripped of its comments — block comments and {@code //} line comments,
 * which carry {@code ;}, quotes and whole sentences of prose that a pattern for the declarations must not trip
 * over (the pattern the first guard used stopped at the first {@code ;} of a doc comment inside the union, and
 * cut {@code ApiErrorCode} short) — and then every run of two or more string literals joined by {@code |} is a
 * union:
 * <ul>
 *   <li>{@code export type Interval = "HOURLY" | "DAILY";}, also in the multi-line form with a leading {@code |} —
 *       keyed by the name of the type: {@code Interval};
 *   <li>a field {@code status: "ACTIVE" | "CLOSED";} or {@code lastOutcome?: "WIN" | … | null;} of an interface —
 *       keyed {@code Interface.field}: {@code MarketDto.status}. {@code | null} and {@code | undefined} are not
 *       members. A field of an object type written inline inside an interface belongs to that interface.
 * </ul>
 * A single literal ({@code phase: "BETTING"}) is a discriminant, not a union, and is not listed.
 *
 * <p>Same module-relative path convention as {@code ChainSvcContractFixturesTest}: web/ and server/ are siblings
 * under the repository root and Surefire's working directory is this module's {@code server/}.
 */
public final class ContractTs {

    public static final Path CONTRACT = Path.of("../web/src/api/contract.ts");

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    /** {@code //} at the start of a line or after white space — not the one in {@code "https://…"}. */
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)(^|\\s)//.*$");
    private static final Pattern DECLARATION =
            Pattern.compile("(?:export\\s+)?(?:interface|class)\\s+(\\w+)|export\\s+type\\s+(\\w+)\\s*=");
    private static final Pattern UNION = Pattern.compile(
            "(?:export\\s+type\\s+(\\w+)\\s*=\\s*|(\\w+)\\??\\s*:\\s*)\\|?\\s*(\"[^\"\\n]*\"(?:\\s*\\|\\s*\"[^\"\\n]*\")+)");
    private static final Pattern LITERAL = Pattern.compile("\"([^\"\\n]*)\"");

    private ContractTs() {
    }

    /** The text of contract.ts. */
    public static String source() throws IOException {
        assertThat(Files.exists(CONTRACT))
                .as("missing %s — the test reads the web client's contract, run it from a full checkout", CONTRACT)
                .isTrue();
        return Files.readString(CONTRACT);
    }

    /** {@code source} without its comments. */
    public static String withoutComments(String source) {
        String noBlocks = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        return LINE_COMMENT.matcher(noBlocks).replaceAll("$1");
    }

    /** Every union of the file, by key (see the class javadoc), members in the order they are written. */
    public static Map<String, List<String>> stringUnions() throws IOException {
        return stringUnions(source());
    }

    /** {@link #stringUnions()} of the given text. */
    public static Map<String, List<String>> stringUnions(String source) {
        String text = withoutComments(source);
        List<int[]> positions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Matcher declaration = DECLARATION.matcher(text);
        while (declaration.find()) {
            positions.add(new int[] {declaration.start()});
            names.add(declaration.group(1) != null ? declaration.group(1) : declaration.group(2));
        }
        Map<String, List<String>> unions = new LinkedHashMap<>();
        Matcher union = UNION.matcher(text);
        while (union.find()) {
            String key = union.group(1);
            if (key == null) {
                String owner = null;
                for (int i = 0; i < positions.size() && positions.get(i)[0] <= union.start(); i++) {
                    owner = names.get(i);
                }
                key = (owner == null ? "?" : owner) + "." + union.group(2);
            }
            List<String> members = new ArrayList<>();
            Matcher literal = LITERAL.matcher(union.group(3));
            while (literal.find()) {
                members.add(literal.group(1));
            }
            List<String> previous = unions.put(key, members);
            assertThat(previous).as("contract.ts declares the union `%s` twice", key).isNull();
        }
        return unions;
    }

    /** The members of one union; fails with the keys there are when it is not in the file. */
    public static List<String> union(String key) throws IOException {
        Map<String, List<String>> unions = stringUnions();
        assertThat(unions).as("`%s` in contract.ts — the string-literal unions it declares", key).containsKey(key);
        return unions.get(key);
    }
}
