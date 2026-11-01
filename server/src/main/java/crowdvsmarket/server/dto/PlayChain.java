package crowdvsmarket.server.dto;

/** D-53: real on-chain PLAY withdraw/deposit DTOs beyond {@link Ledger}'s {@code
 *  WithdrawalRequestDto} — additive, not in contract.ts (same situation documented on the
 *  additive {@code ApiErrorCode}/DTO fields elsewhere in this package). */
public final class PlayChain {
    private PlayChain() {
    }

    /** {@code myDepositAddress()}'s response — deliberately just the address (the task's own
     *  {@code "RPC myDepositAddress() → {address}"}), not the inner/CAT puzzle hashes chainsvc
     *  also returns — those are cached server-side ({@code PlayerDepositAddressRepository}) for
     *  this server's own bookkeeping, not something a player needs to see to send PLAY there. */
    public record DepositAddressDto(String address) {
    }
}
