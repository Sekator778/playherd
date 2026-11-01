package crowdvsmarket.engine.ledger;

/**
 * Classifies a {@link LedgerEntry}'s effect on a player's balance. See {@code LedgerBook}'s
 * class-level javadoc and docs/research/round_engine_design.md ("Ledger") for how these combine
 * into the engine's conservation invariant (packs and net settlement gains are the only PLAY the
 * house mints; net settlement losses, including full liquidation loss per D-7, are what it
 * burns).
 */
public enum LedgerEntryType {
    /** Starter pack grant (D-2, D-12, D-16) — always a credit, minted by the house. */
    STARTER_PACK,
    /** A stake reserved against an open bet (D-8) — a debit, escrowed pending settlement. */
    STAKE_DEBIT,
    /** A reserved stake returned because the bet was cancelled/replaced before round start (D-13) — a credit. */
    STAKE_REFUND,
    /** A round's payout (D-3, D-7): {@code max(0, margin+pnl)} — a credit. A liquidated bet pays 0 if placed before economy stage A,
     *  its remainder at the candle extreme after it (D-92, plan A1); a bet placed after it is also capped (plan A5). */
    SETTLEMENT,
    /** Economy stage A (plan A4): the herd fee charged with a bet, {@code stake * min(1%, r_m * L)} — a debit. Stage B routes it to HERD. */
    FEE_HERD,
    /** Economy stage A (plan A4): the flat network fee charged with a bet (0.1 PLAY) — a debit. Stage B routes it to HERD. */
    FEE_NETWORK,
    /** The herd fee returned with the stake when a bet is cancelled/replaced before the round or its round is cancelled — a credit. */
    FEE_HERD_REFUND,
    /** The network fee returned with the stake in the same cases as {@link #FEE_HERD_REFUND} — a credit. */
    FEE_NETWORK_REFUND,
    /** An on-chain deposit confirmed (D-6) — a credit, from outside the in-game ledger. */
    DEPOSIT,
    /** A withdrawal reserved pending on-chain confirmation (D-6) — a debit. */
    WITHDRAWAL,
    /** A reserved withdrawal that failed on-chain and was returned to the balance (D-6) — a credit. */
    WITHDRAWAL_REFUND,
    /** Wave-2 security fix (D-32/D-43 pattern): an operator-issued, publicly-reasoned correction
     *  that reverses a bogus debit/credit already recorded by a bug (e.g. a malformed negative
     *  withdrawal amount) without altering or deleting the original {@link LedgerEntry} — see
     *  {@link LedgerBook#reverseWithdrawalEffect} and {@link LedgerCorrection}. Sign follows the
     *  correction itself: can be a credit or a debit. Additive — not in {@code contract.ts}'s
     *  {@code LedgerEntryType} union (same situation as the server's additive {@code ApiErrorCode}
     *  values, see docs/research/server_design.md). */
    OPERATOR_CORRECTION
}
