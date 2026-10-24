/**
 * The registry of chart looks: `ChartStyle → { build, Renderer }`. This is the one place that knows which
 * looks exist. Adding a look = a `build` in `series.ts`, a renderer file, one line here (and its name in
 * `CHART_STYLES`); `PriceChart`, the overlays and the toolbar do not change.
 */
import { createElement } from "react";
import { buildLineSeries, type Point, type RangeId, type Series } from "../series";
import type { ChartStyle } from "../styles";
import { LineSeries } from "./LineSeries";
import type { RendererProps, StyleDefinition } from "./types";

export type { RendererProps, StyleDefinition, Tone } from "./types";

/** Registers a look. The one cast of the typed pair `{ build, Renderer }` to the erased one the registry stores:
 *  the pair is typed together here, so a renderer can never be handed another look's data. */
function defineStyle<D>(definition: StyleDefinition<D>): StyleDefinition {
  return definition as unknown as StyleDefinition;
}

const REGISTRY: { [K in ChartStyle]: StyleDefinition } = {
  line: defineStyle({ build: buildLineSeries, Renderer: LineSeries }),
};

/** The series of the given look for the ticks we hold and the window the player picked. */
export function buildSeries(style: ChartStyle, all: readonly Point[], range: RangeId | null): Series {
  return REGISTRY[style].build(all, range);
}

/** Draws any series with the renderer of its own look. */
export function SeriesLayer(props: RendererProps) {
  const Renderer = REGISTRY[props.series.style].Renderer;
  return createElement(Renderer, props);
}
