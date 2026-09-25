package crowdvsmarket.engine.ledger;

/**
 * One immutable, append-only ledger movement: {@code delta} is the signed change applied to
 * {@code playerId}'s balance at the time it was recorded (positive = credit, negative = debit).
 * {@code roundId} is set for stake/settlement entries and {@code null} otherwise. The full
 * sequence, replayed from zero per player, must reproduce {@code LedgerBook}'s live balances
 * exactly — that replay is this engine's conservation check (see the design doc and
 * {@code MonthLongHourlySimulationTest}).
 */
public record LedgerEntry(
        long sequence,
        String playerId,
        LedgerEntryType type,
        PlayAmount delta,
        String roundId,
        long atEpochMs) {
}
