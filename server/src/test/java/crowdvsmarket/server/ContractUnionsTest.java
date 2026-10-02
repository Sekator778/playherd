package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.config.GameMode;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.markets.Category;
import crowdvsmarket.server.prices.HyperliquidPriceSource;
import crowdvsmarket.server.prices.PolymarketPriceSource;
import crowdvsmarket.server.prices.PricesConfig;
import crowdvsmarket.server.prices.SyntheticPriceSource;
import crowdvsmarket.server.testsupport.ContractTs;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The word lists the server and the web client share must not drift apart silently (parity review, finding 12:
 * the ledger had a type, {@code OPERATOR_CORRECTION}, that the wallet had no label for, and nothing noticed).
 * {@code ApiErrorCodeContractTest} did this for the error codes alone; this does it for EVERY string-literal union
 * of web/src/api/contract.ts that has a Java side — the ledger entry types, the withdrawal states, the intervals,
 * the directions, the round phases, the market kinds and statuses, the categories, the crowd's outcomes, the price
 * sources, the modes: a value on one side only fails the test, saying which side it is on and what to do.
 *
 * <p>Three things are held, each with a message that names the union and the Java side:
 * <ol>
 *   <li><b>Every pairing is exact.</b> The members of the union are exactly the values of each Java enum (or list)
 *       it is registered with in {@link #PAIRINGS}, but for the ones a side lists as its own on purpose — a
 *       position is only ever {@code BETTING} or {@code RUNNING}, though a round has two more phases.
 *   <li><b>Nothing is left unregistered.</b> Every union contract.ts declares is registered (a new union without
 *       a Java side to hold it against fails here until someone decides what that side is), and every pairing names
 *       a union that is still there (a renamed one fails instead of silently guarding nothing).
 *   <li><b>No Java enum is forgotten.</b> Every enum nested in {@link Enums} is either in a pairing or listed in
 *       {@link #SERVER_ONLY_ENUMS} with the reason contract.ts has no union for it.
 * </ol>
 * The error codes are {@code ApiErrorCodeContractTest}'s (it also holds the factories and the HTTP statuses).
 *
 * <p>The words are read by {@link ContractTs}; the test needs a full checkout (web/ next to server/), like the
 * contract tests of the chain service.
 */
class ContractUnionsTest {

    /** A set of values on the Java side, with the ones it may have although contract.ts' union does not. */
    private record Side(String java, Set<String> values, Set<String> javaOnly) {

        Side javaOnly(String... allowed) {
            return new Side(java, values, new TreeSet<>(Set.of(allowed)));
        }
    }

    private record Pairing(String key, List<Side> sides) {
    }

    private static <E extends Enum<E>> Side enumSide(Class<E> type) {
        return enumSide(type, E::name);
    }

    private static <E extends Enum<E>> Side enumSide(Class<E> type, Function<E, String> word) {
        Set<String> values = new LinkedHashSet<>();
        for (E constant : type.getEnumConstants()) {
            values.add(word.apply(constant));
        }
        return new Side(type.getName().replace("crowdvsmarket.", ""), values, Set.of());
    }

    private static Side lowerCase(Class<? extends Enum<?>> type) {
        Set<String> values = new LinkedHashSet<>();
        for (Enum<?> constant : type.getEnumConstants()) {
            values.add(constant.name().toLowerCase(Locale.ROOT));
        }
        return new Side(type.getName().replace("crowdvsmarket.", "") + " (lower case)", values, Set.of());
    }

    private static Pairing pair(String key, Side... sides) {
        return new Pairing(key, List.of(sides));
    }

    private static final Side SOURCES = new Side("the names of the price sources (HyperliquidPriceSource, "
            + "PolymarketPriceSource, SyntheticPriceSource)",
            new LinkedHashSet<>(List.of(HyperliquidPriceSource.NAME, PolymarketPriceSource.NAME,
                    SyntheticPriceSource.NAME)), Set.of());

    private static Side priceSources() {
        Set<String> values = new LinkedHashSet<>(lowerCase(PricesConfig.SourceKind.class).values());
        values.add("replay"); // mode=replay plays accelerated history instead of any source
        return new Side("server.prices.PricesConfig.SourceKind (lower case) and \"replay\" for mode=replay", values,
                Set.of());
    }

    /** Contract union → the Java side(s) it is held against. */
    private static final List<Pairing> PAIRINGS = List.of(
            pair("Interval", enumSide(Enums.Interval.class), enumSide(crowdvsmarket.engine.market.Interval.class)),
            pair("Direction", enumSide(Enums.Direction.class), enumSide(crowdvsmarket.engine.round.Direction.class)),
            pair("RoundPhase", enumSide(Enums.RoundPhase.class)),
            pair("MarketKind", enumSide(Enums.MarketKind.class),
                    enumSide(crowdvsmarket.server.markets.MarketKind.class)),
            pair("CategoryId", new Side("server.markets.Category.ORDER",
                    new LinkedHashSet<>(Category.ORDER.stream().map(Category::id).toList()), Set.of())),
            pair("LedgerEntryType", enumSide(Enums.LedgerEntryType.class),
                    enumSide(crowdvsmarket.engine.ledger.LedgerEntryType.class)),
            pair("WithdrawalState", enumSide(Enums.WithdrawalState.class),
                    enumSide(crowdvsmarket.engine.ledger.WithdrawalState.class)),
            pair("WithdrawalChainState",
                    enumSide(Enums.WithdrawalChainState.class, Enums.WithdrawalChainState::wire)),
            pair("MarketDto.status", enumSide(Enums.MarketStatus.class),
                    enumSide(crowdvsmarket.server.markets.MarketStatus.class)),
            pair("CrowdOverviewDto.status", enumSide(Enums.MarketStatus.class),
                    enumSide(crowdvsmarket.server.markets.MarketStatus.class)),
            pair("MarketCrowdBriefDto.lastOutcome", enumSide(Enums.CrowdOutcome.class)),
            pair("MarketDto.source", SOURCES),
            pair("RoundBase.source", SOURCES),
            pair("ServerInfoDto.mode", lowerCase(GameMode.class)),
            pair("ServerInfoDto.priceSource", priceSources()),
            // a position is a bet waiting for its round or a position in the round that runs — never a settled or
            // a cancelled one (those leave myPositions): the phases a round has beyond these are the server's own
            pair("MyPositionDto.phase", enumSide(Enums.RoundPhase.class).javaOnly("SETTLED", "CANCELLED")));

    /** Unions that another test holds against their Java enum, with that test. */
    private static final Map<String, String> HELD_ELSEWHERE = Map.of(
            "ApiErrorCode", "ApiErrorCodeContractTest (it also holds the HTTP statuses and the ApiException factories)");

    /** The enums of {@link Enums} that contract.ts has no union for, and why. */
    private static final Map<String, String> SERVER_ONLY_ENUMS = Map.of(
            "ApiErrorCode", "held by ApiErrorCodeContractTest",
            "DepositState", "only the DTO of the retired self-report requestDeposit RPC: a deposit is no longer "
                    + "something a player requests (contract.ts dropped DepositRequestDto), the server credits one "
                    + "when the chain service reports it confirmed");

    // ---------------------------------------------------------------------------------------
    // the comparison itself
    // ---------------------------------------------------------------------------------------

    /** What is wrong between the union of contract.ts and one Java side; empty when they agree. */
    static List<String> problems(String key, Set<String> union, Side side) {
        List<String> problems = new ArrayList<>();
        Set<String> notInJava = new TreeSet<>(union);
        notInJava.removeAll(side.values());
        for (String value : notInJava) {
            problems.add("contract.ts `" + key + "` names \"" + value + "\" but " + side.java() + " does not have it — "
                    + "a value the web client expects and the server can never send: add it on the server or take it "
                    + "out of the union (and out of web/src/api/wireSchema.ts)");
        }
        Set<String> notInContract = new TreeSet<>(side.values());
        notInContract.removeAll(union);
        notInContract.removeAll(side.javaOnly());
        for (String value : notInContract) {
            problems.add(side.java() + " has \"" + value + "\" which contract.ts `" + key + "` does not name — a value "
                    + "the server can send and the web client has never heard of: add it to the union in "
                    + "web/src/api/contract.ts (and to wireSchema.ts, and give the UI a word for it) or, if it is the "
                    + "server's own on purpose, list it as javaOnly in this test");
        }
        return problems;
    }

    private static List<String> allProblems(Map<String, List<String>> unions) {
        List<String> problems = new ArrayList<>();
        for (Pairing pairing : PAIRINGS) {
            List<String> members = unions.get(pairing.key());
            if (members == null) {
                problems.add("this test pairs the union `" + pairing.key() + "`, which contract.ts no longer declares "
                        + "(renamed or removed?) — the pairing guards nothing: update PAIRINGS; contract.ts has "
                        + unions.keySet());
                continue;
            }
            for (Side side : pairing.sides()) {
                problems.addAll(problems(pairing.key(), new LinkedHashSet<>(members), side));
            }
        }
        for (String key : unions.keySet()) {
            boolean registered = PAIRINGS.stream().anyMatch(p -> p.key().equals(key)) || HELD_ELSEWHERE.containsKey(key);
            if (!registered) {
                problems.add("contract.ts declares the string-literal union `" + key + "` " + unions.get(key)
                        + " and no test holds it against the server: add a pairing for it in ContractUnionsTest."
                        + "PAIRINGS (the Java enum or list it must equal)");
            }
        }
        return problems;
    }

    // ---------------------------------------------------------------------------------------
    // the guard
    // ---------------------------------------------------------------------------------------

    @Test
    void everyUnionOfContractTsIsExactlyItsJavaSide() throws IOException {
        List<String> problems = allProblems(ContractTs.stringUnions());

        assertThat(problems).as("contract.ts and the server disagree on a word list:%n  %s",
                String.join("\n  ", problems)).isEmpty();
    }

    @Test
    void everyEnumOfTheServersDtosIsPairedOrListedAsServerOnly() {
        Set<String> paired = new TreeSet<>();
        for (Pairing pairing : PAIRINGS) {
            for (Side side : pairing.sides()) {
                paired.add(side.java());
            }
        }
        List<String> unaccounted = new ArrayList<>();
        for (Class<?> nested : Enums.class.getDeclaredClasses()) {
            if (!nested.isEnum() || SERVER_ONLY_ENUMS.containsKey(nested.getSimpleName())) {
                continue;
            }
            if (!paired.contains(nested.getName().replace("crowdvsmarket.", ""))) {
                unaccounted.add(nested.getSimpleName());
            }
        }

        assertThat(unaccounted).as("an enum of dto.Enums that no pairing holds against contract.ts and that is not "
                + "listed in SERVER_ONLY_ENUMS: pair it with its union or say why contract.ts has none").isEmpty();
        assertThat(Arrays.stream(Enums.class.getDeclaredClasses()).filter(Class::isEnum).map(Class::getSimpleName)
                .toList()).as("the enums the server's DTOs have").containsAll(SERVER_ONLY_ENUMS.keySet());
    }

    @Test
    void theUnionsOfContractTsAreTheOnesThisTestKnows() throws IOException {
        // a floor under the parser: if the file is restructured so that it no longer finds the unions, the guard
        // above would pass over nothing
        Map<String, List<String>> unions = ContractTs.stringUnions();

        assertThat(unions.keySet()).contains("Interval", "Direction", "RoundPhase", "MarketKind", "CategoryId",
                "LedgerEntryType", "WithdrawalState", "WithdrawalChainState", "MarketDto.status",
                "MarketCrowdBriefDto.lastOutcome", "ServerInfoDto.mode", "ApiErrorCode", "RoundBase.source");
        assertThat(unions.get("LedgerEntryType")).contains("OPERATOR_CORRECTION");
        assertThat(unions.get("MarketCrowdBriefDto.lastOutcome")).contains("FLAT");
        assertThat(unions.get("ApiErrorCode")).as("the doc comments inside the union do not cut it short")
                .endsWith("OTHER");
    }

    // ---------------------------------------------------------------------------------------
    // the guard guards: a value on one side only fails with a message that says so
    // ---------------------------------------------------------------------------------------

    @Test
    void aValueOnTheContractSideOnlyIsReportedAsOneTheServerCanNeverSend() {
        Side java = new Side("dto.Enums.LedgerEntryType", Set.of("DEPOSIT"), Set.of());

        List<String> problems = problems("LedgerEntryType", Set.of("DEPOSIT", "CHARGEBACK"), java);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("`LedgerEntryType`", "\"CHARGEBACK\"", "dto.Enums.LedgerEntryType",
                "does not have it");
    }

    @Test
    void aValueOnTheJavaSideOnlyIsReportedAsOneTheWebClientHasNeverHeardOf() {
        Side java = new Side("dto.Enums.LedgerEntryType", Set.of("DEPOSIT", "OPERATOR_CORRECTION"), Set.of());

        List<String> problems = problems("LedgerEntryType", Set.of("DEPOSIT"), java);

        assertThat(problems).hasSize(1);
        assertThat(problems.get(0)).contains("\"OPERATOR_CORRECTION\"", "does not name", "`LedgerEntryType`",
                "web/src/api/contract.ts");
    }

    @Test
    void aJavaOnlyValueListedOnPurposeIsNotAProblemButAnythingElseStillIs() {
        Side java = new Side("dto.Enums.RoundPhase", Set.of("BETTING", "RUNNING", "SETTLED", "CANCELLED"),
                Set.of("SETTLED", "CANCELLED"));

        assertThat(problems("MyPositionDto.phase", Set.of("BETTING", "RUNNING"), java)).isEmpty();
        assertThat(problems("MyPositionDto.phase", Set.of("BETTING"), java)).hasSize(1); // RUNNING is not listed on purpose
    }

    @Test
    void theRealContractWithAWordRemovedOrAddedIsCaught() throws IOException {
        String real = ContractTs.source();
        assertThat(allProblems(ContractTs.stringUnions(real))).as("the contract as it is").isEmpty();

        // the web side drops the type the wallet had no label for, and invents a withdrawal state
        String edited = real.replace("| \"OPERATOR_CORRECTION\"", "")
                .replace("export type WithdrawalState = \"REQUESTED\"", "export type WithdrawalState = \"EXPIRED\" | \"REQUESTED\"");
        assertThat(edited).as("the edit found its place in contract.ts").isNotEqualTo(real);

        List<String> problems = allProblems(ContractTs.stringUnions(edited));

        assertThat(problems).anyMatch(p -> p.contains("\"OPERATOR_CORRECTION\"") && p.contains("does not name")
                && p.contains("`LedgerEntryType`"));
        assertThat(problems).anyMatch(p -> p.contains("\"EXPIRED\"") && p.contains("`WithdrawalState`")
                && p.contains("does not have it"));
    }

    @Test
    void anUnregisteredUnionAndAPairingOfAUnionThatIsGoneBothFail() {
        Map<String, List<String>> unions = new java.util.LinkedHashMap<>(Map.of("Interval", List.of("HOURLY", "DAILY")));
        unions.put("Surprise", List.of("A", "B"));

        List<String> problems = allProblems(unions);

        assertThat(problems).anyMatch(p -> p.contains("`Surprise`") && p.contains("no test holds it"));
        assertThat(problems).anyMatch(p -> p.contains("pairs the union `Direction`") && p.contains("no longer declares"));
    }

    // ---------------------------------------------------------------------------------------
    // the parser (ContractTs)
    // ---------------------------------------------------------------------------------------

    @Test
    void theParserReadsAliasesAndFieldsAndIgnoresCommentsAndSingleLiterals() {
        String ts = """
                /** A doc comment; with a semicolon and a "quoted | word" — and https://example.org/a // not a comment */
                export type Colour = "RED" | "GREEN"; // trailing; comment
                export type Wide =
                  /** one; */
                  | "A"
                  | "B" // two; three
                  | "C";
                interface Box {
                  /** the state; "X" | "Y" in a comment is not a union */
                  state: "OPEN" | "SHUT";
                  maybe?: "ONE" | "TWO" | null;
                  discriminant: "ONLY";
                  inner: Array<{ kind: "P" | "Q" }>;
                  url: string; // see https://example.org/page
                }
                export interface Other { state: "OTHER_OPEN" | "OTHER_SHUT"; }
                """;

        Map<String, List<String>> unions = ContractTs.stringUnions(ts);

        assertThat(unions).containsOnlyKeys("Colour", "Wide", "Box.state", "Box.maybe", "Box.kind", "Other.state");
        assertThat(unions.get("Colour")).containsExactly("RED", "GREEN");
        assertThat(unions.get("Wide")).containsExactly("A", "B", "C");
        assertThat(unions.get("Box.state")).containsExactly("OPEN", "SHUT");
        assertThat(unions.get("Box.maybe")).containsExactly("ONE", "TWO");
        assertThat(unions.get("Box.kind")).containsExactly("P", "Q");
        assertThat(unions.get("Other.state")).containsExactly("OTHER_OPEN", "OTHER_SHUT");
    }
}
