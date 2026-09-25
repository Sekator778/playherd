import { Link } from "react-router-dom";
import type { Direction, Interval, RoundId } from "../api/contract";
import { outcomeFromNet } from "../api/outcome";
import { formatPlayAmount } from "../format";
import { CrackIcon, DirectionGlyph, MinusDiscIcon, TrophyIcon } from "./icons";

/**
 * A settled round as one result tile — icon + signed net + context, never a table row with the
 * outcome as bare colored text (`docs/product/design/direction.md` §4/§3, audit.md's Profile
 * section: "три состояния... обозначены исключительно цветом текста слова, без иконки/формы").
 * Used by `ProfilePage`'s round history and `HomePage`'s "Your last results".
 */
export type ResultKind = "won" | "lost" | "liquidated" | "even";

export function resultKind(net: number, liquidated: boolean): ResultKind {
  if (liquidated) return "liquidated";
  const outcome = outcomeFromNet(net);
  if (outcome === "won") return "won";
  if (outcome === "lost") return "lost";
  return "even";
}

const NET_CLASS: Record<ResultKind, string> = {
  won: "outcome-won",
  lost: "outcome-lost",
  liquidated: "outcome-lost",
  even: "outcome-even",
};

export function ResultTile({
  roundId,
  interval,
  direction,
  leverage,
  net,
  liquidated,
  dateLabel,
}: {
  roundId: RoundId;
  interval: Interval;
  direction: Direction;
  leverage: number;
  net: number;
  liquidated: boolean;
  /** Optional formatted settlement date/time — `ProfilePage` shows it, `HomePage`'s condensed
   *  "last results" list doesn't need it (the round is always the player's own most recent). */
  dateLabel?: string;
}) {
  const kind = resultKind(net, liquidated);
  return (
    <Link to={`/round/${roundId}`} className={`result-tile result-tile--${kind}`}>
      <span className="result-tile__icon">
        {kind === "won" && <TrophyIcon />}
        {kind === "liquidated" && <CrackIcon />}
        {(kind === "lost" || kind === "even") && <MinusDiscIcon />}
      </span>
      <span className="result-tile__body">
        <span className="result-tile__title">
          {interval === "HOURLY" ? "Hourly" : "Daily"} · <DirectionGlyph direction={direction} /> {direction} · x
          {leverage}
        </span>
        {dateLabel && <span className="result-tile__meta">{dateLabel}</span>}
      </span>
      <span className={`result-tile__net ${NET_CLASS[kind]}`}>
        {formatPlayAmount(net, { decimals: 2, sign: true })} PLAY
        {liquidated && <span className="result-tile__tag"> · LIQ</span>}
      </span>
    </Link>
  );
}
