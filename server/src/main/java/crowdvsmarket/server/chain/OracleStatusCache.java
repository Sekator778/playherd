package crowdvsmarket.server.chain;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;

/**
 * Предложение (additive, bug fix): {@code GET /v1/oracle/status} on chainsvc has been observed on
 * the local stand to take anywhere from well under a second to several seconds — occasionally
 * past the client's own 5s read-timeout ({@code quarkus.rest-client.chainsvc.read-timeout}) —
 * even though the TCP connection itself is instant (raw curl to the same
 * {@code herd.chainsvc.base-url} address connects in low single-digit milliseconds; the slowness
 * is on chainsvc's own handler, not the network). {@link crowdvsmarket.server.service.GameService
 * #serverInfo} used to call {@link ChainSvcClient#oracleStatus()} synchronously on every RPC, so a
 * slow chainsvc round-trip blocked the calling request thread for up to 5s each time serverInfo
 * was polled.
 *
 * <p>This bean owns a single background refresh instead — its own {@code @Scheduled} tick, never
 * on a request thread, and always at most one {@code oracleStatus()} call in flight regardless of
 * how many concurrent {@code serverInfo()} callers there are (also sidesteps any REST-client
 * connection-pool contention that concurrent request-thread calls would otherwise cause). "At most
 * one" includes the tick itself: it is {@code concurrentExecution = SKIP}, because Quarkus's default
 * ({@code PROCEED}) would start a second refresh while a slow one — the several seconds above — is
 * still waiting, which is exactly the overlap this bean exists to prevent.
 *
 * {@link #get()} returns the last known-good status instantly. A failed refresh keeps serving the
 * previous value rather than flipping to {@code null} — matches {@link ChainSvcClient#oracleStatus()}'s
 * own "callers degrade gracefully" contract (see its javadoc); {@code null} only before the very
 * first successful refresh since this process started.
 *
 * <p>Deliberately injects {@link ChainSvcClient} (the mockable interface, {@code FIXED INTERFACE
 * (3)}), not {@link ChainSvcRestClient} directly — same pattern {@code OraclePublisherService}
 * already uses, so {@code @InjectMock ChainSvcClient} in tests fully controls what this cache
 * refreshes from, same as every other caller.
 *
 * <p>Record format 2 (docs/research/chain_service.md §16.3) adds fields to the status
 * ({@code maxKnownSeq}, {@code recordFormat}, {@code feeCoinsFree}, {@code feeCoinsFreeMojos}); all
 * are nullable and this cache stores the status as it came. It is a display copy for {@code
 * serverInfo}: nothing that decides what is sent to the chain service reads it — {@link
 * OracleAssetGuard} and {@link ChainOracleSeqGate} read the status themselves, because "the last good
 * value" is the wrong answer to "what does the chain service say now".
 */
@ApplicationScoped
public class OracleStatusCache {

    private static final Logger LOG = Logger.getLogger(OracleStatusCache.class);

    @Inject
    ChainSvcClient chainSvc;

    private final AtomicReference<ChainSvcClient.OracleStatus> lastGood = new AtomicReference<>();

    @Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refresh() {
        try {
            ChainSvcClient.OracleStatus status = chainSvc.oracleStatus();
            if (status != null) {
                lastGood.set(status);
            }
            // null (chainsvc unreachable, ChainSvcClientImpl already caught/logged it) — keep
            // serving whatever we cached last, per this class's javadoc.
        } catch (Exception e) {
            // Backstop only: ChainSvcClientImpl#oracleStatus() already never throws. Guards
            // against a non-conforming ChainSvcClient (e.g. a test double that rethrows) so a bad
            // tick here can never take down this bean's scheduler thread.
            LOG.debugf(e, "oracle status refresh failed — keeping last known-good status");
        }
    }

    /** Never blocks — see class javadoc. {@code null} only before the first successful refresh. */
    public ChainSvcClient.OracleStatus get() {
        return lastGood.get();
    }
}
