package crowdvsmarket.server;

import static org.assertj.core.api.Assertions.assertThat;

import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import crowdvsmarket.server.testsupport.ContractTs;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The error codes the web client branches on ({@code ApiErrorCode} in web/src/api/contract.ts) and the ones
 * this server sends ({@link Enums.ApiErrorCode}) must not drift apart silently: this reads the union out of
 * contract.ts and compares.
 * <ul>
 *   <li>Every code of the union except {@code OTHER} is a code the server has under EXACTLY that name, and
 *       an {@link ApiException} factory produces it — a code in contract.ts that no server path can send
 *       would be a branch of the UI that never runs.
 *   <li>The other way round, a code the server sends that the union does not name is read by the web client as
 *       {@code OTHER} (it shows the server's own message): that is allowed, but each such code is listed
 *       below on purpose — adding a server code makes this test fail until someone decides whether the union
 *       should name it too.
 * </ul>
 * The union is read by {@link ContractTs}, which strips contract.ts' comments first: the doc comments inside the
 * union (one per code now) carry {@code ;}, and the first version of this test, which cut the declaration at the
 * first {@code ;}, stopped before {@code OTHER}. The other string-literal unions of contract.ts are held against
 * their Java enums by {@code ContractUnionsTest}.
 */
class ApiErrorCodeContractTest {

    /** Codes the server sends that contract.ts' union does not name — read as {@code OTHER} by the web client
     *  ({@code realApi.ts}), which shows the server's message as it is. ({@code NOT_FOUND}, {@code
     *  INVALID_NICKNAME} and {@code ENGINE_NOT_READY} are named by the union since {@code c00da2c}: the web
     *  client branches on them.) */
    private static final Set<String> SERVER_ONLY = Set.of(
            "UNAUTHORIZED", "BAD_REQUEST", "INVALID_AMOUNT",
            "DEPOSIT_VERIFICATION_UNAVAILABLE", "WITHDRAWAL_UNAVAILABLE", "WITHDRAWAL_BELOW_MINIMUM",
            "CHAIN_SERVICE_UNAVAILABLE", "REGISTRATION_LIMIT", "EXPOSURE_LIMIT_EXCEEDED");

    /** The string literals of {@code export type ApiErrorCode = | "A" | "B" …;} in contract.ts, in order. */
    static Set<String> unionInContract() throws IOException {
        return new LinkedHashSet<>(ContractTs.union("ApiErrorCode"));
    }

    /** The codes every public static factory of {@link ApiException} produces (they all take Strings or nothing). */
    private static Set<String> codesOfTheFactories() throws ReflectiveOperationException {
        Set<String> codes = new TreeSet<>();
        for (Method method : ApiException.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())
                    || method.getReturnType() != ApiException.class) {
                continue;
            }
            Object[] args = new Object[method.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                assertThat(method.getParameterTypes()[i]).as("%s takes only Strings", method).isEqualTo(String.class);
                args[i] = "x";
            }
            codes.add(((ApiException) method.invoke(null, args)).code().name());
        }
        return codes;
    }

    @Test
    void theUnionWasReadAndNamesOther() throws IOException {
        Set<String> union = unionInContract();

        assertThat(union).contains("OTHER", "NOT_SIGNED_IN", "RATE_LIMITED", "MARKET_NOT_FOUND", "ENGINE_NOT_READY",
                "NOT_FOUND", "INVALID_NICKNAME");
        assertThat(union.size()).as("a union of this size was parsed: %s", union).isGreaterThanOrEqualTo(16);
    }

    @Test
    void everyCodeOfTheUnionExceptOtherIsAServerCodeUnderExactlyThatName() throws IOException {
        Set<String> serverCodes = new TreeSet<>();
        EnumSet.allOf(Enums.ApiErrorCode.class).forEach(c -> serverCodes.add(c.name()));
        Set<String> union = new TreeSet<>(unionInContract());
        union.remove("OTHER");

        assertThat(serverCodes).as("contract.ts names a code the server does not have").containsAll(union);
    }

    @Test
    void everyCodeOfTheUnionExceptOtherIsProducedByAFactory() throws Exception {
        Set<String> union = new TreeSet<>(unionInContract());
        union.remove("OTHER");

        assertThat(codesOfTheFactories()).as("a code of contract.ts that no ApiException factory produces")
                .containsAll(union);
    }

    @Test
    void theServerCodesTheUnionDoesNotNameAreExactlyTheListedOnes() throws IOException {
        Set<String> union = unionInContract();
        Set<String> notInTheUnion = new TreeSet<>();
        for (Enums.ApiErrorCode code : Enums.ApiErrorCode.values()) {
            if (!union.contains(code.name())) {
                notInTheUnion.add(code.name());
            }
        }

        assertThat(notInTheUnion)
                .as("a server error code that contract.ts' ApiErrorCode does not name: add it to the union "
                        + "(and to realApi.ts' KNOWN_ERROR_CODES) or to SERVER_ONLY here, on purpose")
                .isEqualTo(new TreeSet<>(SERVER_ONLY));
    }

    @Test
    void theCodesTheWebClientBranchesOnComeWithTheStatusContractTsNamesForThem() {
        // contract.ts: ENGINE_NOT_READY "HTTP 503", NOT_FOUND "HTTP 404", INVALID_NICKNAME "HTTP 400"
        assertThat(ApiException.engineNotReady().code().name()).isEqualTo("ENGINE_NOT_READY");
        assertThat(ApiException.engineNotReady().httpStatus()).isEqualTo(503);
        assertThat(ApiException.notFound("x").code().name()).isEqualTo("NOT_FOUND");
        assertThat(ApiException.notFound("x").httpStatus()).isEqualTo(404);
        assertThat(ApiException.invalidNickname("x").code().name()).isEqualTo("INVALID_NICKNAME");
        assertThat(ApiException.invalidNickname("x").httpStatus()).isEqualTo(400);
        assertThat(ApiException.marketNotFound("x").httpStatus()).isEqualTo(404);
        assertThat(ApiException.notSignedIn().httpStatus()).isEqualTo(401);
    }

    @Test
    void rateLimitingIsRateLimitedWithHttp429() {
        ApiException limited = ApiException.rateLimited();

        assertThat(limited.code().name()).isEqualTo("RATE_LIMITED");
        assertThat(limited.httpStatus()).isEqualTo(429);
    }
}
