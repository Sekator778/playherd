import { Link } from "react-router-dom";
import type { Direction, MarketDto, RoundId } from "../api/contract";
import { outcomeFromNet } from "../api/outcome";
import { formatPlayAmount } from "../format";
import { useT } from "../i18n";
import { CrackIcon, DirectionGlyph, MinusDiscIcon, TrophyIcon } from "./icons";
import { oddsMarketPhrase } from "./marketModel";

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
  symbol,
  seriesLabel,
  direction,
  leverage,
  net,
  liquidated,
  dateLabel,
  odds = false,
  source,
}: {
  roundId: RoundId;
  /** The market's ticker ("BTC", "NVDA", "GER") — which market the round was played in (Р-69). */
  symbol?: string;
  /** The series' real length ("3 MIN"), when known: from the history row's own `durationSec`, or — for a
   *  server that predates it — the market list; without either the tile simply leaves it out (never
   *  "Hourly"/"Daily" for a round that was three minutes long). */
  seriesLabel?: string;
  direction: Direction;
  leverage: number;
  net: number;
  liquidated: boolean;
  /** Optional formatted settlement date/time — `ProfilePage` shows it, `HomePage`'s condensed
   *  "last results" list doesn't need it (the round is always the player's own most recent). */
  dateLabel?: string;
  /** The round was in an `ODDS` market (`ProfileRoundEntryDto.kind`): the tile says so — the result is about how the
   *  odds moved, not about who won. */
  odds?: boolean;
  /** The market's source, when its market is still on the list (a history row carries none): the tag's hover text names
   *  whose odds they are only then — an unknown source is not called "Polymarket". */
  source?: MarketDto["source"];
}) {
  const t = useT();
  const kind = resultKind(net, liquidated);
  return (
    <Link to={`/round/${roundId}`} className={`result-tile result-tile--${kind}`}>
      <span className="result-tile__icon">
        {kind === "won" && <TrophyIcon />}
        {kind === "liquidated" && <CrackIcon />}
        {(kind === "lost" || kind === "even") && <MinusDiscIcon />}
      </span>
      <span className="result-tile__body">
        {/* Which market and how long first (what the round WAS), then what the player did and when. A
            tile for a round whose market is unknown to the caller (no symbol, no length) leads with the
            side instead, as it always did. */}
        <span className="result-tile__title">
          {symbol ? (
            <>
              <strong className="result-tile__symbol">{symbol}</strong>
              {odds && (
                <>
                  {" "}
                  <span className="mk-tag mk-tag--odds" title={t("parts.oddsTitle", { what: oddsMarketPhrase(source, t) })}>
                    {t("parts.oddsTag")}
                  </span>
                </>
              )}
              {seriesLabel && ` · ${seriesLabel}`}
            </>
          ) : (
            <>
              {seriesLabel && `${seriesLabel} · `}
              <span className="nowrap">
                <DirectionGlyph direction={direction} /> {direction} · x{leverage}
              </span>
            </>
          )}
        </span>
        <span className="result-tile__meta">
          {symbol && (
            <>
              <span className="nowrap">
                <DirectionGlyph direction={direction} /> {direction} · x{leverage}
              </span>
              {dateLabel && " · "}
            </>
          )}
          {dateLabel}
        </span>
      </span>
      <span className={`result-tile__net ${NET_CLASS[kind]}`}>
        {formatPlayAmount(net, { sign: true })} PLAY
        {liquidated && <span className="result-tile__tag"> · {t("resultTile.liq")}</span>}
      </span>
    </Link>
  );
}
