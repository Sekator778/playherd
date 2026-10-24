/**
 * Which looks a market's chart may have, and the policy around them: pure functions, no React, no
 * drawing. A new look (bars, Heikin-Ashi) is added to `CHART_STYLES` here, gets a builder in `series.ts`
 * and a renderer in `renderers/`, and is registered in `renderers/index.ts` — nothing else changes.
 */
import type { MarketDto } from "../../api/contract";
import type { RangeId } from "./series";

/** In the order the switch shows them. */
export const CHART_STYLES = ["line", "candles"] as const;
export type ChartStyle = (typeof CHART_STYLES)[number];

type Kind = MarketDto["kind"];

/** The looks a market of this kind offers, in switch order. An odds market is a probability, not a price with a
 *  high and a low: line only (Alex, 2026-10-24). */
export function allowedStyles(kind: Kind): readonly ChartStyle[] {
  return kind === "ODDS" ? ["line"] : ["line", "candles"];
}

/** What a market opens on before the player has chosen: candles for a price market, the line for odds. */
export function defaultStyle(kind: Kind): ChartStyle {
  return kind === "ODDS" ? "line" : "candles";
}

export function isChartStyle(value: unknown): value is ChartStyle {
  return typeof value === "string" && (CHART_STYLES as readonly string[]).includes(value);
}

/** The look to draw: the stored choice when this market allows it, else the market's default (a choice of candles
 *  made on BTC must not put candles on an odds market). */
export function resolveStyle(kind: Kind, stored: unknown): ChartStyle {
  return isChartStyle(stored) && allowedStyles(kind).includes(stored) ? stored : defaultStyle(kind);
}

/** How long one candle is for the window on screen: about a dozen to thirty bodies, readable on a phone. */
export function bucketMsFor(range: RangeId | null): number {
  switch (range) {
    case "5M":
      return 15_000;
    case "15M":
      return 30_000;
    default:
      return 5_000; // 1M, and the first minute before any chip exists
  }
}
