import type { EpochMs } from "../../../api/contract";
import { bubbleWidth, bubbleY, type Frame } from "../frame";
import type { Tone } from "../renderers/types";

/** The end point (pulsing while the market takes prices) and the current price in a bubble on the right. */
export function LastPrice({
  frame,
  atMs,
  price,
  text,
  tone,
  live,
}: {
  frame: Frame;
  atMs: EpochMs;
  price: number;
  text: string;
  tone: Tone;
  live: boolean;
}) {
  const cx = frame.x(atMs);
  const cy = frame.y(price);
  const w = bubbleWidth(text);
  const by = bubbleY(frame, price);
  return (
    <>
      <circle cx={cx} cy={cy} r={4.5} className={`pchart__dot pchart__dot--${tone}`} />
      {live && <circle cx={cx} cy={cy} r={4.5} className={`pchart__pulse pchart__pulse--${tone}`} />}
      <g className={`pchart__bubble pchart__bubble--${tone}`}>
        <rect x={frame.width - w - 4} y={by - 10} width={w} height={20} rx={5} />
        <text x={frame.width - w / 2 - 4} y={by + 4} textAnchor="middle">
          {text}
        </text>
      </g>
    </>
  );
}
