package crowdvsmarket.engine.ledger;

/**
 * One operator-issued ledger correction (wave-2 security fix, Р-32/Р-43 pattern): a publicly
 * auditable, append-only record of {@code delta} applied directly to {@code playerId}'s balance to
 * reverse a bogus debit/credit already recorded by a bug, without altering or deleting the original
 * {@link LedgerEntry}/event. {@code relatedWithdrawalRequestId} names the {@link WithdrawalRequest}
 * being reversed (see {@link LedgerBook#reverseWithdrawalEffect}); {@code reason} is mandatory and
 * is what makes this "public with reason" (Р-32's cancellation-log pattern, extended to ledger
 * corrections) rather than a silent balance edit.
 */
public record LedgerCorrection(
        String correctionId,
        String playerId,
        PlayAmount delta,
        String reason,
        String relatedWithdrawalRequestId,
        long atEpochMs) {
}
