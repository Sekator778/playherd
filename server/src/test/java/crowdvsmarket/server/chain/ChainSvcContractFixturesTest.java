package crowdvsmarket.server.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import crowdvsmarket.server.config.GameConfig;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Task item 5 (contract test Java<->Rust, docs/reviews/2026-09-26-independent-critique.md): the
 * server<->chainsvc boundary is also tested in {@link ChainSvcClientImplTest} and
 * {@code OraclePublisherServiceTest}, but those mock {@link ChainSvcClient}/{@link ChainSvcRestClient}
 * directly rather than exercising the real HTTP/JSON contract against chain/c2-svc's actual
 * request/response shapes — a silent field-name/type drift on either side would not have been
 * caught by any of them. This reads the SAME committed JSON fixtures
 * chain/c2-svc/tests/contract_fixtures.rs writes from c2-svc's own real Rust types (real BLS
 * material for the auth cases, replaying tests/vectors/auth_vectors.json — see that Rust test's
 * doc comment) and deserializes/reserializes them with the server's own {@link ChainSvcRestClient}
 * DTOs — the same "shared golden fixture across two languages" pattern
 * {@code LiquidationCalculatorGoldenVectorTest}/{@code liquidation.test.ts} already use for the
 * liquidation math.
 *
 * <p>Regenerate the fixtures with {@code cargo test -p c2-svc --test contract_fixtures} (from
 * {@code chain/}) whenever a wire type's shape changes on the Rust side; this test then fails
 * loudly here instead of the drift only ever being observed live.
 *
 * <p><b>All fifteen fixtures</b> (2026-10-02; the chain-side generator writes the {@code /v1/play/*}
 * ones since 2026-09-26 and the record-format-2 fields — {@code asset} on a record, {@code
 * maxKnownSeq}/{@code recordFormat}/{@code feeCoinsFree}/{@code feeCoinsFreeMojos} on the status —
 * since 2026-10-01): {@link #everyFixtureHasADtoHereAndRoundTripsThroughIt} walks the fixture
 * directory itself, so a fixture the chain side adds without a counterpart here fails this class
 * instead of waiting to be noticed, and each fixture then has a test below that names the fields the
 * server depends on. "Round trip" means: read into the DTO, written back out, and the result equals the
 * fixture — a field the DTO lacks is lost in that trip and the comparison fails (Jackson would drop
 * it silently on the way in, which is how {@code stuckReason}/{@code conflicts} went unread once).
 */
@QuarkusTest
class ChainSvcContractFixturesTest {

    /** Same module layout both directions rely on: chain/ and server/ are sibling directories
     *  under the repo root, and Surefire's forked JVM working directory is this module's own
     *  basedir ({@code server/}) — see e.g. {@code EventStoreGoldenFixturesTest}'s own
     *  module-relative fixture path for the same convention. */
    private static final Path CONTRACT_DIR = Path.of("../chain/c2-svc/tests/contract");

    /** Every fixture and the server DTO that carries it. */
    private static final Map<String, Class<?>> DTO_OF = Map.ofEntries(
            Map.entry("AuthVerifyRequest", ChainSvcRestClient.AuthVerifyRequest.class),
            Map.entry("AuthVerifyResponse_valid", ChainSvcRestClient.AuthVerifyResponse.class),
            Map.entry("AuthVerifyResponse_invalid", ChainSvcRestClient.AuthVerifyResponse.class),
            Map.entry("OracleRecordsRequest", ChainSvcRestClient.OracleRecordsRequest.class),
            Map.entry("OracleRecordsResponse", ChainSvcRestClient.OracleRecordsResponse.class),
            Map.entry("OracleRecordStatusResponse", ChainSvcRestClient.OracleRecordStateResponse.class),
            Map.entry("OracleStatusResponse", ChainSvcRestClient.OracleStatusResponse.class),
            Map.entry("WithdrawalRequestDto", ChainSvcRestClient.PlayWithdrawalRequest.class),
            Map.entry("WithdrawalAcceptedResponse", ChainSvcRestClient.PlayWithdrawalAcceptedResponse.class),
            Map.entry("WithdrawalStatusResponse_queued", ChainSvcRestClient.PlayWithdrawalStatusResponse.class),
            Map.entry("WithdrawalStatusResponse_confirmed", ChainSvcRestClient.PlayWithdrawalStatusResponse.class),
            Map.entry("WithdrawalStatusResponse_failed", ChainSvcRestClient.PlayWithdrawalStatusResponse.class),
            Map.entry("DepositAddressRequestDto", ChainSvcRestClient.PlayDepositAddressRequest.class),
            Map.entry("DepositAddressResponse", ChainSvcRestClient.PlayDepositAddressResponse.class),
            Map.entry("DepositsResponse", ChainSvcRestClient.PlayDepositsResponse.class));

    @Inject
    ObjectMapper mapper;

    private JsonNode readFixture(String name) throws IOException {
        Path path = CONTRACT_DIR.resolve(name + ".json");
        assertThat(Files.exists(path))
                .as("missing contract fixture %s — run `cargo test -p c2-svc --test contract_fixtures` in chain/", path)
                .isTrue();
        return mapper.readTree(Files.readString(path));
    }

    /** The fixture read into {@code type} and written back out, as a tree (key order and
     *  formatting do not matter). */
    private JsonNode roundTrip(JsonNode fixture, Class<?> type) throws IOException {
        return mapper.readTree(mapper.writeValueAsString(mapper.treeToValue(fixture, type)));
    }

    private static JsonNode withoutNulls(JsonNode tree) {
        if (tree instanceof ObjectNode object) {
            ObjectNode copy = object.objectNode();
            for (Map.Entry<String, JsonNode> e : object.properties()) {
                if (!e.getValue().isNull()) {
                    copy.set(e.getKey(), withoutNulls(e.getValue()));
                }
            }
            return copy;
        }
        return tree;
    }

    // --- the whole directory ---

    @Test
    void everyFixtureHasADtoHereAndRoundTripsThroughIt() throws IOException {
        Set<String> onDisk;
        try (Stream<Path> files = Files.list(CONTRACT_DIR)) {
            onDisk = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
        assertThat(onDisk)
                .as("the fixtures chain/c2-svc writes and the DTOs this class knows must be the same set")
                .containsExactlyInAnyOrderElementsOf(DTO_OF.keySet());
        assertThat(onDisk).hasSize(15);

        for (Map.Entry<String, Class<?>> entry : new TreeMap<>(DTO_OF).entrySet()) {
            JsonNode fixture = readFixture(entry.getKey());
            JsonNode back = roundTrip(fixture, entry.getValue());
            if (entry.getKey().equals("AuthVerifyResponse_valid")) {
                // c2-svc omits `reason` when there is none; this side never serializes the response,
                // so only "every field of the fixture was read" matters here.
                assertThat(withoutNulls(back)).as(entry.getKey()).isEqualTo(withoutNulls(fixture));
            } else {
                assertThat(back).as(entry.getKey()).isEqualTo(fixture);
            }
        }
    }

    // --- auth ---

    @Test
    void authVerifyRequestFixtureDeserializesIntoTheServersDto() throws IOException {
        JsonNode json = readFixture("AuthVerifyRequest");
        ChainSvcRestClient.AuthVerifyRequest dto = mapper.treeToValue(json, ChainSvcRestClient.AuthVerifyRequest.class);

        assertThat(dto.address()).isEqualTo(json.get("address").asText());
        assertThat(dto.publicKey()).isEqualTo(json.get("publicKey").asText());
        assertThat(dto.message()).isEqualTo(json.get("message").asText());
        assertThat(dto.signature()).isEqualTo(json.get("signature").asText());

        // Re-serializing the server's own DTO must reproduce the same fixture (round trip) —
        // parsed back to a tree so key order/formatting differences don't matter.
        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }

    @Test
    void authVerifyResponseValidFixtureDeserializesWithReasonAbsent() throws IOException {
        JsonNode json = readFixture("AuthVerifyResponse_valid");
        ChainSvcRestClient.AuthVerifyResponse dto =
                mapper.treeToValue(json, ChainSvcRestClient.AuthVerifyResponse.class);

        assertThat(dto.valid()).isTrue();
        assertThat(dto.reason()).isNull();
    }

    @Test
    void authVerifyResponseInvalidFixtureDeserializesWithReasonPresent() throws IOException {
        JsonNode json = readFixture("AuthVerifyResponse_invalid");
        ChainSvcRestClient.AuthVerifyResponse dto =
                mapper.treeToValue(json, ChainSvcRestClient.AuthVerifyResponse.class);

        assertThat(dto.valid()).isFalse();
        assertThat(dto.reason()).isEqualTo(json.get("reason").asText());
    }

    // --- oracle ---

    @Test
    void oracleRecordsRequestFixtureRoundTrips() throws IOException {
        JsonNode json = readFixture("OracleRecordsRequest");
        ChainSvcRestClient.OracleRecordsRequest dto =
                mapper.treeToValue(json, ChainSvcRestClient.OracleRecordsRequest.class);

        assertThat(dto.records()).hasSize(1);
        ChainSvcRestClient.OracleRecordJson record = dto.records().get(0);
        JsonNode recordJson = json.get("records").get(0);
        assertThat(record.seq()).isEqualTo(recordJson.get("seq").asLong());
        assertThat(record.ts()).isEqualTo(recordJson.get("ts").asLong());
        assertThat(record.priceCents()).isEqualTo(recordJson.get("priceCents").asLong());
        assertThat(record.resultsHash()).isEqualTo(recordJson.get("resultsHash").asText());
        assertThat(record.source()).isEqualTo(recordJson.get("source").asText());
        assertThat(record.sourceTs()).isEqualTo(recordJson.get("sourceTs").asLong());
        // record format 2: the registry index of the record's market — the fixture's is non-zero on
        // purpose, so this round trip proves the field is carried, not defaulted
        assertThat(recordJson.get("asset").asInt()).isEqualTo(3);
        assertThat(record.asset()).isEqualTo(3);

        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }

    @Test
    void theServersOwnRecordIsWhatTheFixtureSays() throws IOException {
        // the path the publisher takes: ChainSvcClient.OracleRecord → ChainSvcRestClient.OracleRecordJson,
        // as ChainSvcClientImpl maps it — serialized, it must be the fixture's record
        JsonNode json = readFixture("OracleRecordsRequest");
        JsonNode recordJson = json.get("records").get(0);
        ChainSvcClient.OracleRecord record = new ChainSvcClient.OracleRecord(recordJson.get("seq").asLong(),
                recordJson.get("ts").asLong(), recordJson.get("priceCents").asLong(),
                recordJson.get("resultsHash").asText(), recordJson.get("source").asText(),
                recordJson.get("sourceTs").asLong(), recordJson.get("asset").asInt());

        ChainSvcRestClient.OracleRecordsRequest request = new ChainSvcRestClient.OracleRecordsRequest(List.of(
                new ChainSvcRestClient.OracleRecordJson(record.seq(), record.ts(), record.priceCents(),
                        record.resultsHash(), record.source(), record.sourceTs(), record.asset())));

        assertThat(mapper.readTree(mapper.writeValueAsString(request))).isEqualTo(json);
    }

    @Test
    void oracleRecordsResponseFixtureRoundTrips() throws IOException {
        JsonNode json = readFixture("OracleRecordsResponse");
        ChainSvcRestClient.OracleRecordsResponse dto =
                mapper.treeToValue(json, ChainSvcRestClient.OracleRecordsResponse.class);

        assertThat(dto.queued()).isEqualTo(json.get("queued").asInt());
        assertThat(dto.acceptedSeqs()).isEqualTo(List.of(205L));
        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }

    @Test
    void oracleRecordStatusResponseFixtureRoundTrips() throws IOException {
        JsonNode json = readFixture("OracleRecordStatusResponse");
        ChainSvcRestClient.OracleRecordStateResponse dto =
                mapper.treeToValue(json, ChainSvcRestClient.OracleRecordStateResponse.class);

        assertThat(dto.seq()).isEqualTo(json.get("seq").asLong());
        assertThat(dto.state()).isEqualTo(json.get("state").asText());
        assertThat(dto.height()).isEqualTo(json.get("height").asLong());
        assertThat(dto.coinId()).isEqualTo(json.get("coinId").asText());
        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }

    @Test
    void oracleStatusResponseFixtureRoundTrips() throws IOException {
        JsonNode json = readFixture("OracleStatusResponse");
        ChainSvcRestClient.OracleStatusResponse dto =
                mapper.treeToValue(json, ChainSvcRestClient.OracleStatusResponse.class);

        assertThat(dto.launcherId()).isEqualTo(json.get("launcherId").asText());
        assertThat(dto.network()).isEqualTo(json.get("network").asText());
        assertThat(dto.lastConfirmedSeq()).isEqualTo(json.get("lastConfirmedSeq").asLong());
        assertThat(dto.lastConfirmedHeight()).isEqualTo(json.get("lastConfirmedHeight").asLong());
        assertThat(dto.pendingRecords()).isEqualTo(json.get("pendingRecords").asInt());
        assertThat(dto.inFlightBundleId()).isNull();
        assertThat(dto.operatorBalanceMojos()).isEqualTo(json.get("operatorBalanceMojos").asText());
        // stuckReason/conflicts: task item 5's own real finding — these were missing from this
        // Java record entirely (silently dropped by Jackson) until this contract test caught it.
        assertThat(dto.stuckReason()).isNull();
        assertThat(dto.conflicts()).isEmpty();
        // record format 2 (docs/research/chain_service.md §16.3)
        assertThat(dto.maxKnownSeq()).isEqualTo(209L);
        assertThat(dto.recordFormat()).isEqualTo(2);
        assertThat(dto.feeCoinsFree()).isEqualTo(4);
        assertThat(dto.feeCoinsFreeMojos()).isEqualTo("800000000");

        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }

    @Test
    void theStatusFixtureReadsAsAFormatTwoChainServiceWithTheFloorAndTheFeeCoins() throws IOException {
        // through ChainSvcClientImpl's own mapping, to the type the rest of the server reads
        JsonNode json = readFixture("OracleStatusResponse");
        ChainSvcRestClient.OracleStatusResponse dto =
                mapper.treeToValue(json, ChainSvcRestClient.OracleStatusResponse.class);
        ChainSvcRestClient rest = mock(ChainSvcRestClient.class);
        when(rest.oracleStatus(any())).thenReturn(dto);
        GameConfig config = mock(GameConfig.class);
        when(config.chainsvcToken()).thenReturn("t");
        ChainSvcClientImpl impl = new ChainSvcClientImpl();
        impl.rest = rest;
        impl.config = config;

        ChainSvcClient.OracleStatus status = impl.oracleStatus();

        assertThat(status.maxKnownSeq()).isEqualTo(209L);
        assertThat(status.recordFormat()).isEqualTo(2);
        assertThat(status.supportsAssets()).isTrue();
        assertThat(status.feeCoinsFree()).isEqualTo(4);
        assertThat(status.feeCoinsFreeMojos()).isEqualTo("800000000");
        assertThat(status.lastConfirmedSeq()).isEqualTo(205L);
        assertThat(status.pendingRecords()).isEqualTo(4);
    }

    // --- PLAY withdrawals and deposits (Р-53, /v1/play/*) ---

    @Test
    void withdrawalRequestFixtureRoundTrips() throws IOException {
        JsonNode json = readFixture("WithdrawalRequestDto");
        ChainSvcRestClient.PlayWithdrawalRequest dto =
                mapper.treeToValue(json, ChainSvcRestClient.PlayWithdrawalRequest.class);

        assertThat(dto.requestId()).isEqualTo("w-205");
        assertThat(dto.toAddress()).isEqualTo(json.get("toAddress").asText());
        assertThat(dto.amountMojos()).isEqualTo(500_000L);
        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }

    @Test
    void withdrawalAcceptedResponseFixtureRoundTrips() throws IOException {
        JsonNode json = readFixture("WithdrawalAcceptedResponse");
        ChainSvcRestClient.PlayWithdrawalAcceptedResponse dto =
                mapper.treeToValue(json, ChainSvcRestClient.PlayWithdrawalAcceptedResponse.class);

        assertThat(dto.accepted()).isTrue();
        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }

    @Test
    void withdrawalStatusConfirmedFixtureRoundTrips() throws IOException {
        JsonNode json = readFixture("WithdrawalStatusResponse_confirmed");
        ChainSvcRestClient.PlayWithdrawalStatusResponse dto =
                mapper.treeToValue(json, ChainSvcRestClient.PlayWithdrawalStatusResponse.class);

        assertThat(dto.requestId()).isEqualTo("w-205");
        assertThat(dto.state()).isEqualTo("confirmed");
        assertThat(dto.coinId()).isEqualTo(json.get("coinId").asText());
        assertThat(dto.height()).isEqualTo(4_737_632L);
        assertThat(dto.reason()).isNull();
        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }

    @Test
    void withdrawalStatusFailedFixtureRoundTrips() throws IOException {
        JsonNode json = readFixture("WithdrawalStatusResponse_failed");
        ChainSvcRestClient.PlayWithdrawalStatusResponse dto =
                mapper.treeToValue(json, ChainSvcRestClient.PlayWithdrawalStatusResponse.class);

        assertThat(dto.requestId()).isEqualTo("w-206");
        assertThat(dto.state()).isEqualTo("failed");
        assertThat(dto.coinId()).isNull();
        assertThat(dto.height()).isNull();
        assertThat(dto.reason()).isEqualTo(json.get("reason").asText());
        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }

    @Test
    void withdrawalStatusQueuedFixtureRoundTrips() throws IOException {
        JsonNode json = readFixture("WithdrawalStatusResponse_queued");
        ChainSvcRestClient.PlayWithdrawalStatusResponse dto =
                mapper.treeToValue(json, ChainSvcRestClient.PlayWithdrawalStatusResponse.class);

        assertThat(dto.requestId()).isEqualTo("w-207");
        assertThat(dto.state()).isEqualTo("queued");
        assertThat(dto.coinId()).isNull();
        assertThat(dto.height()).isNull();
        assertThat(dto.reason()).isNull();
        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }

    @Test
    void depositAddressRequestFixtureRoundTrips() throws IOException {
        JsonNode json = readFixture("DepositAddressRequestDto");
        ChainSvcRestClient.PlayDepositAddressRequest dto =
                mapper.treeToValue(json, ChainSvcRestClient.PlayDepositAddressRequest.class);

        assertThat(dto.playerIndex()).isEqualTo(7);
        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }

    @Test
    void depositAddressResponseFixtureRoundTrips() throws IOException {
        JsonNode json = readFixture("DepositAddressResponse");
        ChainSvcRestClient.PlayDepositAddressResponse dto =
                mapper.treeToValue(json, ChainSvcRestClient.PlayDepositAddressResponse.class);

        assertThat(dto.address()).isEqualTo(json.get("address").asText());
        assertThat(dto.innerPuzzleHash()).isEqualTo(json.get("innerPuzzleHash").asText());
        assertThat(dto.catPuzzleHash()).isEqualTo(json.get("catPuzzleHash").asText());
        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }

    @Test
    void depositsResponseFixtureRoundTrips() throws IOException {
        JsonNode json = readFixture("DepositsResponse");
        ChainSvcRestClient.PlayDepositsResponse dto =
                mapper.treeToValue(json, ChainSvcRestClient.PlayDepositsResponse.class);

        assertThat(dto.tipHeight()).isEqualTo(4_737_650L);
        assertThat(dto.deposits()).hasSize(1);
        ChainSvcRestClient.PlayDepositItem deposit = dto.deposits().get(0);
        assertThat(deposit.coinId()).isEqualTo(json.get("deposits").get(0).get("coinId").asText());
        assertThat(deposit.playerIndex()).isEqualTo(7);
        assertThat(deposit.amountMojos()).isEqualTo(250_000L);
        assertThat(deposit.height()).isEqualTo(4_737_640L);
        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }
}
