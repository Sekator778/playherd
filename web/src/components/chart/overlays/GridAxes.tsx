import { formatClock } from "../../../format";
import { axisValues, bubbleY, type Frame } from "../frame";

/** The faint grid, the price labels on the right and the three clock labels along the bottom. Depends only on the
 *  frame and the last price (an axis label the bubble would cover is left out rather than drawn half-hidden). */
export function GridAxes({ frame, lastPrice, formatPrice }: { frame: Frame; lastPrice: number; formatPrice: (value: number) => string }) {
  const { width, height, pad, plotW, fromMs, toMs, y, domain } = frame;
  const values = axisValues(domain);
  const covered = bubbleY(frame, lastPrice);
  const labels = values.filter((value) => Math.abs(y(value) - covered) > 15);
  const showSeconds = toMs - fromMs <= 20 * 60_000;
  return (
    <>
      {values.map((value) => (
        <line key={value} x1={pad.left} x2={pad.left + plotW} y1={y(value)} y2={y(value)} className="pchart__grid" />
      ))}
      {labels.map((value) => (
        <text key={value} x={width - 6} y={y(value) + 3.5} textAnchor="end" className="pchart__axis">
          {formatPrice(value)}
        </text>
      ))}
      {[0, 0.5, 1].map((f) => (
        <text
          key={f}
          x={pad.left + f * plotW}
          y={height - 7}
          textAnchor={f === 0 ? "start" : f === 1 ? "end" : "middle"}
          className="pchart__axis"
        >
          {formatClock(fromMs + f * (toMs - fromMs), { seconds: showSeconds })}
        </text>
      ))}
    </>
  );
}
