import type { Candle } from "../series";
import { candleShapes } from "./candleGeometry";
import type { RendererProps } from "./types";

/** The candle look: a wick from the high to the low and a body from the open to the close, one per time slot.
 *  Up, down and unchanged each have their own colour class; a candle that did not move is a thin neutral line.
 *  Knows nothing of levels, axes or the bubble. */
export function CandleSeries({ series, frame }: RendererProps<Candle[]>) {
  const shapes = candleShapes(series.data, frame);
  return (
    <g className="pchart__candles">
      {shapes.map((s) => (
        <g key={s.startMs} className={`pchart__candle pchart__candle--${s.direction}`}>
          <line x1={s.cx} x2={s.cx} y1={s.wickTop} y2={s.wickBottom} className="pchart__wick" />
          {s.direction === "flat" ? (
            <line x1={s.bodyX} x2={s.bodyX + s.bodyW} y1={s.bodyY} y2={s.bodyY} className="pchart__body pchart__body--flat" />
          ) : (
            <rect x={s.bodyX} y={s.bodyY} width={s.bodyW} height={s.bodyH} rx={1} className="pchart__body" />
          )}
        </g>
      ))}
    </g>
  );
}
