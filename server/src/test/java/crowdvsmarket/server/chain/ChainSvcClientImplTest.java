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
                "launcher", "testnet11", 1L, 100L, 0, null, "0"));

        impl.oracleStatus();

        verify(rest).oracleStatus(eq("Bearer " + TOKEN));
    }

    @Test
    void healthNeverSendsAToken() {
        when(rest.health()).thenReturn(new ChainSvcRestClient.HealthResponse(true));

        assertThat(impl.health()).isTrue();
        // health() takes no Authorization parameter at all (FIXED INTERFACE a explicitly exempts
        // GET /health) — nothing more to verify beyond the interface shape itself compiling.
    }
}
