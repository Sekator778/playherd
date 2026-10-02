package crowdvsmarket.server.chain;

import crowdvsmarket.server.persistence.ServerMetaRepository;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The real {@link OracleSeqGate} (docs/research/markets_architecture.md §7 п.4): owns the oracle seq
 * floor. One oracle singleton lives on chain for good, while a server database can be new — a fresh
 * database would start its {@code seq} at 1 although the chain already holds 1..N, and every such
 * record would be rejected as a replay (or silently ignored as already known). So before the first
 * {@code seq} is issued, and again whenever the chain service knows more than this server does, the
 * server's counter is raised to the chain service's {@code maxKnownSeq}
 * ({@link ServerMetaRepository#raiseOracleSeqTo}: monotonic, never lowers).
 *
 * <ul>
 *   <li>{@code herd.oracle.require-seq-floor=false} (the default; the local stand): {@link #open()}
 *       is always true. The counter is still raised whenever a status can be read — the flag only
 *       decides whether settlement waits for it.
 *   <li>{@code herd.oracle.require-seq-floor=true} (the stand and the VPS, {@code
 *       HERD_ORACLE_REQUIRE_SEQ_FLOOR}): {@link #open()} turns true once this process has read the
 *       chain service's status successfully and raised the counter to its {@code maxKnownSeq}, and stays
 *       true — a chain service that goes away later does not stop the game; the floor was obtained for
 *       this process. Until then the scheduler settles nothing (it asks {@link #open()} before each
 *       settlement, the only thing that reserves a {@code seq}; the refund of a round that is an hour late
 *       and the jump over empty windows go on) and rounds queue up, to be caught up when the
 *       gate opens. A status without {@code maxKnownSeq} (a chain service older than record format 2)
 *       <b>never</b> opens a required gate: there is no floor to obtain, and guessing one is how a
 *       fresh database ends up replaying history.
 * </ul>
 *
 * <p>{@link #refresh()} runs every 5 s (never overlapping itself) and does both the retry until the
 * first success and the later re-raise: another writer, or a chain service that finished its bootstrap
 * from the chain after the first answer, shows up as a higher {@code maxKnownSeq}. It reads the status
 * itself ({@link ChainSvcClient#oracleStatus()}, never {@link OracleStatusCache}): the cache keeps
 * serving the last good value when the chain service goes away, and "read successfully since this
 * process started" must mean a real read.
 */
@ApplicationScoped
public class ChainOracleSeqGate implements OracleSeqGate {

    private static final Logger LOG = Logger.getLogger(ChainOracleSeqGate.class);

    @ConfigProperty(name = "herd.oracle.require-seq-floor", defaultValue = "false")
    boolean requireSeqFloor;

    @Inject
    ChainSvcClient chainSvc;
    @Inject
    ServerMetaRepository serverMeta;

    private final LogThrottle warnings = new LogThrottle(60_000);

    /** The highest {@code maxKnownSeq} this process has raised the counter to; -1 before the first. */
    private volatile long raisedTo = -1;
    private volatile boolean floorObtained;

    @Override
    public boolean open() {
        return !requireSeqFloor || floorObtained;
    }

    @Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refresh() {
        ChainSvcClient.OracleStatus status;
        try {
            status = chainSvc.oracleStatus();
        } catch (RuntimeException e) {
            // ChainSvcClientImpl never throws; a test double or a future implementation might.
            LOG.debugf(e, "reading the chain service status failed");
            status = null;
        }
        if (status == null) {
            if (!floorObtained) {
                warnWhileClosed("unreachable", "the chain service status is unavailable");
            }
            return;
        }
        warnings.reset("unreachable");
        Long maxKnownSeq = status.maxKnownSeq();
        if (maxKnownSeq == null) {
            if (!floorObtained) {
                warnWhileClosed("no-max-known-seq", "the chain service status has no maxKnownSeq (a chain service "
                        + "older than record format 2) — there is no seq floor to obtain, and the gate stays closed");
            }
            return;
        }
        warnings.reset("no-max-known-seq");
        if (floorObtained && maxKnownSeq <= raisedTo) {
            return; // nothing new: this process already raised the counter at least that far
        }
        try {
            long counter = serverMeta.raiseOracleSeqTo(maxKnownSeq);
            warnings.reset("raise-failed");
            raisedTo = Math.max(raisedTo, maxKnownSeq);
            if (!floorObtained) {
                floorObtained = true;
                LOG.infof("oracle seq floor obtained: chainsvc maxKnownSeq=%d, the counter is at %d — "
                        + "settlement may issue seq %d and on", maxKnownSeq, counter, counter + 1);
            }
        } catch (RuntimeException e) {
            // A floor outside the safe range, or a database that does not answer: not obtained.
            if (warnings.ready("raise-failed")) {
                LOG.warnf(e, "could not raise the oracle seq counter to chainsvc's maxKnownSeq=%d — will retry",
                        maxKnownSeq);
            }
        }
    }

    /** A condition that keeps a required gate closed — loud, since settlement waits on it — or, with the
     *  floor not required, only worth a debug line (the game does not wait for it). */
    private void warnWhileClosed(String key, String what) {
        if (requireSeqFloor) {
            if (warnings.ready(key)) {
                LOG.warnf("%s: rounds are not settled until the oracle seq floor is obtained "
                        + "(herd.oracle.require-seq-floor=true)", what);
            }
        } else {
            LOG.debugf("%s (herd.oracle.require-seq-floor=false: settlement does not wait for the floor)", what);
        }
    }
}
