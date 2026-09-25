/**
 * Net-result logic shared by every screen that shows a settled position's outcome (RoundPage,
 * RoundParts' `PositionsList`, ProfilePage, HomePage's "Your last results"). Fixes the bug Alex
 * reported (2026-09-25): every screen judged "won"/"lost" from `payout > 0` alone, so a losing
 * position that still got back some equity (e.g. an 80.15 PLAY payout on a 100 PLAY stake — a
 * 19.85 PLAY loss) rendered as "You won 80 PLAY", and a real win's payout (e.g. 104.96 on 100)
 * looked smaller than the stake, not like a gain. The player's balance is the ground truth: what
 * actually happened is `net = payout - stake`, not the raw payout.
 */
import type { Decimal } from "decimal.js";
import type { PositionDto } from "./contract";
import { initialMargin } from "./liquidation";

export type NetOutcome = "won" | "lost" | "even";

/** `>0` won, `<0` lost, `=0` even — never derived from `payout > 0` (see file header). */
export function outcomeFromNet(net: number): NetOutcome {
  if (net > 0) return "won";
  if (net < 0) return "lost";
  return "even";
}

/** CSS class for the net headline's color — `outcome-liquidated` is deliberately not covered
 *  here: a liquidation is always a loss (net = -stake), but callers show the "· liquidated"
 *  qualifier themselves (Р-7's "always the whole stake" is worth saying explicitly). */
export function outcomeClassName(outcome: NetOutcome): string {
  if (outcome === "won") return "outcome-won";
  if (outcome === "lost") return "outcome-lost";
  return "outcome-even";
}

/** `payout`/`stake` as `PlayAmountStr` decimal strings (contract.ts) — positive = net gain. */
export function netFromPayoutAndStake(payout: string, stake: string): number {
  return Number(payout) - Number(stake);
}

/**
 * Recovers a position's original PLAY stake when `PositionDto.stake` (additive, see
 * `contract.ts`) isn't present yet — `initialMargin` is `sizeForMargin`'s formula run backwards,
 * lossy across the two roundings (see `contract.ts`'s doc comment on `stake` for a worked
 * example). Only meant as a fallback for a backend that predates the field; prefer the field
 * itself whenever it's set.
 */
export function derivedStakeFromPosition(
  position: Pick<PositionDto, "size" | "entryPrice" | "leverage">,
): Decimal {
  return initialMargin(position.size, position.entryPrice, position.leverage);
}

/** The stake to use for a position's net result: the server-sent exact value when present,
 *  otherwise the lossy client-side derivation (see {@link derivedStakeFromPosition}). */
export function positionStakeStr(
  position: Pick<PositionDto, "stake" | "size" | "entryPrice" | "leverage">,
): string {
  if (position.stake !== undefined) return position.stake;
  return derivedStakeFromPosition(position).toFixed(2);
}
