package crowdvsmarket.server.service;

import crowdvsmarket.server.chain.ChainRequestIds;
import crowdvsmarket.server.chain.ChainSvcClient;
import crowdvsmarket.server.persistence.WithdrawalDepositRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The one place a withdrawal is handed to the chain service — by {@code GameService#requestWithdrawal} right after
 * the escrow, and by {@code WithdrawalPollerService} for every request that has not been accepted yet. Best effort
 * and never throwing: the escrow debit has happened and stands whatever the chain service does ("never block the
 * game on chain confirmation"), the poller is the safety net for a submission that did not get through.
 *
 * <p>The chain service knows the request by {@link ChainRequestIds#forWithdrawal its own id} (the engine's id
 * with the database's epoch tag, E1), and has one answer more than "accepted" or "not reachable": HTTP 409, a
 * request id it already holds with another recipient or amount (E3). That is not transient — the same submission
 * will be refused again — and must never be taken for "already paid": the withdrawal is FAILED with a plain
 * reason and its PLAY goes back to the player through the failure path every failed withdrawal takes ({@code
 * GameService#failWithdrawalOnChain}), and an ERROR says what the chain service said.
 */
@ApplicationScoped
public class WithdrawalSubmitter {

    private static final Logger LOG = Logger.getLogger(WithdrawalSubmitter.class);

    /** What the player reads as the reason of a withdrawal that the chain service refused: plain words, no
     *  internals (the chain service's own text goes to the log). */
    static final String REFUSED_REASON = "The payout was refused: its request number was already used for a "
            + "different payout. Nothing was sent, and the PLAY is back in your balance.";

    @Inject
    ChainSvcClient chainSvc;
    @Inject
    ChainRequestIds chainRequestIds;
    @Inject
    WithdrawalDepositRepository withdrawalDeposits;
    @Inject
    GameService gameService;

    /** Decides this database's namespace of chain request ids ({@link ChainRequestIds#ensureNamespace}); done before
     *  its first withdrawal row exists. */
    public void ensureNamespace() {
        chainRequestIds.ensureNamespace();
    }

    /**
     * Submits one withdrawal request: accepted — recorded as submitted, so that the poller asks about it from
     * now on; refused as a conflict — failed and refunded; anything else (an outage) — left for the next try.
     *
     * @param requestId the engine's id of the withdrawal, {@code "w-N"}
     */
    public void submit(String requestId, String toAddress, long amountMojos, long nowMs) {
        String chainRequestId = chainRequestIds.forWithdrawal(requestId);
        ChainSvcClient.PlayWithdrawalSubmitResult result;
        try {
            result = chainSvc.submitPlayWithdrawal(chainRequestId, toAddress, amountMojos);
        } catch (Exception e) {
            LOG.warnf(e, "chainsvc withdrawal submit failed for %s (%s) — will retry", requestId, chainRequestId);
            return;
        }
        if (result == null) {
            return; // the client already said why; the poller retries
        }
        if (result.accepted()) {
            withdrawalDeposits.markWithdrawalSubmitted(requestId, nowMs);
            return;
        }
        if (result.isConflict()) {
            LOG.errorf("the chain service refused withdrawal %s (request id %s) as a conflict — failing it and "
                    + "returning the PLAY to the player: %s", requestId, chainRequestId, result.conflict());
            try {
                gameService.failWithdrawalOnChain(requestId, REFUSED_REASON, nowMs);
            } catch (RuntimeException e) {
                // e.g. the engine is not ready: the request is still unsubmitted, the next try gets the
                // same refusal and fails it then
                LOG.warnf(e, "could not fail the refused withdrawal %s yet — will retry", requestId);
            }
        }
    }
}
