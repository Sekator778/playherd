/**
 * What the Stats → Crowd tab needs from `markets()` / `crowdOverview()` / a crowd account's history, as
 * pure functions: the market selector's groups, the best and worst market, and how the crowd chart's range
 * chips and time axis follow how much history there is (a stand with 3-minute rounds has hours of it, a
 * live deployment months — one set of chips and one date format cannot serve both).
 */
import type { CategoryDto, CategoryId, CrowdOverviewDto, MarketDto } from "../api/contract";
import { categoryTitle } from "../api/marketMeta";

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
export function marketGroups(markets: readonly MarketDto[], categories: readonly CategoryDto[]): MarketGroup[] {
  const groups: MarketGroup[] = categories.map((c) => ({ id: c.id, title: c.title || categoryTitle(c.id), markets: [] }));
  for (const market of markets) {
    let group = groups.find((g) => g.id === market.category);
    if (!group) {
      group = { id: market.category, title: categoryTitle(market.category) ?? market.category, markets: [] };
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

/** An axis tick: the time of day (UTC) over a short history, the date over a long one. */
export function formatAxisTick(ms: number, spanMs: number): string {
  const iso = new Date(ms).toISOString();
  return isShortSpan(spanMs) ? iso.slice(11, 16) : iso.slice(0, 10);
}

/** A tooltip's heading: always the full UTC date and time on a short history (a bare "14:20" is
 *  ambiguous across midnight), the date alone on a long one. */
export function formatAxisTooltip(ms: number, spanMs: number): string {
  const iso = new Date(ms).toISOString();
  return isShortSpan(spanMs) ? `${iso.slice(0, 10)} ${iso.slice(11, 16)} UTC` : iso.slice(0, 10);
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
