package crowdvsmarket.server.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import crowdvsmarket.server.config.GameConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Plain unit test (no {@code @QuarkusTest} needed — {@link ChainSvcRestClient} is a plain
 * interface, mocked directly): FIXED INTERFACE (a) — every {@code /v1/*} call except
 * {@code GET /health} carries {@code Authorization: Bearer <token>}.
 */
class ChainSvcClientImplTest {

    private static final String TOKEN = "test-chainsvc-token-abc123";

    private final ChainSvcRestClient rest = mock(ChainSvcRestClient.class);
    private final GameConfig config = mock(GameConfig.class);
    private final ChainSvcClientImpl impl = new ChainSvcClientImpl();

    @BeforeEach
    void setUp() {
        impl.rest = rest;
        impl.config = config;
        when(config.chainsvcToken()).thenReturn(TOKEN);
    }

    @Test
    void authVerifySendsBearerToken() {
        when(rest.authVerify(any(), any())).thenReturn(new ChainSvcRestClient.AuthVerifyResponse(true, null));

        impl.authVerify("addr", "pk", "msg", "sig");

        verify(rest).authVerify(eq("Bearer " + TOKEN),
                eq(new ChainSvcRestClient.AuthVerifyRequest("addr", "pk", "msg", "sig")));
    }

    @Test
    void oracleRecordsSendsBearerTokenAndMapsAcceptedSeqs() {
        when(rest.oracleRecords(any(), any()))
                .thenReturn(new ChainSvcRestClient.OracleRecordsResponse(1, List.of(42L)));

        ChainSvcClient.OracleRecordsResult result = impl.oracleRecords(
                List.of(new ChainSvcClient.OracleRecord(42L, 1000L, 500000L, "0xhash", "replay", 1000L)));

        verify(rest).oracleRecords(eq("Bearer " + TOKEN), any());
        assertThat(result.queued()).isEqualTo(1);
        assertThat(result.acceptedSeqs()).containsExactly(42L);
    }

    @Test
    void oracleRecordsSendsEveryRecordsAssetAndABtcRecordIsAssetZero() {
        when(rest.oracleRecords(any(), any()))
                .thenReturn(new ChainSvcRestClient.OracleRecordsResponse(2, List.of(42L, 43L)));

        impl.oracleRecords(List.of(
                new ChainSvcClient.OracleRecord(42L, 1000L, 500000L, "0xhash", "live", 1000L, 3),
                new ChainSvcClient.OracleRecord(43L, 1001L, 8561200L, "0xhash2", "live", 1001L)));

        ArgumentCaptor<ChainSvcRestClient.OracleRecordsRequest> sent =
                ArgumentCaptor.forClass(ChainSvcRestClient.OracleRecordsRequest.class);
        verify(rest).oracleRecords(eq("Bearer " + TOKEN), sent.capture());
        assertThat(sent.getValue().records()).containsExactly(
                new ChainSvcRestClient.OracleRecordJson(42L, 1000L, 500000L, "0xhash", "live", 1000L, 3),
                new ChainSvcRestClient.OracleRecordJson(43L, 1001L, 8561200L, "0xhash2", "live", 1001L, 0));
    }

    @Test
    void oracleRecordsDegradesGracefullyWhenChainsvcUnreachable() {
        when(rest.oracleRecords(any(), any())).thenThrow(new RuntimeException("connection refused"));

        ChainSvcClient.OracleRecordsResult result = impl.oracleRecords(
                List.of(new ChainSvcClient.OracleRecord(1L, 1000L, 500000L, "0xhash", "replay", 1000L)));

        assertThat(result.queued()).isZero();
        assertThat(result.acceptedSeqs()).isEmpty();
    }

    @Test
    void oracleRecordStatusSendsBearerToken() {
        when(rest.oracleRecordStatus(any(), anyLong()))
                .thenReturn(new ChainSvcRestClient.OracleRecordStateResponse(7L, "confirmed", 123456L, "0xcoin"));

        ChainSvcClient.OracleRecordState state = impl.oracleRecordStatus(7L);

        verify(rest).oracleRecordStatus(eq("Bearer " + TOKEN), eq(7L));
        assertThat(state.state()).isEqualTo("confirmed");
        assertThat(state.height()).isEqualTo(123456L);
        assertThat(state.coinId()).isEqualTo("0xcoin");
    }

    @Test
    void oracleRecordStatusReturnsNullWhenChainsvcUnreachable() {
        when(rest.oracleRecordStatus(any(), anyLong())).thenThrow(new RuntimeException("timeout"));

        assertThat(impl.oracleRecordStatus(7L)).isNull();
    }

    @Test
    void oracleStatusSendsBearerToken() {
        when(rest.oracleStatus(any())).thenReturn(new ChainSvcRestClient.OracleStatusResponse(
                "launcher", "testnet11", 1L, 100L, 0, null, "0", null, java.util.List.of(), null, null, null, null));

        impl.oracleStatus();

        verify(rest).oracleStatus(eq("Bearer " + TOKEN));
    }

    @Test
    void oracleStatusCarriesTheRecordFormatTwoFields() {
        when(rest.oracleStatus(any())).thenReturn(new ChainSvcRestClient.OracleStatusResponse(
                "launcher", "testnet11", 205L, 4737632L, 4, null, "999999999", null, List.of(), 209L, 2, 4,
                "800000000"));

        ChainSvcClient.OracleStatus status = impl.oracleStatus();

        assertThat(status.maxKnownSeq()).isEqualTo(209L);
        assertThat(status.recordFormat()).isEqualTo(2);
        assertThat(status.feeCoinsFree()).isEqualTo(4);
        assertThat(status.feeCoinsFreeMojos()).isEqualTo("800000000");
        assertThat(status.effectiveRecordFormat()).isEqualTo(2);
        assertThat(status.supportsAssets()).isTrue();
    }

    @Test
    void aChainServiceThatSendsNoRecordFormatCountsAsFormatOneAndTheNewFieldsStayNull() {
        // what chainsvc answered before record format 2: none of the four fields (Jackson leaves them null)
        when(rest.oracleStatus(any())).thenReturn(new ChainSvcRestClient.OracleStatusResponse(
                "launcher", "testnet11", 1145L, 4735705L, 0, null, "10000000000", null, null, null, null, null, null));

        ChainSvcClient.OracleStatus status = impl.oracleStatus();

        assertThat(status.recordFormat()).isNull();
        assertThat(status.maxKnownSeq()).isNull();
        assertThat(status.feeCoinsFree()).isNull();
        assertThat(status.feeCoinsFreeMojos()).isNull();
        assertThat(status.effectiveRecordFormat()).isEqualTo(1);
        assertThat(status.supportsAssets()).isFalse();
        assertThat(status.conflicts()).isEmpty(); // null from the wire → empty, as before
    }

    @Test
    void recordFormatsBelowTwoDoNotSupportAssets() {
        ChainSvcClient.OracleStatus one = new ChainSvcClient.OracleStatus("l", "n", null, null, 0, null, null, null,
                List.of(), 5L, 1, null, null);
        ChainSvcClient.OracleStatus three = new ChainSvcClient.OracleStatus("l", "n", null, null, 0, null, null, null,
                List.of(), 5L, 3, null, null);
        // the nine-argument form is the status of a chainsvc that predates the format-2 fields
        ChainSvcClient.OracleStatus legacy = new ChainSvcClient.OracleStatus("l", "n", null, null, 0, null, null, null,
                List.of());

        assertThat(one.supportsAssets()).isFalse();
        assertThat(three.supportsAssets()).isTrue(); // a later format keeps the field
        assertThat(legacy.supportsAssets()).isFalse();
        assertThat(legacy.maxKnownSeq()).isNull();
    }

    // --- Р-53: real on-chain PLAY withdraw/deposit ---

    @Test
    void submitPlayWithdrawalSendsBearerTokenAndMapsAccepted() {
        when(rest.submitPlayWithdrawal(any(), any()))
                .thenReturn(new ChainSvcRestClient.PlayWithdrawalAcceptedResponse(true));

        ChainSvcClient.PlayWithdrawalSubmitResult result = impl.submitPlayWithdrawal("w-1", "txch1dest", 500_000L);

        verify(rest).submitPlayWithdrawal(eq("Bearer " + TOKEN),
                eq(new ChainSvcRestClient.PlayWithdrawalRequest("w-1", "txch1dest", 500_000L)));
        assertThat(result.accepted()).isTrue();
    }

    @Test
    void submitPlayWithdrawalReturnsNullWhenChainsvcUnreachable() {
        when(rest.submitPlayWithdrawal(any(), any())).thenThrow(new RuntimeException("connection refused"));

        assertThat(impl.submitPlayWithdrawal("w-1", "txch1dest", 500_000L)).isNull();
    }

    @Test
    void playWithdrawalStatusSendsBearerToken() {
        when(rest.playWithdrawalStatus(any(), eq("w-1"))).thenReturn(
                new ChainSvcRestClient.PlayWithdrawalStatusResponse("w-1", "confirmed", "0xcoin", 123L, null));

        ChainSvcClient.PlayWithdrawalStatus status = impl.playWithdrawalStatus("w-1");

        verify(rest).playWithdrawalStatus(eq("Bearer " + TOKEN), eq("w-1"));
        assertThat(status.state()).isEqualTo("confirmed");
        assertThat(status.coinId()).isEqualTo("0xcoin");
        assertThat(status.height()).isEqualTo(123L);
    }

    @Test
    void playWithdrawalStatusReturnsNullWhenChainsvcUnreachable() {
        when(rest.playWithdrawalStatus(any(), eq("w-1"))).thenThrow(new RuntimeException("timeout"));

        assertThat(impl.playWithdrawalStatus("w-1")).isNull();
    }

    @Test
    void playDepositAddressSendsBearerTokenAndPlayerIndex() {
        when(rest.playDepositAddress(any(), any())).thenReturn(
                new ChainSvcRestClient.PlayDepositAddressResponse("txch1addr", "0xinner", "0xcat"));

        ChainSvcClient.PlayDepositAddress addr = impl.playDepositAddress(7);

        verify(rest).playDepositAddress(eq("Bearer " + TOKEN), eq(new ChainSvcRestClient.PlayDepositAddressRequest(7)));
        assertThat(addr.address()).isEqualTo("txch1addr");
    }

    @Test
    void playDepositAddressReturnsNullWhenChainsvcUnreachable() {
        when(rest.playDepositAddress(any(), any())).thenThrow(new RuntimeException("connection refused"));

        assertThat(impl.playDepositAddress(7)).isNull();
    }

    @Test
    void playDepositsSendsBearerTokenAndSinceHeightAndMapsItems() {
        when(rest.playDeposits(any(), eq(1000L))).thenReturn(new ChainSvcRestClient.PlayDepositsResponse(
                List.of(new ChainSvcRestClient.PlayDepositItem("0xcoin", 3, 250_000L, 1005L)), 1010L));

        ChainSvcClient.PlayDepositsResult result = impl.playDeposits(1000L);

        verify(rest).playDeposits(eq("Bearer " + TOKEN), eq(1000L));
        assertThat(result.tipHeight()).isEqualTo(1010L);
        assertThat(result.deposits()).hasSize(1);
        assertThat(result.deposits().get(0).coinId()).isEqualTo("0xcoin");
        assertThat(result.deposits().get(0).playerIndex()).isEqualTo(3);
    }

    @Test
    void playDepositsReturnsNullWhenChainsvcUnreachable() {
        when(rest.playDeposits(any(), anyLong())).thenThrow(new RuntimeException("timeout"));

        assertThat(impl.playDeposits(1000L)).isNull();
    }

    @Test
    void healthNeverSendsAToken() {
        when(rest.health()).thenReturn(new ChainSvcRestClient.HealthResponse(true));

        assertThat(impl.health()).isTrue();
        // health() takes no Authorization parameter at all (FIXED INTERFACE a explicitly exempts
        // GET /health) — nothing more to verify beyond the interface shape itself compiling.
    }

    @Test
    void aWithdrawalIsSubmittedUnderTheIdItIsGiven() {
        when(rest.submitPlayWithdrawal(any(), any()))
                .thenReturn(new ChainSvcRestClient.PlayWithdrawalAcceptedResponse(true));

        ChainSvcClient.PlayWithdrawalSubmitResult result = impl.submitPlayWithdrawal("w-3-1a2b3c4d", "txch1to", 5_000L);

        verify(rest).submitPlayWithdrawal(eq("Bearer " + TOKEN),
                eq(new ChainSvcRestClient.PlayWithdrawalRequest("w-3-1a2b3c4d", "txch1to", 5_000L)));
        assertThat(result.accepted()).isTrue();
        assertThat(result.isConflict()).isFalse();
    }

    @Test
    void aHttp409IsAConflictResultAndAnyOtherFailureIsNull() {
        when(rest.submitPlayWithdrawal(any(), any())).thenThrow(new jakarta.ws.rs.WebApplicationException(
                jakarta.ws.rs.core.Response.status(409).type("application/json")
                        .entity("{\"error\": \"id already held with other content\"}").build()));

        ChainSvcClient.PlayWithdrawalSubmitResult conflict = impl.submitPlayWithdrawal("w-3", "txch1to", 5_000L);

        assertThat(conflict.isConflict()).isTrue();
        assertThat(conflict.accepted()).isFalse();
        assertThat(conflict.conflict()).isEqualTo("id already held with other content");

        org.mockito.Mockito.doThrow(new jakarta.ws.rs.WebApplicationException(
                jakarta.ws.rs.core.Response.status(500).build())).when(rest).submitPlayWithdrawal(any(), any());
        assertThat(impl.submitPlayWithdrawal("w-3", "txch1to", 5_000L)).isNull();

        org.mockito.Mockito.doThrow(new RuntimeException("connection refused")).when(rest)
                .submitPlayWithdrawal(any(), any());
        assertThat(impl.submitPlayWithdrawal("w-3", "txch1to", 5_000L)).isNull();
    }

    @Test
    void theStatusOfAWithdrawalIsAskedUnderTheIdItIsGiven() {
        when(rest.playWithdrawalStatus(any(), any())).thenReturn(
                new ChainSvcRestClient.PlayWithdrawalStatusResponse("w-3-1a2b3c4d", "confirmed", "0xcoin", 9L, null));

        ChainSvcClient.PlayWithdrawalStatus status = impl.playWithdrawalStatus("w-3-1a2b3c4d");

        verify(rest).playWithdrawalStatus(eq("Bearer " + TOKEN), eq("w-3-1a2b3c4d"));
        assertThat(status.state()).isEqualTo("confirmed");
    }
}
