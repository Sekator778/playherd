import type { TKey } from "../../i18n";
import type { RangeId } from "./series";

/** The text key of each range chip (also used in the chart's spoken summary). */
export const RANGE_LABEL: Record<RangeId, TKey> = { "1M": "pchart.r1M", "5M": "pchart.r5M", "15M": "pchart.r15M" };
