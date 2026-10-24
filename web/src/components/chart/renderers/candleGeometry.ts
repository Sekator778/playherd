/**
 * The pixels of each candle on a frame, and its spoken description: pure functions, so the rules (what a candle
 * looks like when the price did not move, how wide a body is) have tests and `CandleSeries` only maps the shapes
 * to SVG.
 */
import type { TFn } from "../../../i18n";
import type { Frame } from "../frame";
import type { Candle, Series } from "../series";

export type CandleDirection = "up" | "down" | "flat";

export interface CandleShape {
  startMs: number;
  direction: CandleDirection;
  /** Centre line of the candle, where the wick is. */
  cx: number;
  bodyX: number;
  bodyW: number;
  /** Top edge and height of the body; a candle that closed where it opened has no body (`direction: "flat"`). */
  bodyY: number;
  bodyH: number;
  /** The wick: from the high down to the low. */
  wickTop: number;
  wickBottom: number;
}

/** A body is never thinner than this, so a small real move is still a visible, coloured body. */
const MIN_BODY_PX = 2;
/** Share of a candle's slot that its body takes; the rest is the gap between candles. */
const BODY_SHARE = 0.72;

export function directionOf(candle: Candle): CandleDirection {
  return candle.close > candle.open ? "up" : candle.close < candle.open ? "down" : "flat";
}

export function candleShapes(candles: readonly Candle[], frame: Frame): CandleShape[] {
  return candles.map((c) => {
    const left = frame.x(c.startMs);
    const slot = frame.x(c.endMs) - left;
    const bodyW = Math.max(1.5, Math.min(slot * BODY_SHARE, slot - 1.5));
    const cx = left + slot / 2;
    const direction = directionOf(c);
    const yOpen = frame.y(c.open);
    const yClose = frame.y(c.close);
    const top = Math.min(yOpen, yClose);
    const bodyH = direction === "flat" ? 0 : Math.max(MIN_BODY_PX, Math.abs(yOpen - yClose));
    return {
      startMs: c.startMs,
      direction,
      cx,
      bodyX: cx - bodyW / 2,
      bodyW,
      bodyY: direction === "flat" ? yOpen : top,
      bodyH,
      wickTop: frame.y(c.high),
      wickBottom: frame.y(c.low),
    };
  });
}

/** The candle look's part of the chart's spoken summary: the slot length and the window's high and low. */
export function describeCandles(series: Series<Candle[]>, t: TFn, format: (value: number) => string): string {
  const candles = series.data;
  if (candles.length === 0) return "";
  return t("pchart.sumCandles", {
    seconds: Math.round((candles[0].endMs - candles[0].startMs) / 1000),
    high: format(series.extent.high),
    low: format(series.extent.low),
  });
}
