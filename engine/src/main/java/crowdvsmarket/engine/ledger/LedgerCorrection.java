package crowdvsmarket.engine.ledger;

/**
 * One operator-issued ledger correction (wave-2 security fix, Р-32/Р-43 pattern; extended wave-3,
 * task item 1, to deposits too): a publicly auditable, append-only record of {@code delta} applied
 * directly to {@code playerId}'s balance to reverse a bogus debit/credit already recorded by a bug
 * or by an unverified-by-design action (e.g. a self-reported deposit accepted before real on-chain
 * CAT-deposit verification existed), without altering or deleting the original
 * {@link LedgerEntry}/event. {@code relatedRequestId} names the {@link WithdrawalRequest} or
 * {@link DepositRequest} being reversed — its own {@code "w-N"}/{@code "d-N"} prefix disambiguates
 * which (see {@link LedgerBook#reverseWithdrawalEffect}/{@link LedgerBook#reverseDepositEffect});
 * {@code reason} is mandatory and is what makes this "public with reason" (Р-32's cancellation-log
 * pattern, extended to ledger corrections) rather than a silent balance edit.
 */
public record LedgerCorrection(
        String correctionId,
        String playerId,
        PlayAmount delta,
        String reason,
        String relatedRequestId,
        long atEpochMs) {
}
