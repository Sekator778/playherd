import type { TFn } from "../../i18n";
import type { ChartLine } from "../priceChartModel";
import { RANGE_LABEL } from "./labels";
import type { RangeId } from "./series";

/** The chart's spoken summary (`aria-label` of the SVG): the market, the window, where the price went, the open price
 *  and any liquidation levels. The same for every look of the series. */
export function chartSummary(input: {
  t: TFn;
  symbol: string;
  range: RangeId | null;
  from: number;
  to: number;
  lines: readonly ChartLine[];
  format: (value: number) => string;
  /** What the chosen look adds about itself (`describeSeries`), appended as it is. */
  look?: string;
}): string {
  const { t, symbol, range, from, to, lines, format, look } = input;
  const open = lines.find((l) => l.id === "open");
  return (
    t("pchart.summary", {
      symbol,
      range: range ? t(RANGE_LABEL[range]) : t("pchart.recent"),
      from: format(from),
      to: format(to),
    }) +
    (look ? `; ${look}` : "") +
    (open ? `; ${t("pchart.sumOpen", { price: format(open.price) })}` : "") +
    lines
      .filter((l) => l.tone === "liq")
      .map((l) => `; ${l.label.toLowerCase()} ${format(l.price)}`)
      .join("")
  );
}
