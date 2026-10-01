package crowdvsmarket.server.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link OracleAssetGuard}: records of a market other than BTC may be sent only when the chain service
 * has just said it understands record format 2 — every other way of not knowing is "no", because a chain
 * service that does not understand {@code asset} publishes such a record as a BTC price, on a public chain.
 * Plain unit test: the chain service is {@link FakeChainSvc}.
 */
class OracleAssetGuardTest {

    private final FakeChainSvc chain = new FakeChainSvc();
    private final OracleAssetGuard guard = new OracleAssetGuard();

    @BeforeEach
    void wire() {
        guard.chainSvc = chain;
    }

    @Test
    void recordFormatTwoSupportsAssets() {
        chain.recordFormat = 2;

        assertThat(guard.assetsSupported()).isTrue();
    }

    @Test
    void aLaterFormatKeepsTheFieldAndSupportsAssets() {
        chain.recordFormat = 3;

        assertThat(guard.assetsSupported()).isTrue();
    }

    @Test
    void recordFormatOneDoesNot() {
        chain.recordFormat = 1;

        assertThat(guard.assetsSupported()).isFalse();
    }

    @Test
    void aStatusWithoutRecordFormatIsAChainServiceOlderThanFormatTwo() {
        chain.recordFormat = null;

        assertThat(guard.assetsSupported()).isFalse();
    }

    @Test
    void aChainServiceThatDoesNotAnswerIsNotKnownToSupportAssets() {
        chain.down = true;

        assertThat(guard.assetsSupported()).isFalse();
    }

    @Test
    void aChainServiceClientThatThrowsIsNotKnownToSupportAssetsEither() {
        ChainSvcClient broken = mock(ChainSvcClient.class);
        when(broken.oracleStatus()).thenThrow(new IllegalStateException("boom"));
        guard.chainSvc = broken;

        assertThat(guard.assetsSupported()).isFalse();
    }

    @Test
    void theAnswerIsAlwaysReadLiveSoARolledBackChainServiceIsNotBelievedFormatTwoForAMoment() {
        chain.recordFormat = 2;
        assertThat(guard.assetsSupported()).isTrue();

        chain.recordFormat = 1; // chainsvc restarted on the older image
        assertThat(guard.assetsSupported()).isFalse();

        chain.recordFormat = 2;
        assertThat(guard.assetsSupported()).isTrue();
        assertThat(chain.statusReads()).isEqualTo(3);
    }
}
