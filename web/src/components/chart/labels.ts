import type { TKey } from "../../i18n";
import type { RangeId } from "./series";
import type { ChartStyle } from "./styles";

/** The text key of each range chip (also used in the chart's spoken summary). */
export const RANGE_LABEL: Record<RangeId, TKey> = { "1M": "pchart.r1M", "5M": "pchart.r5M", "15M": "pchart.r15M" };

/** The text key of each look's switch chip. A new look cannot compile without its label. */
export const STYLE_LABEL: Record<ChartStyle, TKey> = { line: "pchart.styleLine", candles: "pchart.styleCandles" };
