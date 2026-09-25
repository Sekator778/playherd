import { Link } from "react-router-dom";
import type { PlayerSummaryDto } from "../api/contract";
import { Avatar } from "./Avatar";
import { BotBadge } from "./Common";

/**
 * Leaderboard cards (`docs/product/design/direction.md` §4 "Leaderboard cards, not tables") —
 * one reusable component for the Ratings page's Richest/Prophets lists and for Home's top-5
 * preview of the same two lists ("тот же компонент переиспользуется на Home", direction.md §4).
 * Replaces `RatingsPage`'s `<table>` (the audit's "most literally 'bухгалтерский' экран... ничем
 * не отличается от экспорта в Excel") and `HomePage`'s plain `.list-row` rows.
 */
export interface LeaderboardEntry {
  rank: number;
  player: PlayerSummaryDto;
  /** Already formatted, sign included where relevant (`formatPlayAmount`/`formatPercent`). */
  value: string;
  /** e.g. `outcome-won`/`outcome-lost` for a signed value; omitted for a neutral metric. */
  valueClassName?: string;
  /** e.g. "12 rounds". */
  meta?: string;
}

function rankClass(rank: number): string {
  if (rank === 1) return "leaderboard-card--gold";
  if (rank === 2) return "leaderboard-card--silver";
  if (rank === 3) return "leaderboard-card--bronze";
  return "";
}

export function LeaderboardList({ entries }: { entries: LeaderboardEntry[] }) {
  return (
    <ul className="leaderboard">
      {entries.map((e) => (
        <li key={e.player.playerId} className={`leaderboard-card ${rankClass(e.rank)}`}>
          <span className="leaderboard-card__rank">#{e.rank}</span>
          <Avatar name={e.player.nickname} className="leaderboard-card__avatar" />
          <div className="leaderboard-card__body">
            <Link to={`/profile/${e.player.playerId}`} className="leaderboard-card__name">
              {e.player.displayName}
            </Link>{" "}
            <BotBadge isBot={e.player.isBot} />
            {e.meta && <div className="leaderboard-card__meta">{e.meta}</div>}
          </div>
          <span className={`leaderboard-card__value ${e.valueClassName ?? ""}`}>{e.value}</span>
        </li>
      ))}
    </ul>
  );
}
