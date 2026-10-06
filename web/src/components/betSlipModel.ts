/**
 * The bet slip's small pure rules — what the stake field accepts, what its chips and − / + buttons put in
 * it. Kept out of `BetSlip.tsx` so they have tests and so that file exports only components.
 */
import { LEVERAGE_MIN, MIN_STAKE_PLAY } from "../api/contract";

/** Plain-integer default for the stake field's *initial* value — never `MIN_STAKE_PLAY` itself
 *  (contract.ts: `"100.000"`, PLAY's own exact 3-decimal scale) bound straight into the input.
 *  Independent critique, 2026-09-26: a native `<input type="number">` rendered that raw string
 *  locale-dependently — on a comma-decimal system, `"100.000"` reads back as "100,000" (a
 *  thousand-fold misread) even though the interface language is fixed to English (Р-24). The stake
 *  field is a plain text input we format/parse ourselves instead, so this constant only needs to be a
 *  clean starting number, not a value any browser could reformat. */
export const DEFAULT_STAKE_DISPLAY = String(Number(MIN_STAKE_PLAY));

/** What the stake field's − / + buttons move by: the minimum stake, the smallest bet there is. */
export const STAKE_STEP = Number(MIN_STAKE_PLAY);

/** Where a slip has always opened: x10 — on every market whose cap lies above it. */
export const DEFAULT_LEVERAGE = 10;

/** Where a slip opens on a market capped at `DEFAULT_LEVERAGE` or lower, where x10 would be the cap itself. */
export const CAPPED_MARKET_DEFAULT_LEVERAGE = 3;

/**
 * Where a new slip's leverage starts. x10, as it always did, on a market whose cap is above x10 (BTC and the indices x40,
 * ETH x25, SOL x20 …). On a market capped at x10 or lower — every odds market, DOGE, HYPE — x10 was the cap, so a slip opened
 * on its Flame setting, which a player who just pressed PLACE BET had not chosen (first tester pass, D2: two of three such bets
 * lost the whole stake): those open on x3 instead (and on the cap itself when that is below x3). Nothing else about the
 * default changed — it is the minimal fix of the defect, not a new rule for every market. Never below `LEVERAGE_MIN`, never above the cap.
 */
export function defaultLeverage(maxLeverage: number): number {
  const wanted = maxLeverage > DEFAULT_LEVERAGE ? DEFAULT_LEVERAGE : CAPPED_MARKET_DEFAULT_LEVERAGE;
  return Math.min(maxLeverage, Math.max(LEVERAGE_MIN, wanted));
}

/** Only digits and at most one decimal point — typed directly into a plain text field, immune to
 *  the locale-reformatting a native number input applies to its own value. Returns `null` (do
 *  nothing) for a keystroke that isn't a partial number, e.g. a second "." or a stray letter, so
 *  the field can never show something Direction/Number(stake) would silently misparse. */
export function sanitizeStakeInput(raw: string): string | null {
  return raw === "" || /^\d*\.?\d*$/.test(raw) ? raw : null;
}

/** Same reasoning as {@link DEFAULT_STAKE_DISPLAY} applied to an existing bet's stake when
 *  loading it back into the editable field — `existing.stake` is contract-precision (e.g.
 *  `"600.000"`); the field shows the plain number a player actually typed. */
export function stakeDisplay(amount: string): string {
  return String(Number(amount));
}

/** A number as the field shows it: at most PLAY's 3 decimals, no trailing zeros ("350.5", "2500"). */
export function trimStake(n: number): string {
  return String(Number(n.toFixed(3)));
}

/**
 * The stake a percentage chip puts in the field (Polymarket's 25 · 50 · 75 · Max, Р-69): a fraction of
 * the balance, rounded DOWN to whole PLAY so the number is tidy and can never exceed what the player
 * has; MAX is everything, to the thousandth (PLAY's smallest unit), so nothing is left behind.
 */
export function stakeForFraction(balance: number, fraction: number): string {
  if (fraction >= 1) return trimStake(Math.floor(balance * 1000) / 1000);
  return String(Math.floor(balance * fraction));
}

export const STAKE_CHIPS: ReadonlyArray<{ label: string; fraction: number }> = [
  { label: "25%", fraction: 0.25 },
  { label: "50%", fraction: 0.5 },
  { label: "75%", fraction: 0.75 },
  { label: "MAX", fraction: 1 },
];

/** The value after a − / + press: moves by `delta`, never below 0 and, when the balance is known, never above it. */
export function steppedStake(current: number, delta: number, balance?: number): string {
  const raised = current + delta;
  return trimStake(Math.max(0, balance !== undefined ? Math.min(balance, raised) : raised));
}
