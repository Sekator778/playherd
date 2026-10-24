import { useT } from "../../../i18n";
import type { ChartLine } from "../../priceChartModel";
import { placeOnScale, spreadLabels, type Frame } from "../frame";

/** Approximate advance of one character of the 10.5 px bold capitals-and-digits text of a reference-line label. */
const REF_CHAR_PX = 7;

/**
 * The betting lines: the round's open price, the player's entry and liquidation levels and the herd's, each with
 * its label. A line too far from the price to share its scale is pinned to the edge and says how far off it is,
 * in words. Labels are spread so none overlaps another. Depends only on the frame and the last price.
 */
export function ReferenceLines({
  frame,
  lines,
  lastPrice,
  formatPrice,
}: {
  frame: Frame;
  lines: readonly ChartLine[];
  lastPrice: number;
  formatPrice: (value: number) => string;
}) {
  const t = useT();
  const { pad, plotW, plotH, domain } = frame;
  const placed = lines.map((line) => {
    const placement = placeOnScale(line.price, domain);
    return { line, placement, y: pad.top + placement.t * plotH };
  });
  const labelY = spreadLabels(
    placed.map((p) => p.y - 9),
    17,
    pad.top - 4,
    pad.top + plotH - 14,
  );
  return (
    <>
      {placed.map(({ line, placement, y: ly }, i) => {
        const away = placement.off
          ? ` · ${t(placement.off === "above" ? "pchart.above" : "pchart.below", {
              pct: ((Math.abs(line.price - lastPrice) / (Math.abs(lastPrice) || 1)) * 100).toFixed(1),
            })}`
          : "";
        const text = `${line.label} ${formatPrice(line.price)}${away}`;
        const boxW = Math.min(plotW - 8, text.length * REF_CHAR_PX + 14);
        return (
          <g key={line.id} className={`pchart__ref pchart__ref--${line.tone} ${line.faint ? "pchart__ref--faint" : ""}`}>
            <line x1={pad.left} x2={pad.left + plotW} y1={ly} y2={ly} className="pchart__ref-line" />
            <rect x={pad.left + 4} y={labelY[i]} width={boxW} height={16} rx={4} className="pchart__ref-box" />
            <text x={pad.left + 10} y={labelY[i] + 11.5} className="pchart__ref-text">
              {text}
            </text>
          </g>
        );
      })}
    </>
  );
}
