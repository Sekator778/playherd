package crowdvsmarket.server.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The seq floor as the application wires it: the {@link OracleSeqGate} the round scheduler injects is
 * {@link ChainOracleSeqGate}, not the always-open fallback, and its requirement comes from the config key
 * {@code herd.oracle.require-seq-floor} (SmallRye reads {@code HERD_ORACLE_REQUIRE_SEQ_FLOOR} into it, which
 * is how the stand and the VPS switch it on — a misspelled key would leave the gate silently never
 * required). The chain service is {@link FakeChainSvc} behind the {@code @InjectMock} mock; the Quarkus
 * scheduler is off, so the test makes the 5-second tick itself.
 */
@QuarkusTest
@TestProfile(OracleSeqGateWiringTest.RequiredFloorProfile.class)
class OracleSeqGateWiringTest {

    public static class RequiredFloorProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "herd.oracle.require-seq-floor", "true",
                    "herd.bots.count", "0",
                    "quarkus.scheduler.enabled", "false");
        }
    }

    @InjectMock
    ChainSvcClient chainSvcClient;

    @Inject
    OracleSeqGate gate;
    @Inject
    ChainOracleSeqGate chainGate;

    @Test
    void theInjectedGateIsTheRealOneAndTheConfigKeyMakesItRequiredUntilTheFloorIsObtained() {
        assertThat(gate).isInstanceOf(ChainOracleSeqGate.class);

        FakeChainSvc chain = new FakeChainSvc().install(chainSvcClient);
        chain.down = true; // the chain service is not up yet
        chainGate.refresh();
        assertThat(gate.open()).as("required (herd.oracle.require-seq-floor=true): closed until the floor is read")
                .isFalse();

        chain.down = false;
        chain.confirmedUpTo = 0; // an empty chain service: floor 0, which leaves the counter alone
        chainGate.refresh();
        assertThat(gate.open()).isTrue();
    }
}
