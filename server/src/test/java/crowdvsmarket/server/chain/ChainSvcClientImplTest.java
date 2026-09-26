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
                "launcher", "testnet11", 1L, 100L, 0, null, "0", null, java.util.List.of()));

        impl.oracleStatus();

        verify(rest).oracleStatus(eq("Bearer " + TOKEN));
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
}
