/** Shared display formatting — kept separate from `api/contract.ts` data shapes (those stay
 *  plain decimal strings, per contract.ts's own doc comment) and from `api/liquidation.ts`
 *  (exact math). Nothing here is a source of truth for a number, only how it is printed. */

export function formatPlayAmount(
  amount: string | number,
  opts: { decimals?: number; sign?: boolean } = {},
): string {
  const n = typeof amount === "string" ? Number(amount) : amount;
  const decimals = opts.decimals ?? 0;
  const formatted = new Intl.NumberFormat("en-US", {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(Math.abs(n));
  const sign = opts.sign ? (n < 0 ? "-" : n > 0 ? "+" : "") : n < 0 ? "-" : "";
  return `${sign}${formatted}`;
}

export function formatUsd(amount: string | number, opts: { decimals?: number } = {}): string {
  const n = typeof amount === "string" ? Number(amount) : amount;
  const decimals = opts.decimals ?? 2;
  return `$${new Intl.NumberFormat("en-US", {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(n)}`;
}

export function formatPercent(fraction: number, opts: { decimals?: number; sign?: boolean } = {}): string {
  const decimals = opts.decimals ?? 1;
  const value = (fraction * 100).toFixed(decimals);
  const sign = opts.sign && fraction > 0 ? "+" : "";
  return `${sign}${value}%`;
}

/** Doc pattern `docs/research/round_engine_design.md` §4.7: first 6 / last 4 characters, full
 *  address if shorter than that would leave nothing to shorten. Only used before a player's
 *  server-precomputed `displayName` exists yet (the nickname-pick step of first sign-in) —
 *  everywhere else, `PlayerSummaryDto.displayName` is shown as-is (contract.ts: "never
 *  re-derived in the UI"). */
export function shortenAddress(address: string): string {
  if (address.length <= 14) return address;
  return `${address.slice(0, 6)}…${address.slice(-4)}`;
}

export function formatCountdown(msRemaining: number): string {
  const totalSeconds = Math.max(0, Math.floor(msRemaining / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  const mm = String(minutes).padStart(2, "0");
  const ss = String(seconds).padStart(2, "0");
  return hours > 0 ? `${hours}:${mm}:${ss}` : `${mm}:${ss}`;
}

export function formatDateTimeUtc(ms: number): string {
  const d = new Date(ms);
  return `${d.toISOString().slice(0, 10)} ${d.toISOString().slice(11, 16)} UTC`;
}

export function formatUpdatedAtUtc(ms: number): string {
  const d = new Date(ms);
  return `Updated ${d.toISOString().slice(11, 19)} UTC`;
}
