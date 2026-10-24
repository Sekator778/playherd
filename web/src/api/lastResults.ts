import type { ProfileRoundEntryDto } from "./contract";
import { netFromPayoutAndStake } from "./outcome";

/** How many settled rounds the "last results" row shows. */
export const LAST_RESULTS = 5;

export type RoundResult = "won" | "lost" | "flat";

/**
 * One settled round as the streak reads it (`PublicProfileDto.streak`, Р-71): a win paid out more than the stake,
 * a loss paid out less (a liquidation pays 0, so it is a loss), a flat round gave the stake back.
 */
export function classifyRound(entry: Pick<ProfileRoundEntryDto, "payout" | "stake">): RoundResult {
  const net = netFromPayoutAndStake(entry.payout, entry.stake);
  return net > 0 ? "won" : net < 0 ? "lost" : "flat";
}

/** The last {@link LAST_RESULTS} settled rounds of a newest-first history, OLDEST FIRST (the newest is last, on the right). */
export function lastResults(history: readonly Pick<ProfileRoundEntryDto, "payout" | "stake">[]): RoundResult[] {
  return history.slice(0, LAST_RESULTS).map(classifyRound).reverse();
}

export function countResults(results: readonly RoundResult[]): Record<RoundResult, number> {
  const counts = { won: 0, lost: 0, flat: 0 };
  for (const r of results) counts[r] += 1;
  return counts;
}
