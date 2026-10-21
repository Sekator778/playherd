package crowdvsmarket.server.markets.catalog;

import crowdvsmarket.server.dto.ApiException;
import crowdvsmarket.server.dto.Enums;
import org.jboss.logging.Logger;

/**
 * Runs a catalog job, and treats an engine that is still being built at start as "not yet": the registry and the game answer
 * {@code ENGINE_NOT_READY} until the first rebuild is done (the registry no longer loads lazily inside the writer's turn), and the
 * next tick of the job runs again. Any other failure is the job's own and propagates as before.
 */
final class WhenReady {

    private static final Logger LOG = Logger.getLogger(WhenReady.class);

    private WhenReady() {
    }

    static void run(String job, Runnable body) {
        try {
            body.run();
        } catch (ApiException e) {
            if (e.code() != Enums.ApiErrorCode.ENGINE_NOT_READY) {
                throw e;
            }
            LOG.debugf("%s: the engine is not ready yet - the next tick runs", job);
        }
    }
}
