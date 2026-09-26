/**
 * Shared client-side validation for a PLAY amount typed into a free-text field — built for the
 * Withdraw/Deposit forms in `pages/WalletPage.tsx`. Pure functions, no React (same pattern as
 * `outcome.ts`/`fx/resultEvent.ts`), so they're unit-tested directly.
 *
 * Neither form validated this before (wave2 fix, 2026-09-26): `Number(amount) || 0` treated a
 * negative string like "-50" as a truthy, in-range amount — `!amountNum` is `false` for a
 * negative number, and `-50 > balance` is also `false`, so both forms' submit buttons stayed
 * enabled and would have sent a negative withdrawal/deposit straight to the server. Neither form
 * checked the fractional part either, so e.g. "10.12345" reached `.toFixed(3)` unnoticed —
 * silently sending 10.123, a different number than what was typed, with no warning.
 *
 * Not currently wired into the UI (independent critique, 2026-09-26): both forms were replaced by
 * an honest "turned off" notice after the *other* half of the same finding — the Deposit form
 * credited any self-reported amount with zero on-chain check — turned out to be the real, live
 * exploit. This validation stayed correct throughout; it's kept, tested, ready to wire back in
 * once real on-chain verification exists (`WalletPage.tsx`'s `UnavailableNotice` doc comment).
 */

/** `PlayAmountStr`'s scale (`contract.ts`: "PlayAmount.java... scale 3, 1 PLAY = 1000 mojos"). */
export const PLAY_DECIMALS = 3;

/** Checked on the raw typed string, not the parsed number — `Number("1.2345").toFixed(3)` would
 *  silently round to "1.235" without this check ever firing. */
export function hasTooManyDecimals(raw: string, maxDecimals: number = PLAY_DECIMALS): boolean {
  const dot = raw.indexOf(".");
  if (dot === -1) return false;
  return raw.length - dot - 1 > maxDecimals;
}

export interface AmountCheck {
  /** `Number(raw.trim())` — `NaN` for unparseable input. */
  amount: number;
  /** `raw` parses to a finite number strictly greater than 0. */
  isPositive: boolean;
  tooManyDecimals: boolean;
  /** Only ever `true` when `maxBalance` was passed and `isPositive` — never flags an already
   *  non-positive or malformed amount as *also* over balance. */
  overBalance: boolean;
  /** `isPositive && !tooManyDecimals && !overBalance`. */
  valid: boolean;
}

/** Validates a PLAY amount typed into a form field: positive, at most {@link PLAY_DECIMALS}
 *  decimal places, and — when `maxBalance` is given (Withdraw only; Deposit has no ceiling) —
 *  no more than that balance. */
export function checkPlayAmount(raw: string, maxBalance?: number): AmountCheck {
  const trimmed = raw.trim();
  const amount = Number(trimmed);
  const isPositive = trimmed !== "" && Number.isFinite(amount) && amount > 0;
  const tooManyDecimals = hasTooManyDecimals(trimmed);
  const overBalance = isPositive && maxBalance !== undefined && amount > maxBalance;
  return { amount, isPositive, tooManyDecimals, overBalance, valid: isPositive && !tooManyDecimals && !overBalance };
}
