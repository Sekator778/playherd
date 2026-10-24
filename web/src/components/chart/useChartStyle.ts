import { useCallback, useState } from "react";
import type { MarketDto } from "../../api/contract";
import { resolveStyle, type ChartStyle } from "./styles";

/** Where the choice is remembered (one for every chart: pick candles once, get candles in every price room). */
export const CHART_STYLE_KEY = "herd.chartStyle";

/** Every access to storage is guarded: it throws in a private window or when storage is blocked, and a chart must
 *  never fail to draw because of that. */
function readStored(): string | null {
  try {
    return window.localStorage.getItem(CHART_STYLE_KEY);
  } catch {
    return null;
  }
}

function writeStored(style: ChartStyle): void {
  try {
    window.localStorage.setItem(CHART_STYLE_KEY, style);
  } catch {
    // not remembered; the choice still holds for this visit
  }
}

/**
 * The look of the chart for a market kind, and a setter that remembers the choice. Concerned only with the choice:
 * it knows nothing of the data or the drawing. A stored look the market does not allow is not applied (the market's
 * default is) and not erased either, so it comes back on the next market that allows it.
 */
export function useChartStyle(kind: MarketDto["kind"]): [ChartStyle, (style: ChartStyle) => void] {
  const [stored, setStored] = useState<string | null>(readStored);
  const choose = useCallback((style: ChartStyle) => {
    setStored(style);
    writeStored(style);
  }, []);
  return [resolveStyle(kind, stored), choose];
}
