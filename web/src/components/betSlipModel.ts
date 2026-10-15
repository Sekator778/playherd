/**
 * The bet slip's small pure rules — what the stake field accepts, what its chips and − / + buttons put in
 * it. Kept out of `BetSlip.tsx` so they have tests and so that file exports only components.
 */
import { LEVERAGE_MIN, MIN_STAKE_PLAY, type BetDto, type Direction } from "../api/contract";

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

/**
 * A change to the player's own bet that the SERVER has accepted — `placeBet` or `cancelBet` answered — and that the page has not
 * shown yet: the round is read again to show it. What the player is told ("Bet updated.") waits for the page to show it
 * (`savedChangeIsShown`): saying it earlier put "Bet updated." over a card that still held the old bet — and, when the reload
 * failed, left it there with no word that the page was behind (first tester pass, D4, as first built).
 */
export type SavedChange =
  | { kind: "placed" | "updated"; roundId: string; direction: Direction; leverage: number; stake: string }
  | { kind: "cancelled"; roundId: string; refund: string };

/**
 * Whether the bet the page shows (`RoundBetting.myBet`) is what the change made it: the bet as it was sent (the stake as the
 * number the server stores — "650.000" and 650 are one stake), or, after a cancel, none.
 */
export function savedChangeIsShown(saved: SavedChange, shown: BetDto | null): boolean {
  if (saved.kind === "cancelled") return shown === null;
  return shown !== null && shown.direction === saved.direction && shown.leverage === saved.leverage && Number(shown.stake) === Number(saved.stake);
}

// ---------------------------------------------------------------------------
// Fees (economy stage C, C1)
// ---------------------------------------------------------------------------

/** A fee as a share of the stake, in percent: "0.92%", "<0.01%" for a fee that rounds to nothing, "—" when there is no stake. */
export function formatFeePercent(fee: number, stake: number): string {
  if (!(stake > 0) || !Number.isFinite(fee)) return "—";
  const pct = (fee / stake) * 100;
  if (pct > 0 && pct < 0.01) return "<0.01%";
  return `${pct.toFixed(2)}%`;
}

/**
 * The move against the bet that liquidates it, as the confirmation words it ("0.083%", "2.4%", "12%"): two significant digits,
 * because at x1000 the move is a fraction of a percent and "0.1%" or "0.0%" would be wrong by half.
 */
export function formatMovePercent(fraction: number): string {
  if (!Number.isFinite(fraction) || fraction <= 0) return "—";
  const pct = fraction * 100;
  if (pct >= 10) return `${Math.round(pct)}%`;
  return `${Number(pct.toPrecision(2))}%`;
}

/**
 * The biggest stake that still leaves room for its own fees: `stake + stake * herdRate + networkFee <= balance`, with the herd
 * rate read off a preview (`feeHerd / stake`). Rounded down to 0.001 PLAY, and one step lower for the herd fee's own rounding up.
 * Without a preview (no fee known) the whole balance, as before.
 */
export function maxAffordableStake(balance: number, previewStake: number | null, feeHerd: number | null, feeNetwork: number | null): number {
  if (previewStake === null || !(previewStake > 0) || feeHerd === null || feeNetwork === null) return balance;
  const rate = feeHerd / previewStake;
  const stake = Math.floor(((balance - feeNetwork) / (1 + rate)) * 1000) / 1000 - 0.001;
  return Math.max(0, stake);
}
