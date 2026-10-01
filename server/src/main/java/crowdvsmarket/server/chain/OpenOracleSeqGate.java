package crowdvsmarket.server.chain;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Fallback {@link OracleSeqGate} used only while no other implementation is on the classpath
 * ({@link DefaultBean}): always open. {@link ChainOracleSeqGate}, the oracle wiring, replaces it —
 * this class is never the active bean any more and stays only as an "always open" gate for code that
 * needs one without a chain service (a plain unit test).
 */
@DefaultBean
@ApplicationScoped
public class OpenOracleSeqGate implements OracleSeqGate {

    @Override
    public boolean open() {
        return true;
    }
}
