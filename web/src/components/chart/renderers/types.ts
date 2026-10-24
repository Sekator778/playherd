import type { ComponentType } from "react";
import type { TFn } from "../../../i18n";
import type { Frame } from "../frame";
import type { RangeId, Point, Series } from "../series";

/** The colour the chart takes from the move since the round opened: above open, below it, or level. */
export type Tone = "up" | "down" | "flat";

export interface RendererProps<D = unknown> {
  series: Series<D>;
  frame: Frame;
  tone: Tone;
}

/** One look of the chart: how to make its series from the ticks, how to draw it on a frame, and (when it has
 *  something to add) what the chart's spoken summary says about it. */
export interface StyleDefinition<D = unknown> {
  /** `all` is every tick we hold (oldest first); the look cuts its own window by `range`. */
  build(all: readonly Point[], range: RangeId | null): Series<D>;
  Renderer: ComponentType<RendererProps<D>>;
  describe?(series: Series<D>, t: TFn, format: (value: number) => string): string;
}
