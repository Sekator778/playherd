package crowdvsmarket.engine.ledger;

/**
 * A request to move {@code amount} PLAY out of the in-game ledger to the player's wallet as a CAT
 * (Р-6). The balance is debited (escrowed) at {@link WithdrawalState#REQUESTED} time; on
 * {@link WithdrawalState#FAILED} it is returned; on {@link WithdrawalState#CONFIRMED} nothing
 * further changes (the debit already happened). Driving a request to {@code CONFIRMED}/
 * {@code FAILED} is the job of an {@link OnChainWalletPort} implementation — out of scope for
 * this engine module.
 */
public record WithdrawalRequest(
        String requestId,
        String playerId,
        PlayAmount amount,
        WithdrawalState state,
        long requestedAtEpochMs) {

    WithdrawalRequest withState(WithdrawalState newState) {
        return new WithdrawalRequest(requestId, playerId, amount, newState, requestedAtEpochMs);
    }
}
