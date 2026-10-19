package crowdvsmarket.server.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The clock of the ledger history projection: every second it asks {@link GameService#flushLedgerRows} to write the
 * movements the engine has produced to the {@code ledger_entries} table. That is also the retry of a write that failed
 * (the movements wait in the hub's outbox meanwhile) and what writes the remainder a rebuild handed over.
 */
@ApplicationScoped
public class LedgerRowsFlusher {

    private static final Logger LOG = Logger.getLogger(LedgerRowsFlusher.class);

    @Inject
    GameService gameService;

    // SKIP: a write slower than a second must not overlap the next one (the flush is serialised anyway).
    @Scheduled(every = "${herd.ledger-rows.every:1s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void flush() {
        try {
            gameService.flushLedgerRows();
        } catch (RuntimeException e) {
            LOG.warnf(e, "the ledger history flush failed unexpectedly");
        }
    }
}
