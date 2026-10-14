/**
 * What the Stats → Crowd tab needs from `markets()` / `crowdOverview()` / a crowd account's history, as
 * pure functions: the market selector's groups, the best and worst market, and how the crowd chart's range
 * chips and time axis follow how much history there is (a stand with 3-minute rounds has hours of it, a
 * live deployment months — one set of chips and one date format cannot serve both).
 */
import type { CategoryDto, CategoryId, CrowdOverviewDto, MarketDto } from "../api/contract";
import { categoryName } from "../api/marketMeta";
import { tEn, type TFn } from "../i18n";
import { formatClock, formatDate, formatDateTime, formatUsd } from "../format";

export type OverviewRow = CrowdOverviewDto["markets"][number];

/** The markets the herd has traded in, best return first; ties keep the server's order. */
export function rankedByReturn(rows: readonly OverviewRow[]): OverviewRow[] {
  return rows
    .map((row, index) => ({ row, index }))
    .filter(({ row }) => row.roundsTraded > 0)
    .sort((a, b) => b.row.returnPct - a.row.returnPct || a.index - b.index)
    .map(({ row }) => row);
}

/** The best and the worst market by the herd's return — over markets it has actually traded in (a market
 *  with no trade is neither a success nor a failure). `worst` is `null` when there is only one such market:
 *  naming the same market twice would be noise. */
export function bestAndWorst(rows: readonly OverviewRow[]): { best: OverviewRow; worst: OverviewRow | null } | null {
  const ranked = rankedByReturn(rows);
  if (ranked.length === 0) return null;
  return { best: ranked[0], worst: ranked.length > 1 ? ranked[ranked.length - 1] : null };
}

export interface MarketGroup {
  id: CategoryId;
  title: string;
  markets: MarketDto[];
}

/** The selector's `<optgroup>`s: the server's categories in its order, each with its markets (closed ones
 *  too — their crowd account is still there to read); markets of a category the list does not name go last. */
export function marketGroups(markets: readonly MarketDto[], categories: readonly CategoryDto[], t: TFn = tEn): MarketGroup[] {
  const groups: MarketGroup[] = categories.map((c) => ({ id: c.id, title: categoryName(c, t), markets: [] }));
  for (const market of markets) {
    let group = groups.find((g) => g.id === market.category);
    if (!group) {
      group = { id: market.category, title: categoryName({ id: market.category, title: "" }, t), markets: [] };
      groups.push(group);
    }
    group.markets.push(market);
  }
  return groups.filter((g) => g.markets.length > 0);
}

// ---------------------------------------------------------------------------
// The crowd chart's ranges and axis
// ---------------------------------------------------------------------------

const HOUR_MS = 3_600_000;
const DAY_MS = 86_400_000;

/** Up to this much history the chart is "short": ranges are in hours and the axis shows the time of day. */
export const SHORT_SPAN_MS = 2 * DAY_MS;

export interface CrowdRange {
  key: string;
  label: string;
  /** How far back from the newest point; `null` — all of it. */
  ms: number | null;
}

const SHORT_RANGES: readonly CrowdRange[] = [
  { key: "1H", label: "1H", ms: HOUR_MS },
  { key: "6H", label: "6H", ms: 6 * HOUR_MS },
  { key: "1D", label: "1D", ms: DAY_MS },
  { key: "ALL", label: "All", ms: null },
];
const LONG_RANGES: readonly CrowdRange[] = [
  { key: "1M", label: "1M", ms: 30 * DAY_MS },
  { key: "3M", label: "3M", ms: 90 * DAY_MS },
  { key: "1Y", label: "1Y", ms: 365 * DAY_MS },
  { key: "ALL", label: "All", ms: null },
];

/** Whether the history is short enough for time-of-day ticks. */
export function isShortSpan(spanMs: number): boolean {
  return spanMs <= SHORT_SPAN_MS;
}

/**
 * The chips for `spanMs` of history: months for a long one (1M · 3M · 1Y · All, as before), hours for a short
 * one (1H · 6H · 1D · All) — and only those that are shorter than the history itself (a "1Y" over three days
 * would be the same chart as "All"), "All" always.
 */
export function crowdRanges(spanMs: number): CrowdRange[] {
  const options = isShortSpan(spanMs) ? SHORT_RANGES : LONG_RANGES;
  return options.filter((r) => r.ms === null || r.ms < spanMs);
}

/** What the chart opens on: "3M" for a long history (as before), everything for a short one. */
export function defaultCrowdRange(spanMs: number): CrowdRange {
  const options = crowdRanges(spanMs);
  const preferred = isShortSpan(spanMs) ? null : options.find((r) => r.key === "3M");
  return preferred ?? options[options.length - 1];
}

/** An axis tick: the time of day over a short history, the date over a long one — in the app's one zone (UTC), which the
 *  chart's note names (`format.ts`: one rule for every time in the app). */
export function formatAxisTick(ms: number, spanMs: number): string {
  return isShortSpan(spanMs) ? formatClock(ms) : formatDate(ms);
}

/** A tooltip's heading: always the full date and time, with its zone, on a short history (a bare "14:20" is
 *  ambiguous across midnight), the date alone on a long one. */
export function formatAxisTooltip(ms: number, spanMs: number): string {
  return isShortSpan(spanMs) ? formatDateTime(ms) : formatDate(ms);
}

// ---------------------------------------------------------------------------
// The capital axis
// ---------------------------------------------------------------------------

/** A tick step of 1, 2, 2.5, 5 × a power of ten, at least `rough`. */
function niceStep(rough: number): number {
  const base = 10 ** Math.floor(Math.log10(rough));
  const f = rough / base;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * base;
}

/** An axis label of `value` for ticks `step` apart: "$96k" when whole thousands tell the ticks apart, "$99.5k" / "$99.75k" when
 *  a step is smaller than that — enough decimals that no two ticks read alike — and whole dollars below 10. */
function formatCapitalTick(value: number, step: number): string {
  if (value === 0) return "$0";
  if (step >= 10) {
    const decimals = step % 1000 === 0 ? 0 : step % 100 === 0 ? 1 : 2;
    return `$${(value / 1000).toFixed(decimals)}k`;
  }
  return formatUsd(value, { decimals: step % 1 === 0 ? 0 : 2 });
}

export interface CapitalAxis {
  /** The scale: the first and the last tick. */
  domain: [number, number];
  ticks: number[];
  format: (value: number) => string;
  /** How wide the label column must be, in px at the chart's 12 px tick font. */
  width: number;
}

/**
 * The capital axis of the crowd chart, computed from what is drawn. A tick label used to be `$${round(v / 1000)}k` over
 * whatever ticks the chart library picked, so a crowd account that had moved a few hundred dollars read "$101k $100k $100k
 * $100k $100k" (first tester pass, D8). The ticks are now round numbers (1, 2, 2.5, 5 × 10ⁿ apart) that cover the data, and the
 * labels carry exactly as many decimals as it takes to tell neighbouring ticks apart. A flat account (one value, or a span
 * under 0.2 % of it) gets a little room above and below instead of five copies of the same number. `null` — no finite
 * value to scale by: the chart's own default stands.
 */
export function capitalAxis(values: readonly number[], targetTicks = 5): CapitalAxis | null {
  // A loop, not `Math.min(...values)`: that puts every value on the stack as an argument, and past a limit it throws a RangeError
  // (the review measured it at about 40 000 points of the crowd's history in V8 and 22 000 in WebKit — the chart hands over three
  // values per point). A stand with 3-minute rounds fills that within weeks, and an error thrown while drawing unmounted the whole app.
  let min = Number.POSITIVE_INFINITY;
  let max = Number.NEGATIVE_INFINITY;
  for (const value of values) {
    if (!Number.isFinite(value)) continue;
    if (value < min) min = value;
    if (value > max) max = value;
  }
  if (min > max) return null; // no finite value at all
  const middle = (min + max) / 2;
  const minSpan = Math.max(1, Math.abs(middle) * 0.002);
  if (max - min < minSpan) {
    min = middle - minSpan / 2;
    max = middle + minSpan / 2;
  }
  const step = niceStep((max - min) / Math.max(1, targetTicks - 1));
  const low = Math.floor(min / step) * step;
  let high = Math.ceil(max / step) * step;
  if (high === low) high = low + step;
  const ticks: number[] = [];
  for (let v = low; v <= high + step / 2; v += step) ticks.push(Number(v.toFixed(6)));
  const format = (value: number) => formatCapitalTick(value, step);
  const chars = Math.max(...ticks.map((t) => format(t).length));
  return { domain: [ticks[0], ticks[ticks.length - 1]], ticks, format, width: Math.min(80, Math.max(44, 14 + chars * 7)) };
}

/**
 * One point per instant. The real server writes the crowd account's capital once per settled ROUND, and the rounds of both
 * series that end at one boundary (a 3-minute and a 15-minute round, an hour and a day) end in the same millisecond: the
 * history then holds several points with one `atMs`. On a time axis that is two x values at one place — Recharts keys its ticks
 * by position, so it warned of duplicate keys and could drop or double a tick. The last point of an instant is the account
 * after every round that ended then, so it is the one that stays; order is kept.
 */
export function dedupeByTime<T extends { atMs: number }>(history: readonly T[]): T[] {
  const lastAt = new Map<number, number>();
  history.forEach((point, index) => lastAt.set(point.atMs, index));
  return history.filter((point, index) => lastAt.get(point.atMs) === index);
}
