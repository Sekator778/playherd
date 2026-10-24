/**
 * The chart's geometry: pure functions, no React. A `Frame` maps time to x and price to y for one drawing;
 * the grid, the lines, the markers and every series look are drawn on the same frame, so none of them needs
 * to know how another one is drawn.
 */
import type { EpochMs } from "../../api/contract";

/** Tall enough to read on a desktop, short enough on a phone that the arena below it stays in the first screen. */
export const HEIGHT_WIDE = 224;
export const HEIGHT_NARROW = 196;
export const NARROW_BELOW = 420;
export const PAD_BASE = { left: 8, top: 16, bottom: 26 };
/** Approximate advance of one character of the 11 px bold tabular text used in the price bubble. */
export const BUBBLE_CHAR_PX = 6.7;
/** Width used before the first measurement (and under jsdom, which never reports one). */
export const FALLBACK_WIDTH = 340;

export function chartHeight(width: number): number {
  return width < NARROW_BELOW ? HEIGHT_NARROW : HEIGHT_WIDE;
}

/** The width of the current-price bubble for its text. It sits in a gutter on the right sized to it, so
 *  "$117,654.12" and "$0.094534" both fit without squeezing the plot more than they need to. */
export function bubbleWidth(text: string): number {
  return Math.max(56, Math.round(text.length * BUBBLE_CHAR_PX + 14));
}

export interface YDomain {
  min: number;
  max: number;
}

export interface Pad {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface Frame {
  width: number;
  height: number;
  pad: Pad;
  plotW: number;
  plotH: number;
  fromMs: EpochMs;
  toMs: EpochMs;
  domain: YDomain;
  x(ms: EpochMs): number;
  y(price: number): number;
}

export function makeFrame(input: { width: number; height: number; pad: Pad; fromMs: EpochMs; toMs: EpochMs; domain: YDomain }): Frame {
  const { width, height, pad, fromMs, toMs, domain } = input;
  const plotW = width - pad.left - pad.right;
  const plotH = height - pad.top - pad.bottom;
  return {
    width,
    height,
    pad,
    plotW,
    plotH,
    fromMs,
    toMs,
    domain,
    x: (ms) => pad.left + ((ms - fromMs) / (toMs - fromMs || 1)) * plotW,
    y: (value) => pad.top + ((domain.max - value) / (domain.max - domain.min || 1)) * plotH,
  };
}

/** Is this moment inside the window the frame covers? (Strictly inside: a marker on the edge is not drawn.) */
export function inView(frame: Frame, ms: EpochMs | null | undefined): ms is EpochMs {
  return ms != null && ms > frame.fromMs && ms < frame.toMs;
}

/** Where the current-price bubble sits vertically: at the price, kept inside the plot. */
export function bubbleY(frame: Frame, price: number): number {
  return Math.min(frame.pad.top + frame.plotH - 10, Math.max(frame.pad.top + 10, frame.y(price)));
}

/**
 * The vertical scale: the data (its lowest and highest price) and the `anchors` (the round's open price and
 * the player's entry — they sit right at the price, so they always belong on the chart) set it, padded 12 %;
 * an `optional` price (a liquidation line, which can be far away) joins the scale only when it is within one
 * scale-height of the padded data — otherwise it would flatten the price to a hair, and the chart marks it at
 * the edge instead (`placeOnScale`).
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
