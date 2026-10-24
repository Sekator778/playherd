/**
 * The maths of the room's live price chart (`PriceChart.tsx`), kept free of React and of the DOM so
 * every rule — which ranges the data allows, how the vertical scale is chosen, when a liquidation
 * line is too far away to be on the chart, how labels are kept from overlapping — is a plain function
 * with a test. Prices are `PriceStr`s turned into numbers here: this is drawing, not money math (the
 * numbers that decide a round are `api/liquidation.ts`'s).
 */
import type { EpochMs, PriceTickDto } from "../api/contract";
import { tEn, type TFn } from "../i18n";

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

export interface YDomain {
  min: number;
  max: number;
}

/**
 * The vertical scale: the data and the `anchors` (the round's open price and the player's entry — they
 * sit right at the price, so they always belong on the chart) set it, padded 12 %; an `optional` price
 * (a liquidation line, which can be far away) joins the scale only when it is within one scale-height of
 * the padded data — otherwise it would flatten the price to a hair, and the chart marks it at the edge
 * instead (`placeOnScale`).
 */
export function yDomain(prices: readonly number[], anchors: readonly number[] = [], optional: readonly number[] = []): YDomain {
  const base = [...prices, ...anchors].filter(Number.isFinite);
  if (base.length === 0) return { min: 0, max: 1 };
  let lo = Math.min(...base);
  let hi = Math.max(...base);
  const floorRange = Math.max(Math.abs(hi) * 0.0004, 1e-9); // a dead-flat price still gets a visible band
  if (hi - lo < floorRange) {
    const mid = (hi + lo) / 2;
    lo = mid - floorRange / 2;
    hi = mid + floorRange / 2;
  }
  const pad = (hi - lo) * 0.12;
  lo -= pad;
  hi += pad;
  const height = hi - lo;
  let min = lo;
  let max = hi;
  for (const value of optional) {
    if (!Number.isFinite(value)) continue;
    if (value >= lo - height && value <= hi + height) {
      min = Math.min(min, value);
      max = Math.max(max, value);
    }
  }
  if (min < lo || max > hi) {
    const extra = (max - min) * 0.06;
    min -= extra;
    max += extra;
  }
  return { min, max };
}

export interface Placement {
  /** 0 = top of the plot, 1 = bottom; clamped into the plot. */
  t: number;
  /** The price is beyond the scale: the line is drawn at that edge and labelled with an arrow. */
  off: "above" | "below" | null;
}

export function placeOnScale(value: number, domain: YDomain): Placement {
  const span = domain.max - domain.min || 1;
  const t = (domain.max - value) / span;
  if (t < 0) return { t: 0, off: "above" };
  if (t > 1) return { t: 1, off: "below" };
  return { t, off: null };
}

/**
 * Vertical positions for labels that want to sit at `desired` pixels: keeps at least `minGap` between
 * neighbours (pushing them apart around where they wanted to be) and inside `[min, max]`. Returns the
 * positions in the order given.
 */
export function spreadLabels(desired: readonly number[], minGap: number, min: number, max: number): number[] {
  const order = desired.map((y, index) => ({ y, index })).sort((a, b) => a.y - b.y);
  const placed = order.map((o) => Math.min(max, Math.max(min, o.y)));
  for (let i = 1; i < placed.length; i++) placed[i] = Math.max(placed[i], placed[i - 1] + minGap);
  const overflow = placed.length > 0 ? placed[placed.length - 1] - max : 0;
  if (overflow > 0) {
    placed[placed.length - 1] = max;
    for (let i = placed.length - 2; i >= 0; i--) placed[i] = Math.min(placed[i], placed[i + 1] - minGap);
  }
  const result = new Array<number>(desired.length);
  order.forEach((o, i) => {
    result[o.index] = placed[i];
  });
  return result;
}

/** `count` evenly spaced values strictly inside the scale, top to bottom — the faint axis labels. */
export function axisValues(domain: YDomain, count = 3): number[] {
  return Array.from({ length: count }, (_, i) => domain.max - ((i + 1) / (count + 1)) * (domain.max - domain.min));
}

/** How far the price has moved since the round opened: relative for a price, in points for odds. */
export function moveSinceOpen(open: number, now: number, odds: boolean): { value: number; unit: "pct" | "pts" } {
  if (odds) return { value: (now - open) * 100, unit: "pts" };
  return { value: open === 0 ? 0 : now / open - 1, unit: "pct" };
}

/**
 * The move since the round opened as the chart's header prints it, and the tone that colours and arrows it: a
 * percentage with two decimals (three for a tiny move) for a price, percentage POINTS with one decimal for odds
 * (`+0.4 pt` — the same spelling as the 24 h figure on the card). A move that prints as zero is `0.0 pt` / `0.00%`,
 * flat, with neither sign nor arrow: "−0.0 pt ▼" would say the price fell when nothing did.
 */
export function describeMove(move: { value: number; unit: "pct" | "pts" }): { text: string; tone: "up" | "down" | "flat" } {
  const digits = move.unit === "pts" ? 1 : Math.abs(move.value) < 0.001 ? 3 : 2;
  const shown = Number((move.unit === "pts" ? move.value : move.value * 100).toFixed(digits));
  const unit = move.unit === "pts" ? " pt" : "%";
  if (shown === 0) return { text: `${(0).toFixed(digits)}${unit}`, tone: "flat" };
  return { text: `${shown > 0 ? "+" : ""}${shown.toFixed(digits)}${unit}`, tone: shown > 0 ? "up" : "down" };
}

/**
 * The distance from the round's open in money (a price market only; odds already read in points): `+$5.80` /
 * `-$5.80`, the amount formatted by the room's own price formatter (`format`, given the absolute difference).
 * `null` when the difference prints as zero — no sign, no claim of a direction, same as {@link describeMove}.
 */
export function describeMoneyMove(open: number, now: number, format: (value: number) => string): string | null {
  const diff = now - open;
  if (!Number.isFinite(diff)) return null;
  const shown = format(Math.abs(diff));
  if (shown === format(0)) return null;
  return `${diff > 0 ? "+" : "-"}${shown}`;
}

// ---------------------------------------------------------------------------
// The horizontal lines: round open, the player's entry/liquidation, the herd's
// ---------------------------------------------------------------------------

export interface PositionLinesInput {
  direction: "UP" | "DOWN";
  leverage: number;
  entryPrice: string;
  /** Null for a fixed-payout position ("Win or lose", Р-106): it has no wipeout line. */
  liquidationPrice: string | null;
  mode?: "PERCENTAGE" | "FIXED" | null;
}

export interface ChartLine {
  id: "open" | "own-entry" | "own-liq" | "crowd-entry" | "crowd-liq";
  /** Short text printed on the line (the price follows it). */
  label: string;
  price: number;
  tone: "open" | "entry" | "liq";
  /** The herd's lines are drawn fainter than the player's own. */
  faint: boolean;
}

const SAME_PRICE = 1e-9;
const sameLevel = (a: number, b: number) => Math.abs(a - b) <= SAME_PRICE * Math.max(1, Math.abs(a));
const glyph = (direction: "UP" | "DOWN") => (direction === "UP" ? "▲" : "▼");

/**
 * The lines to draw. Every position opens at the round's open price (Р-8), so the open line and the
 * player's entry — and the herd's — are normally one and the same level: drawn once, the label saying
 * what it is for the player. The liquidation lines are the informative ones: the player's own (solid)
 * and the herd's (the same colour, fainter).
 */
export function buildChartLines(
  input: { openPrice?: string | null; own?: PositionLinesInput | null; crowd?: PositionLinesInput | null },
  t: TFn = tEn,
): ChartLine[] {
  const lines: ChartLine[] = [];
  const open = input.openPrice != null ? Number(input.openPrice) : null;
  const openOk = open !== null && Number.isFinite(open);
  const own = input.own ?? null;
  const crowd = input.crowd ?? null;
  const ownEntry = own ? Number(own.entryPrice) : null;
  const crowdEntry = crowd ? Number(crowd.entryPrice) : null;

  if (openOk) {
    const entryIsOpen = own !== null && ownEntry !== null && sameLevel(ownEntry, open);
    lines.push({
      id: "open",
      label: entryIsOpen ? (own.mode === "FIXED" ? t("mode.openEntry", { glyph: glyph(own.direction) }) : t("lines.openEntry", { glyph: glyph(own.direction), leverage: own.leverage })) : t("lines.open"),
      price: open,
      tone: entryIsOpen ? "entry" : "open",
      faint: false,
    });
  }
  if (own && ownEntry !== null && Number.isFinite(ownEntry) && !(openOk && sameLevel(ownEntry, open))) {
    lines.push({ id: "own-entry", label: own.mode === "FIXED" ? t("mode.yourEntry", { glyph: glyph(own.direction) }) : t("lines.yourEntry", { glyph: glyph(own.direction), leverage: own.leverage }), price: ownEntry, tone: "entry", faint: false });
  }
  if (own && own.liquidationPrice !== null) {
    const liq = Number(own.liquidationPrice);
    if (Number.isFinite(liq)) lines.push({ id: "own-liq", label: t("lines.yourLiq"), price: liq, tone: "liq", faint: false });
  }
  if (crowd && crowdEntry !== null && Number.isFinite(crowdEntry)) {
    const mergedIntoOpen = openOk && sameLevel(crowdEntry, open);
    const mergedIntoOwn = ownEntry !== null && sameLevel(crowdEntry, ownEntry);
    if (!mergedIntoOpen && !mergedIntoOwn) {
      lines.push({ id: "crowd-entry", label: t("lines.herdEntry", { glyph: glyph(crowd.direction), leverage: crowd.leverage }), price: crowdEntry, tone: "entry", faint: true });
    }
  }
  if (crowd) {
    const liq = Number(crowd.liquidationPrice);
    if (Number.isFinite(liq)) lines.push({ id: "crowd-liq", label: t("lines.herdLiq", { glyph: glyph(crowd.direction), leverage: crowd.leverage }), price: liq, tone: "liq", faint: true });
  }
  return lines;
}
