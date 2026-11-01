package crowdvsmarket.server.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.scheduler.Scheduled;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * docs/research/markets_architecture.md §4 item 4: the scheduler tick must not overlap itself. Quarkus's
 * default is {@code concurrentExecution = PROCEED} (verified in quarkus-scheduler-api: the attribute's
 * default is {@code ConcurrentExecution.PROCEED}), under which a tick still busy at the next second
 * would run side by side with it — so the SKIP is pinned here.
 */
class RoundSchedulerTickTest {

    @Test
    void theScheduledTickSkipsInsteadOfOverlapping() throws Exception {
        Method tick = RoundSchedulerService.class.getDeclaredMethod("tick");

        Scheduled scheduled = tick.getAnnotation(Scheduled.class);

        assertThat(scheduled).as("@Scheduled on RoundSchedulerService#tick()").isNotNull();
        assertThat(scheduled.concurrentExecution()).isEqualTo(Scheduled.ConcurrentExecution.SKIP);
        assertThat(scheduled.every()).isEqualTo("1s");
    }

    @Test
    void quarkusDefaultWouldHaveBeenProceed() throws Exception {
        // the reason for the pin above, kept as a fact: an unannotated @Scheduled overlaps by default
        Method defaultValue = Scheduled.class.getDeclaredMethod("concurrentExecution");

        assertThat(defaultValue.getDefaultValue()).isEqualTo(Scheduled.ConcurrentExecution.PROCEED);
    }
}
