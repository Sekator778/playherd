/**
 * The chart's data, as plain functions with no React and no DOM: ticks → points → the window the player
 * picked → a `Series` of the chosen look. Prices are `PriceStr`s turned into numbers here: this is drawing,
 * not money math (the numbers that decide a round are `api/liquidation.ts`'s).
 */
import type { EpochMs, PriceTickDto } from "../../api/contract";
import type { ChartStyle } from "./styles";

export interface Point {
  atMs: EpochMs;
  price: number;
}

/** Ticks as numbers, dropping any that are not finite (a malformed price must not blank the chart). */
export function toPoints(ticks: readonly PriceTickDto[]): Point[] {
  const points: Point[] = [];
  for (const tick of ticks) {
    const price = Number(tick.price);
    if (Number.isFinite(price) && Number.isFinite(tick.atMs)) points.push({ atMs: tick.atMs, price });
  }
  return points;
}

// ---------------------------------------------------------------------------
// Ranges
// ---------------------------------------------------------------------------

export type RangeId = "1M" | "5M" | "15M";

export const CHART_RANGES: ReadonlyArray<{ id: RangeId; ms: number }> = [
  { id: "1M", ms: 60_000 },
  { id: "5M", ms: 300_000 },
  { id: "15M", ms: 900_000 },
];

/** A range is offered once the data covers (nearly) all of it — a "15M" chip over four minutes of ticks
 *  would be a lie about what is drawn. */
const COVERAGE = 0.9;

export function dataSpanMs(points: readonly Point[]): number {
  return points.length < 2 ? 0 : points[points.length - 1].atMs - points[0].atMs;
}

/** The chips the data allows, shortest first (none while there is less than most of a minute of ticks). */
export function availableRanges(spanMs: number): RangeId[] {
  return CHART_RANGES.filter((r) => spanMs >= r.ms * COVERAGE).map((r) => r.id);
}

/** What the chart shows before anyone taps a chip: 5 minutes when there are, else the longest there is. */
export function defaultRange(available: readonly RangeId[]): RangeId | null {
  if (available.includes("5M")) return "5M";
  return available.length > 0 ? available[available.length - 1] : null;
}

export function rangeMs(id: RangeId): number {
  return CHART_RANGES.find((r) => r.id === id)!.ms;
}

/**
 * The points of the last `range` before the newest point (all of them for `null`, or when the data does not
 * reach back that far). The window starts exactly `range` before the newest point: when the boundary falls
 * between two ticks, the first point is the price interpolated onto it, so the line enters from the left edge
 * and "5M" is five minutes, not five and a bit.
 */
export function sliceRange(points: readonly Point[], range: RangeId | null): Point[] {
  if (range === null || points.length === 0) return [...points];
  const from = points[points.length - 1].atMs - rangeMs(range);
  const firstIn = points.findIndex((p) => p.atMs >= from);
  if (firstIn <= 0) return [...points];
  const before = points[firstIn - 1];
  const after = points[firstIn];
  if (after.atMs === from) return points.slice(firstIn);
  const t = (from - before.atMs) / (after.atMs - before.atMs);
  return [{ atMs: from, price: before.price + (after.price - before.price) * t }, ...points.slice(firstIn)];
}

// ---------------------------------------------------------------------------
// A series: what a renderer draws
// ---------------------------------------------------------------------------

/** The time and price span a series covers: what the frame is scaled to. */
export interface Extent {
  fromMs: EpochMs;
  toMs: EpochMs;
  low: number;
  high: number;
}

/** The data of one look plus the extent it covers. `D` is that look's own shape (points, candles…). */
export interface Series<D = unknown> {
  style: ChartStyle;
  extent: Extent;
  data: D;
}

export function seriesExtent(series: Series): Extent {
  return series.extent;
}

/** The extent of some points (no points: an empty extent at zero). */
export function extentOf(points: readonly Point[]): Extent {
  if (points.length === 0) return { fromMs: 0, toMs: 0, low: 0, high: 0 };
  let low = Infinity;
  let high = -Infinity;
  for (const p of points) {
    if (p.price < low) low = p.price;
    if (p.price > high) high = p.price;
  }
  return { fromMs: points[0].atMs, toMs: points[points.length - 1].atMs, low, high };
}

/** The line look: the windowed points, the first one interpolated onto the window's edge. */
export function buildLineSeries(all: readonly Point[], range: RangeId | null): Series<Point[]> {
  const points = sliceRange(all, range);
  return { style: "line", extent: extentOf(points), data: points };
}
