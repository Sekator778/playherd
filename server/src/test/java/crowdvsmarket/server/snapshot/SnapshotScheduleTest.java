package crowdvsmarket.server.snapshot;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two jobs of {@link SnapshotService} are registered with the scheduler under the settings production uses: the cue every 30
 * seconds, and the nightly check on the cron the application ships with (a cron the scheduler cannot read would stop the server at
 * start). The tests everywhere else switch the check off.
 */
@QuarkusTest
@TestProfile(SnapshotScheduleTest.ProductionCron.class)
class SnapshotScheduleTest {

    public static class ProductionCron implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("herd.snapshot.check-cron", "0 30 3 * * ?", "herd.mode", "live", "herd.bots.count", "0");
        }
    }

    @Inject
    Scheduler scheduler;

    @Test
    void theCueAndTheNightlyCheckAreScheduled() {
        assertThat(scheduler.getScheduledJob("herd-snapshot")).as("the cue").isNotNull();
        assertThat(scheduler.getScheduledJob("herd-snapshot-check")).as("the nightly check").isNotNull();
    }
}
