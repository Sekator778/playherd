package crowdvsmarket.engine.ledger;

/**
 * A request to move {@code amount} PLAY from an on-chain CAT back into the in-game ledger (Р-6).
 * Unlike a withdrawal, nothing is credited until {@link DepositState#CONFIRMED} — the PLAY has
 * not arrived until the on-chain transaction is confirmed. Driving a request to
 * {@code CONFIRMED}/{@code FAILED} is the job of an {@link OnChainWalletPort} implementation —
 * out of scope for this engine module.
 */
public record DepositRequest(
        String requestId,
        String playerId,
        PlayAmount amount,
        DepositState state,
        long requestedAtEpochMs) {

    DepositRequest withState(DepositState newState) {
        return new DepositRequest(requestId, playerId, amount, newState, requestedAtEpochMs);
    }
}
