/**
 * Which looks a market's chart may have, and the policy around them: pure functions, no React, no
 * drawing. A new look (bars, Heikin-Ashi) is added to `CHART_STYLES` here, gets a builder in `series.ts`
 * and a renderer in `renderers/`, and is registered in `renderers/index.ts` — nothing else changes.
 */
import type { MarketDto } from "../../api/contract";

export const CHART_STYLES = ["line"] as const;
export type ChartStyle = (typeof CHART_STYLES)[number];

type Kind = MarketDto["kind"];

/** The looks a market of this kind offers, the default first. Odds markets are a probability: line only. */
export function allowedStyles(kind: Kind): readonly ChartStyle[] {
  void kind; // one look so far: every kind gets the line
  return ["line"];
}

export function defaultStyle(kind: Kind): ChartStyle {
  return allowedStyles(kind)[0];
}

export function isChartStyle(value: unknown): value is ChartStyle {
  return typeof value === "string" && (CHART_STYLES as readonly string[]).includes(value);
}
