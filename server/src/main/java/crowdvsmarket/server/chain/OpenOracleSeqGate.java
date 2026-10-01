package crowdvsmarket.server.chain;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Fallback {@link OracleSeqGate} used only while no other implementation is on the classpath
 * ({@link DefaultBean}): always open. The oracle wiring replaces it.
 */
@DefaultBean
@ApplicationScoped
public class OpenOracleSeqGate implements OracleSeqGate {

    @Override
    public boolean open() {
        return true;
    }
}
