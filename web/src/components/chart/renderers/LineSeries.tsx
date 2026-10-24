import { useId } from "react";
import type { Point } from "../series";
import type { RendererProps } from "./types";

/** The line look: the price as one path with a soft fill under it. Knows nothing of levels, axes or the bubble. */
export function LineSeries({ series, frame, tone }: RendererProps<Point[]>) {
  const gradientId = useId();
  const { x, y, pad, plotH } = frame;
  const points = series.data;
  if (points.length === 0) return null;
  const first = points[0];
  const last = points[points.length - 1];
  const path = points.map((p, i) => `${i === 0 ? "M" : "L"}${x(p.atMs).toFixed(1)} ${y(p.price).toFixed(1)}`).join(" ");
  const area = `${path} L${x(last.atMs).toFixed(1)} ${pad.top + plotH} L${x(first.atMs).toFixed(1)} ${pad.top + plotH} Z`;
  return (
    <>
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={`var(--pchart-${tone})`} stopOpacity="0.28" />
          <stop offset="100%" stopColor={`var(--pchart-${tone})`} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#${gradientId})`} />
      <path d={path} className={`pchart__line pchart__line--${tone}`} fill="none" />
    </>
  );
}
