import { useT } from "../../i18n";
import { RANGE_LABEL } from "./labels";
import { describeMoneyMove, describeMove, moveSinceOpen } from "./move";
import { CHART_RANGES, type RangeId } from "./series";

export interface ChartMove {
  /** The round's open price; no open price, no move text. */
  open: number | null;
  last: number;
  odds: boolean;
  format: (value: number) => string;
}

/** The bar over the chart: the range chips and how far the price has moved since the round opened. Draws nothing
 *  itself and knows nothing of how the series is drawn. */
export function ChartToolbar({
  available,
  range,
  onRange,
  move,
}: {
  available: readonly RangeId[];
  range: RangeId | null;
  onRange: (id: RangeId) => void;
  move: ChartMove;
}) {
  const t = useT();
  const described = move.open !== null ? describeMove(moveSinceOpen(move.open, move.last, move.odds)) : null;
  const money = move.open !== null && !move.odds && described && described.tone !== "flat" ? describeMoneyMove(move.open, move.last, move.format) : null;
  const moveText = described ? (money ? `${money} (${described.text})` : described.text) : null;
  const moveTone = described ? described.tone : "flat";
  return (
    <div className="pchart__bar">
      <div className="chip-row pchart__chips" role="tablist" aria-label={t("chart.rangeAria")}>
        {CHART_RANGES.filter((r) => available.includes(r.id)).map((r) => (
          <button
            key={r.id}
            type="button"
            role="tab"
            aria-selected={range === r.id}
            className="chip chip--small"
            onClick={() => onRange(r.id)}
          >
            {t(RANGE_LABEL[r.id])}
          </button>
        ))}
        {available.length === 0 && <span className="pchart__live faint">{t("pchart.live")}</span>}
      </div>
      {moveText && (
        <p className={`pchart__move pchart__move--${moveTone} mono`}>
          <span aria-hidden="true">{moveTone === "up" ? "▲" : moveTone === "down" ? "▼" : "•"}</span> {moveText}{" "}
          <span className="pchart__move-note">{t("pchart.vsOpen")}</span>
        </p>
      )}
    </div>
  );
}
