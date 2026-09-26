package crowdvsmarket.server.chain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Task item 5 (contract test Java<->Rust, docs/reviews/2026-09-26-independent-critique.md): the
 * only Java-side tests of the server<->chainsvc boundary ({@link ChainSvcClientImplTest},
 * {@code OraclePublisherServiceTest}) mock {@link ChainSvcClient}/{@link ChainSvcRestClient}
 * directly rather than exercising the real HTTP/JSON contract against chain/c2-svc's actual
 * request/response shapes — a silent field-name/type drift on either side would not have been
 * caught by any automated test. This reads the SAME committed JSON fixtures
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
 */
@QuarkusTest
class ChainSvcContractFixturesTest {

    /** Same module layout both directions rely on: chain/ and server/ are sibling directories
     *  under the repo root, and Surefire's forked JVM working directory is this module's own
     *  basedir ({@code server/}) — see e.g. {@code EventStoreGoldenFixturesTest}'s own
     *  module-relative fixture path for the same convention. */
    private static final Path CONTRACT_DIR = Path.of("../chain/c2-svc/tests/contract");

    @Inject
    ObjectMapper mapper;

    private JsonNode readFixture(String name) throws IOException {
        Path path = CONTRACT_DIR.resolve(name + ".json");
        assertThat(Files.exists(path))
                .as("missing contract fixture %s — run `cargo test -p c2-svc --test contract_fixtures` in chain/", path)
                .isTrue();
        return mapper.readTree(Files.readString(path));
    }

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

        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
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

        assertThat(mapper.readTree(mapper.writeValueAsString(dto))).isEqualTo(json);
    }
}
