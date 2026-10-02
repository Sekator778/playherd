/**
 * What the wallet's history says about the ROUND a settlement row belongs to — pure functions over the ledger, so every
 * sentence has a test.
 *
 * A `SETTLEMENT` entry is the PAYOUT, a credit: a bet of 100 PLAY that lost 0.17 pays back 99.83 and the row reads
 * "+99.83" — green, with a trophy, as the first live run showed. The stake left the balance earlier, in the round's
 * `STAKE_DEBIT` row. The row that says whether the round was won or lost is therefore the round's NET (everything the round
 * moved: debits, refunds, payout), never the sign of the payout — the very mistake Alex reported on 2026-09-25 for every other
 * screen (`api/outcome.ts`), and "a loss dressed as a win" is what the product must never show.
 */
import type { LedgerEntryDto } from "../api/contract";

/** Milli-PLAY (the ledger's own scale: 3 decimals), so sums are exact. */
function milli(delta: string): number {
  return Math.round(Number(delta) * 1000);
}

/**
 * The net result of every round whose settlement is among `entries`, in PLAY, keyed by round id — for the rounds whose own
 * entries are all there to be added up. A round placed, edited and settled leaves `DEBIT, REFUND, DEBIT, …, SETTLEMENT`;
 * every refund follows a debit, so any run of the round's entries that STARTS at a debit nets to the round's result. A page
 * of history that cuts the round short — its oldest loaded entry is a refund, or there is no debit at all — would give a
 * wrong number, so such a round is left out: the row then makes no claim about it.
 */
export function settlementNets(entries: readonly LedgerEntryDto[]): Map<string, number> {
  const byRound = new Map<string, LedgerEntryDto[]>();
  for (const entry of entries) {
    if (entry.roundId === null) continue;
    const group = byRound.get(entry.roundId);
    if (group) group.push(entry);
    else byRound.set(entry.roundId, [entry]);
  }
  const nets = new Map<string, number>();
  for (const [roundId, group] of byRound) {
    if (!group.some((e) => e.type === "SETTLEMENT")) continue;
    const oldestFirst = [...group].sort((a, b) => a.sequence - b.sequence);
    if (oldestFirst[0].type !== "STAKE_DEBIT") continue;
    nets.set(roundId, group.reduce((sum, e) => sum + milli(e.delta), 0) / 1000);
  }
  return nets;
}
