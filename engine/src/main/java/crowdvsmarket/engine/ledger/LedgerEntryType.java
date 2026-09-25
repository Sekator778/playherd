package crowdvsmarket.engine.ledger;

/**
 * Classifies a {@link LedgerEntry}'s effect on a player's balance. See {@code LedgerBook}'s
 * class-level javadoc and docs/research/round_engine_design.md ("Ledger") for how these combine
 * into the engine's conservation invariant (packs and net settlement gains are the only PLAY the
 * house mints; net settlement losses, including full liquidation loss per Р-7, are what it
 * burns).
 */
public enum LedgerEntryType {
    /** Starter pack grant (Р-2, Р-12, Р-16) — always a credit, minted by the house. */
    STARTER_PACK,
    /** A stake reserved against an open bet (Р-8) — a debit, escrowed pending settlement. */
    STAKE_DEBIT,
    /** A reserved stake returned because the bet was cancelled/replaced before round start (Р-13) — a credit. */
    STAKE_REFUND,
    /** A round's payout (Р-3, Р-7): 0 on liquidation (full stake lost), {@code max(0, margin+pnl)} otherwise — a credit. */
    SETTLEMENT,
    /** An on-chain deposit confirmed (Р-6) — a credit, from outside the in-game ledger. */
    DEPOSIT,
    /** A withdrawal reserved pending on-chain confirmation (Р-6) — a debit. */
    WITHDRAWAL,
    /** A reserved withdrawal that failed on-chain and was returned to the balance (Р-6) — a credit. */
    WITHDRAWAL_REFUND
}
