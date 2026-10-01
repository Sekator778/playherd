package crowdvsmarket.server.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * With nothing configured ({@code herd.oracle.require-seq-floor} unset — the default, and the local stand)
 * the application's {@link OracleSeqGate} is the real {@link ChainOracleSeqGate} and is open at once: the
 * floor is only required where the operator asks for it. (The required case is
 * {@link OracleSeqGateWiringTest}.)
 */
@QuarkusTest
class OracleSeqGateDefaultTest {

    @Inject
    OracleSeqGate gate;

    @Test
    void theGateIsOpenWithoutAnyConfiguration() {
        assertThat(gate).isInstanceOf(ChainOracleSeqGate.class);
        assertThat(gate.open()).isTrue();
    }
}
