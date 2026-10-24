/**
 * The registry of chart looks: `ChartStyle → { build, Renderer, describe? }`. This is the one place that knows which
 * looks exist. Adding a look = a `build` in `series.ts`, a renderer file, one line here (and its name in
 * `CHART_STYLES`); `PriceChart`, the overlays and the toolbar do not change.
 */
import { createElement } from "react";
import type { TFn } from "../../../i18n";
import { buildCandleSeries, buildLineSeries, type Point, type RangeId, type Series } from "../series";
import type { ChartStyle } from "../styles";
import { CandleSeries } from "./CandleSeries";
import { describeCandles } from "./candleGeometry";
import { LineSeries } from "./LineSeries";
import type { RendererProps, StyleDefinition } from "./types";

export type { RendererProps, StyleDefinition, Tone } from "./types";

/** Registers a look. The one cast of the typed set `{ build, Renderer, describe }` to the erased one the registry
 *  stores: the set is typed together here, so a renderer can never be handed another look's data. */
function defineStyle<D>(definition: StyleDefinition<D>): StyleDefinition {
  return definition as unknown as StyleDefinition;
}

const REGISTRY: { [K in ChartStyle]: StyleDefinition } = {
  line: defineStyle({ build: buildLineSeries, Renderer: LineSeries }),
  candles: defineStyle({ build: buildCandleSeries, Renderer: CandleSeries, describe: describeCandles }),
};

/** The series of the given look for the ticks we hold and the window the player picked. */
export function buildSeries(style: ChartStyle, all: readonly Point[], range: RangeId | null): Series {
  return REGISTRY[style].build(all, range);
}

/** What the look adds to the chart's spoken summary ("" when nothing). */
export function describeSeries(series: Series, t: TFn, format: (value: number) => string): string {
  return REGISTRY[series.style].describe?.(series, t, format) ?? "";
}

/** Draws any series with the renderer of its own look. */
export function SeriesLayer(props: RendererProps) {
  return createElement(REGISTRY[props.series.style].Renderer, props);
}
