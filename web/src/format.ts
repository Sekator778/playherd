/** Shared display formatting — kept separate from `api/contract.ts` data shapes (those stay
 *  plain decimal strings, per contract.ts's own doc comment) and from `api/liquidation.ts`
 *  (exact math). Nothing here is a source of truth for a number, only how it is printed. */
import type { MarketDto, MarketPriceDto } from "./api/contract";

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

/**
 * A PLAY amount that is COUNTING (`RollingDigits`) toward `target`. `formatPlayAmount`'s auto decimals look at the number in front
 * of them, and a count-up passes through values like 9,957.6543: the header's pill flickered between "9,957.65" and "9,957.654"
 * and back, three decimals mid-animation for a balance that has two (first tester pass, D12). While it rolls, the number shows
 * the decimals of where it is going — never more than two; once it has arrived it is exactly what `formatPlayAmount` says
 * (three decimals when the amount really has them).
 */
export function rollingPlayFormat(target: number, opts: { sign?: boolean; unit?: boolean } = {}): (n: number) => string {
  const rolling = Math.min(2, autoPlayDecimals(target));
  const suffix = opts.unit ? " PLAY" : "";
  return (n) =>
    (Math.abs(n - target) < 0.0005 ? formatPlayAmount(target, { sign: opts.sign }) : formatPlayAmount(n, { sign: opts.sign, decimals: rolling })) + suffix;
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

/** `MarketPriceDto.change24h` (`0.0098` → `+0.98%`); `null` — the source gives none — is a dash. This is the
 *  PERP reading; an `ODDS` market's change is read in points ({@link market24hMove}). */
export function formatChange24h(change: number | null): string {
  if (change === null || !Number.isFinite(change)) return "—";
  // A change that rounds to nothing is "0.00%" — not "+0.00%" or "-0.00%", which would claim a direction.
  if (Number((change * 100).toFixed(2)) === 0) return "0.00%";
  return formatPercent(change, { decimals: 2, sign: true });
}

/**
 * How far an `ODDS` price moved in the last 24 hours, in PERCENTAGE POINTS (0.64 → 0.65 is `+1.0`), from the
 * contract's `change24h`, which is the RELATIVE change (`MarketPriceDto.change24h`): the price 24 h ago was
 * `price / (1 + change24h)`, so the move is `price − price / (1 + change24h)`, ×100 to read in points. `null`
 * when there is no change, or when it cannot be a probability's change (≤ −100%).
 */
export function oddsMove24hPoints(price: string | number, change24h: number | null): number | null {
  const now = typeof price === "string" ? Number(price) : price;
  if (change24h === null || !Number.isFinite(change24h) || !Number.isFinite(now) || 1 + change24h <= 0) return null;
  return (now - now / (1 + change24h)) * 100;
}

/** A move in percentage points as text: `+1.0 pt`, `-0.4 pt`, `0.0 pt` — one decimal, no sign on a zero. */
export function formatPoints(points: number, opts: { sign?: boolean } = {}): string {
  const rounded = Math.round(points * 10) / 10;
  const sign = opts.sign && rounded > 0 ? "+" : "";
  return `${sign}${rounded === 0 ? "0.0" : rounded.toFixed(1)} pt`;
}

/** What a market's 24 h figure reads as: the text, and the signed number the trend arrow and colour follow. */
export interface Move24h {
  text: string;
  /** Signed, at the precision `text` shows (a move that prints as 0.0 is flat, never an arrow). `null` — unknown. */
  value: number | null;
  unit: "pct" | "pt";
}

/**
 * The 24 h change of a market the way the player reads it: a `PERP` as a percentage (`+0.98%`), an `ODDS`
 * market in percentage points (`+1.0 pt` — the price is itself a percentage, so a percentage of a percentage
 * would read as 1.56% for a 0.64 → 0.65 move). `price` may be `null` (no price yet): a dash, never a zero.
 */
export function market24hMove(market: Pick<MarketDto, "kind">, price: Pick<MarketPriceDto, "price" | "change24h"> | null): Move24h {
  const change = price ? price.change24h : null;
  if (market.kind === "ODDS") {
    const points = price ? oddsMove24hPoints(price.price, change) : null;
    if (points === null) return { text: "—", value: null, unit: "pt" };
    const rounded = Math.round(points * 10) / 10;
    return { text: formatPoints(points, { sign: true }), value: rounded, unit: "pt" };
  }
  const known = change !== null && Number.isFinite(change);
  // The value follows what the text shows: a change that prints as 0.00% is flat.
  return { text: formatChange24h(change), value: known ? (Number((change * 100).toFixed(2)) === 0 ? 0 : change) : null, unit: "pct" };
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

// ---------------------------------------------------------------------------
// Times: ONE zone everywhere — UTC — and the zone is NAMED wherever a time of day is shown.
//
// The room chart ticked in UTC with no word of it while the phone's clock read two hours more (first tester pass, D9): the defect
// was the unlabelled axis — the rows of the profile, the wallet and the status page already said "UTC". So the zone is UTC, as it
// was, and every place that prints a time now says so: "2026-10-06 22:41 UTC" in a row, "Times in UTC" under a chart. Rounds are
// UTC-aligned on the server and a season is a UTC month (`api/season.ts`), so UTC is also the clock their ids and windows are in.
//
// Printing the player's own zone instead ("22:41 GMT+2") is another product choice, not a defect fix, and it is Alex's to make.
// The code for it is here and switched off: `setDisplayTimeZone(null)` prints in the device's zone, named the same way ("your time
// zone (GMT+2)" under a chart, "GMT+2" beside a time in a row). Nothing in the app calls it.
// ---------------------------------------------------------------------------

/** The zone times are printed in unless something else is set: UTC (see above). */
export const DEFAULT_DISPLAY_TIME_ZONE = "UTC";

let pinnedZone: string | null = DEFAULT_DISPLAY_TIME_ZONE;

/** Sets the zone every time is printed in (an IANA name, "UTC") — or, with `null`, the device's own. A test of another zone sets
 *  its own and puts {@link DEFAULT_DISPLAY_TIME_ZONE} back. */
export function setDisplayTimeZone(zone: string | null): void {
  pinnedZone = zone;
}

/** The zone times are printed in: the one that was set (UTC unless something set another) or, with `null`, the device's. */
export function displayTimeZone(): string {
  if (pinnedZone) return pinnedZone;
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}

const zoneFormatters = new Map<string, Intl.DateTimeFormat>();

interface ClockParts {
  year: string;
  month: string;
  day: string;
  hour: string;
  minute: string;
  second: string;
  /** "UTC", "GMT+2", "EDT" — as the platform names the zone at that instant (daylight saving included). */
  zone: string;
}

function clockParts(ms: number): ClockParts {
  const zone = displayTimeZone();
  let formatter = zoneFormatters.get(zone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat("en-US", {
      timeZone: zone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23",
      timeZoneName: "short",
    });
    zoneFormatters.set(zone, formatter);
  }
  const parts: Record<string, string> = {};
  for (const part of formatter.formatToParts(new Date(ms))) parts[part.type] = part.value;
  return { year: parts.year, month: parts.month, day: parts.day, hour: parts.hour, minute: parts.minute, second: parts.second, zone: parts.timeZoneName };
}

/** The time of day: "22:41", or "22:41:44" with `seconds`. No zone — the chart or row that prints it names it once. */
export function formatClock(ms: number, opts: { seconds?: boolean } = {}): string {
  const p = clockParts(ms);
  return opts.seconds ? `${p.hour}:${p.minute}:${p.second}` : `${p.hour}:${p.minute}`;
}

/** The calendar date, "2026-10-06". */
export function formatDate(ms: number): string {
  const p = clockParts(ms);
  return `${p.year}-${p.month}-${p.day}`;
}

/** What the zone is called at that instant: "UTC", "GMT+2", "EDT". */
export function timeZoneLabel(ms: number = Date.now()): string {
  return clockParts(ms).zone;
}

/**
 * The zone as a chart's note says it, after "in": "UTC" — the default — or, once the device's own zone is switched on,
 * "your time zone (GMT+2)". The labels on a chart's axis carry no zone of their own; the note under it is where it is named
 * ("Times in UTC").
 */
export function timeZoneNote(ms: number = Date.now()): string {
  const label = timeZoneLabel(ms);
  return pinnedZone === null && label !== "UTC" ? `your time zone (${label})` : label;
}

/** A date and time with its zone: "2026-10-06 22:41 GMT+2". */
export function formatDateTime(ms: number): string {
  const p = clockParts(ms);
  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute} ${p.zone}`;
}

export function formatUpdatedAt(ms: number): string {
  const p = clockParts(ms);
  return `Updated ${p.hour}:${p.minute}:${p.second} ${p.zone}`;
}
