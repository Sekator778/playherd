package crowdvsmarket.server.chain;

/**
 * Says whether this server may hand out oracle sequence numbers yet (docs/research/
 * markets_architecture.md §7 item 4). One oracle singleton lives on chain for good, while a server
 * database can be new: a fresh database would start its {@code seq} at 1 although the chain is
 * already far ahead, and every such record would be rejected as a replay. So before the first
 * {@code seq} is issued, the server raises its counter to the chain service's
 * {@code maxKnownSeq} ("the floor").
 *
 * <p>FIXED INTERFACE between the round scheduler (asks {@link #open()} around a settlement and nowhere
 * else: only a settlement reserves a {@code seq} — a cancellation, a refund and the jump of a series over empty
 * windows write no oracle record, so they never wait for the gate) and the oracle wiring (owns the floor).
 */
public interface OracleSeqGate {

    /**
     * True when settlements may run: either the floor is not required
     * ({@code herd.oracle.require-seq-floor=false}), or it has been obtained from the chain
     * service at least once since this process started. Cheap and non-blocking — it is called on
     * every scheduler tick.
     */
    boolean open();
}
