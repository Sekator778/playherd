/** Shared display formatting — kept separate from `api/contract.ts` data shapes (those stay
 *  plain decimal strings, per contract.ts's own doc comment) and from `api/liquidation.ts`
 *  (exact math). Nothing here is a source of truth for a number, only how it is printed. */
import type { MarketDto } from "./api/contract";

/** One convention for every PLAY amount in the app (independent critique, 2026-09-26, "Game feel
 *  / number formatting consistency": whole numbers in some places, 2-3 decimals in others, for
 *  the same kind of number). Auto mode (no explicit `decimals`) rounds to PLAY's own scale — see
 *  `api/amountInput.ts`'s `PLAY_DECIMALS` (3, 1 PLAY = 1000 mojos) — then shows only the decimals
 *  that are actually non-zero: a whole amount reads as "10,000", a real fractional settlement as
 *  "601.32" or, on the rare mojo-level remainder, "601.325". Callers only pass `decimals`
 *  explicitly for a genuinely different need (e.g. a fixed-width column); every other call site
 *  in this app uses auto. */
function autoPlayDecimals(n: number): number {
  const milliPlay = Math.round(Math.abs(n) * 1000);
  if (milliPlay % 1000 === 0) return 0;
  return milliPlay % 10 === 0 ? 2 : 3;
}

export function formatPlayAmount(
  amount: string | number,
  opts: { decimals?: number; sign?: boolean } = {},
): string {
  const n = typeof amount === "string" ? Number(amount) : amount;
  const decimals = opts.decimals ?? autoPlayDecimals(n);
  const formatted = new Intl.NumberFormat("en-US", {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(Math.abs(n));
  const sign = opts.sign ? (n < 0 ? "-" : n > 0 ? "+" : "") : n < 0 ? "-" : "";
  return `${sign}${formatted}`;
}

export function formatUsd(amount: string | number, opts: { decimals?: number; sign?: boolean } = {}): string {
  const n = typeof amount === "string" ? Number(amount) : amount;
  const decimals = opts.decimals ?? 2;
  const formatted = new Intl.NumberFormat("en-US", {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(Math.abs(n));
  const sign = opts.sign ? (n < 0 ? "-" : n > 0 ? "+" : "") : n < 0 ? "-" : "";
  return `${sign}$${formatted}`;
}

export function formatPercent(fraction: number, opts: { decimals?: number; sign?: boolean } = {}): string {
  const decimals = opts.decimals ?? 1;
  const value = (fraction * 100).toFixed(decimals);
  const sign = opts.sign && fraction > 0 ? "+" : "";
  return `${sign}${value}%`;
}

/** The two things `formatPrice` reads of a market — a `MarketDto` satisfies it as is. */
export type PriceFormatMarket = Pick<MarketDto, "kind" | "priceDecimals">;

/** The most significant digits a price is shown with (Hyperliquid's own tick rule, "up to 5
 *  significant figures": `docs/research/hl_multi_asset_live.md` §3.5). */
const PRICE_SIGNIFICANT_DIGITS = 5;

/**
 * A market price as the player reads it (`PriceStr` in, text out).
 * - `PERP`: USD with thousands separators and as many fractional digits as it takes to show 5
 *   significant digits — never fewer than 2 (cents), never more than the market's own
 *   `priceDecimals`, never scientific notation: `$84,629.12`, `$2,696.80`, `$117.84`, `$1.4964`,
 *   `$0.094534`.
 * - `ODDS`: the probability as a percentage with one decimal: `52.3%`.
 */
export function formatPrice(price: string | number, market: PriceFormatMarket): string {
  const n = typeof price === "string" ? Number(price) : price;
  if (!Number.isFinite(n)) return "—";
  if (market.kind === "ODDS") return formatPercent(n, { decimals: 1 });
  const magnitude = Math.abs(n);
  // Decimal exponent of the leading digit, read from the exponential form (no float log10 edge cases).
  const exponent = magnitude === 0 ? 0 : Number(magnitude.toExponential().split("e")[1]);
  const wanted = Math.max(2, PRICE_SIGNIFICANT_DIGITS - (exponent + 1));
  return formatUsd(n, { decimals: Math.min(market.priceDecimals, wanted) });
}

/** `MarketPriceDto.change24h` (`0.0098` → `+0.98%`); `null` — the source gives none — is a dash. */
export function formatChange24h(change: number | null): string {
  if (change === null || !Number.isFinite(change)) return "—";
  return formatPercent(change, { decimals: 2, sign: true });
}

/**
 * A series' length from `MarketSeriesDto.durationSec`, the way the round chips label it: `"3 MIN"`,
 * `"15 MIN"`, `"1 HOUR"`, `"2 HOURS"`, `"1 DAY"`. The largest whole unit that divides the duration
 * evenly (so 90 minutes reads `"90 MIN"`, not "1.5 HOURS"); anything that is not a whole number of
 * minutes falls back to seconds.
 */
export function formatDuration(durationSec: number): string {
  const sec = Math.round(durationSec);
  if (sec > 0 && sec % 86_400 === 0) return `${sec / 86_400} ${sec === 86_400 ? "DAY" : "DAYS"}`;
  if (sec > 0 && sec % 3_600 === 0) return `${sec / 3_600} ${sec === 3_600 ? "HOUR" : "HOURS"}`;
  if (sec > 0 && sec % 60 === 0) return `${sec / 60} MIN`;
  return `${sec} SEC`;
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
