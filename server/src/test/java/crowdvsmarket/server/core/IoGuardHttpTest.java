package crowdvsmarket.server.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import crowdvsmarket.server.jfr.TimedEngineLock;
import crowdvsmarket.server.persistence.Db;
import crowdvsmarket.server.prices.KrakenApi;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.URI;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.junit.jupiter.api.Test;

/** The guard in the real wiring: {@link Db} and a REST client of the application stop at the guard under the engine write lock. */
@QuarkusTest
class IoGuardHttpTest {

    @Inject
    Db db;

    @Test
    void theGuardIsOnInTheServerTests() {
        assertThat(IoGuard.mode()).isEqualTo(IoGuard.Mode.FAIL);
    }

    @Test
    void aRestClientCallUnderTheEngineLockStopsAtTheGuardBeforeAnyConnection() {
        KrakenApi client = RestClientBuilder.newBuilder().baseUri(URI.create("http://127.0.0.1:1")).build(KrakenApi.class);
        TimedEngineLock lock = new TimedEngineLock();
        lock.writeLock().lock();
        try {
            assertThatThrownBy(() -> client.ticker("XBTUSD")).hasStackTraceContaining("engine I/O rule")
                    .hasStackTraceContaining("network");
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Test
    void aDatabaseCallUnderTheEngineLockStopsAtTheGuard() {
        TimedEngineLock lock = new TimedEngineLock();
        lock.writeLock().lock();
        try {
            assertThatThrownBy(() -> db.read(c -> 1)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("database");
            assertThatThrownBy(() -> db.tx(c -> 1)).isInstanceOf(IllegalStateException.class);
        } finally {
            lock.writeLock().unlock();
        }
        assertThat((int) db.<Integer>read(c -> 1)).isEqualTo(1);
    }
}
