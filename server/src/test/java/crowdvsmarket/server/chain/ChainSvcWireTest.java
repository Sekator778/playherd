package crowdvsmarket.server.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import crowdvsmarket.server.config.GameConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ChainSvcClientImpl} over a real HTTP stack — the Quarkus-generated REST client, a JDK server on the
 * loopback interface that answers with the committed chainsvc fixtures
 * ({@code chain/c2-svc/tests/contract/}, see {@link ChainSvcContractFixturesTest}) — never the network. The
 * fixture test proves the DTOs; this one proves what actually goes over the wire and what a real response
 * does to the client: a record leaves with its {@code asset}, the format-2 status fields arrive, a status of
 * an older chainsvc arrives without them, and a field chainsvc may add one day does not make the status
 * unreadable (which would keep a required seq floor closed for good). A {@code @QuarkusTest} because Quarkus
 * generates the client implementation at build time.
 */
@QuarkusTest
class ChainSvcWireTest {

    private static final Path CONTRACT_DIR = Path.of("../chain/c2-svc/tests/contract");

    private record Received(String method, String path, String authorization, String contentType, String body) {
    }

    @Inject
    ObjectMapper mapper;

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile String replyBody = "{}";
    private volatile int replyStatus = 200;
    private ChainSvcClientImpl impl;

    @BeforeEach
    void startServerAndClient() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        GameConfig config = mock(GameConfig.class);
        when(config.chainsvcToken()).thenReturn("wire-test-token");
        impl = new ChainSvcClientImpl();
        impl.config = config;
        impl.rest = RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .build(ChainSvcRestClient.class);
    }

    @AfterEach
    void stopServer() throws Exception {
        if (impl.rest instanceof java.io.Closeable c) {
            c.close();
        }
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"), body));
        byte[] bytes = replyBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(replyStatus, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    private String fixture(String name) throws IOException {
        return Files.readString(CONTRACT_DIR.resolve(name + ".json"));
    }

    @Test
    void anOracleRecordLeavesAsJsonWithItsAssetAndTheBearerToken() throws Exception {
        replyBody = fixture("OracleRecordsResponse");

        ChainSvcClient.OracleRecordsResult result = impl.oracleRecords(List.of(
                new ChainSvcClient.OracleRecord(205L, 1_790_400_000L, 8_561_200L, "0x" + "ab".repeat(32), "live",
                        1_790_400_001L, 3),
                new ChainSvcClient.OracleRecord(206L, 1_790_400_180L, 8_561_300L, "0x" + "cd".repeat(32), "live",
                        1_790_400_181L)));

        assertThat(received).hasSize(1);
        Received request = received.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/oracle/records");
        assertThat(request.authorization()).isEqualTo("Bearer wire-test-token");
        assertThat(request.contentType()).startsWith("application/json");
        JsonNode sent = mapper.readTree(request.body());
        assertThat(sent.get("records")).hasSize(2);
        // the same record as the committed fixture (asset 3), and a BTC record with an explicit asset 0
        JsonNode fixtureRecord = mapper.readTree(fixture("OracleRecordsRequest")).get("records").get(0);
        assertThat(sent.get("records").get(0)).isEqualTo(fixtureRecord);
        assertThat(sent.get("records").get(1).get("asset").asInt()).isZero();
        assertThat(sent.get("records").get(1).get("seq").asLong()).isEqualTo(206L);
        assertThat(result.acceptedSeqs()).containsExactly(205L);
    }

    @Test
    void theStatusFixtureIsReadOverHttpIntoTheFormatTwoFields() throws Exception {
        replyBody = fixture("OracleStatusResponse");

        ChainSvcClient.OracleStatus status = impl.oracleStatus();

        assertThat(received.get(0).method()).isEqualTo("GET");
        assertThat(received.get(0).path()).isEqualTo("/v1/oracle/status");
        assertThat(received.get(0).authorization()).isEqualTo("Bearer wire-test-token");
        assertThat(status.maxKnownSeq()).isEqualTo(209L);
        assertThat(status.recordFormat()).isEqualTo(2);
        assertThat(status.supportsAssets()).isTrue();
        assertThat(status.feeCoinsFree()).isEqualTo(4);
        assertThat(status.feeCoinsFreeMojos()).isEqualTo("800000000");
        assertThat(status.lastConfirmedSeq()).isEqualTo(205L);
        assertThat(status.conflicts()).isEmpty();
    }

    @Test
    void theStatusOfAnOlderChainServiceReadsWithoutTheFormatTwoFieldsAndCountsAsFormatOne() throws Exception {
        ObjectNode old = (ObjectNode) mapper.readTree(fixture("OracleStatusResponse"));
        old.remove(List.of("maxKnownSeq", "recordFormat", "feeCoinsFree", "feeCoinsFreeMojos"));
        replyBody = old.toString();

        ChainSvcClient.OracleStatus status = impl.oracleStatus();

        assertThat(status).isNotNull();
        assertThat(status.maxKnownSeq()).isNull();
        assertThat(status.recordFormat()).isNull();
        assertThat(status.feeCoinsFree()).isNull();
        assertThat(status.feeCoinsFreeMojos()).isNull();
        assertThat(status.effectiveRecordFormat()).isEqualTo(1);
        assertThat(status.supportsAssets()).isFalse();
        assertThat(status.lastConfirmedSeq()).isEqualTo(205L);
    }

    @Test
    void aFieldChainsvcAddsLaterDoesNotMakeTheStatusUnreadable() throws Exception {
        ObjectNode future = (ObjectNode) mapper.readTree(fixture("OracleStatusResponse"));
        future.put("somethingChainsvcAddsInRecordFormatThree", 7);
        replyBody = future.toString();

        ChainSvcClient.OracleStatus status = impl.oracleStatus();

        assertThat(status).as("an unknown field must not turn the status into 'chainsvc unavailable'").isNotNull();
        assertThat(status.maxKnownSeq()).isEqualTo(209L);
    }

    // ------------------------------------------------------------------------------------------
    // POST /v1/play/withdrawals: accepted, or HTTP 409 (E3)
    // ------------------------------------------------------------------------------------------

    @Test
    void aWithdrawalLeavesUnderTheIdItIsGivenAndAnAcceptedAnswerIsAcceptedNotAConflict() throws Exception {
        replyBody = fixture("WithdrawalAcceptedResponse");

        ChainSvcClient.PlayWithdrawalSubmitResult result = impl.submitPlayWithdrawal("w-11-3fa9c01b",
                "txch1recipient", 250_000L);

        assertThat(result.accepted()).isTrue();
        assertThat(result.isConflict()).isFalse();
        Received request = received.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/v1/play/withdrawals");
        assertThat(request.authorization()).isEqualTo("Bearer wire-test-token");
        JsonNode sent = mapper.readTree(request.body());
        assertThat(sent.get("requestId").asText()).as("the id of the chain service's namespace").isEqualTo("w-11-3fa9c01b");
        assertThat(sent.get("toAddress").asText()).isEqualTo("txch1recipient");
        assertThat(sent.get("amountMojos").asLong()).isEqualTo(250_000L);
    }

    @Test
    void aHttp409WithAnErrorTextIsAConflictResultCarryingThatText() {
        replyStatus = 409;
        replyBody = "{\"error\": \"requestId w-11 is already held by this service with a different recipient or amount\"}";

        ChainSvcClient.PlayWithdrawalSubmitResult result = impl.submitPlayWithdrawal("w-11", "txch1recipient", 250_000L);

        assertThat(result.isConflict()).isTrue();
        assertThat(result.accepted()).isFalse();
        assertThat(result.conflict()).isEqualTo(
                "requestId w-11 is already held by this service with a different recipient or amount");
    }

    @Test
    void theCommittedConflictFixtureServedWithHttp409IsAConflictResultWithItsErrorText() throws Exception {
        replyStatus = 409;
        replyBody = fixture("WithdrawalConflictResponse");

        ChainSvcClient.PlayWithdrawalSubmitResult result = impl.submitPlayWithdrawal("w-11", "txch1recipient", 250_000L);

        assertThat(result.isConflict()).isTrue();
        assertThat(result.accepted()).isFalse();
        assertThat(result.conflict()).isEqualTo(mapper.readTree(fixture("WithdrawalConflictResponse")).get("error")
                .asText());
    }

    @Test
    void theConflictBodyThatTheChainServiceSendsTodayHasCodeAndRequestIdBesideTheErrorAndIsReadTheSame() {
        replyStatus = 409;
        replyBody = "{\"error\": \"requestId w-12 is already held by this service, but its recipient and amount "
                + "were not recorded\", \"code\": \"request_id_conflict\", \"requestId\": \"w-12\"}";

        ChainSvcClient.PlayWithdrawalSubmitResult result = impl.submitPlayWithdrawal("w-12", "txch1recipient", 1L);

        assertThat(result.isConflict()).isTrue();
        assertThat(result.conflict()).startsWith("requestId w-12 is already held").doesNotContain("request_id_conflict");
    }

    @Test
    void aHttp409WhoseBodyIsNotTheExpectedJsonStillIsAConflictWithWhatThereIs() {
        replyStatus = 409;
        replyBody = "conflict, plain text";
        assertThat(impl.submitPlayWithdrawal("w-1", "txch1r", 1L).conflict()).isEqualTo("conflict, plain text");

        replyBody = "{\"unexpected\": true}";
        assertThat(impl.submitPlayWithdrawal("w-1", "txch1r", 1L).conflict()).isEqualTo("{\"unexpected\": true}");

        replyBody = "";
        ChainSvcClient.PlayWithdrawalSubmitResult empty = impl.submitPlayWithdrawal("w-1", "txch1r", 1L);
        assertThat(empty.isConflict()).isTrue();
        assertThat(empty.conflict()).isEqualTo("HTTP 409");
    }

    @Test
    void otherErrorsAreAnOutageForTheRetryAndNeverAConflict() {
        for (int status : new int[] {400, 404, 500, 503}) {
            replyStatus = status;
            replyBody = "{\"error\": \"something\"}";

            assertThat(impl.submitPlayWithdrawal("w-1", "txch1r", 1L)).as("HTTP %d", status).isNull();
        }
    }
}
