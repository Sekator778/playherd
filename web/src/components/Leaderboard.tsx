import { Link } from "react-router-dom";
import type { PlayerSummaryDto } from "../api/contract";
import { Avatar } from "./Avatar";
import { BotBadge } from "./Common";

/**
 * Leaderboard cards (`docs/product/design/direction.md` §4 "Leaderboard cards, not tables") —
 * one reusable component for the Ratings page's Richest/Prophets lists and for Home's top-5
 * preview of the same two lists ("the same component is reused on Home", direction.md §4).
 * Replaces `RatingsPage`'s `<table>` (the audit's "the most literally 'accounting' screen... is no
 * different from an Excel export") and `HomePage`'s plain `.list-row` rows.
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

export interface LeaderboardHighlight {
  label: string;
  value: string;
  /** e.g. `outcome-won`/`outcome-lost` — the same convention `LeaderboardEntry.valueClassName`
   *  uses; omitted for a neutral tile. */
  valueClassName?: string;
  meta?: string;
}

/**
 * "This season" highlight strip (`docs/product/design/leaderboard.md`) — sits above the
 * Richest/Prophets tabs. Never repaints a rank's own sign-colored number (the honesty rule
 * `game-designer.md` sets: a loss stays a loss everywhere it's shown); instead gives the page a
 * few genuinely positive, real numbers to lead with when the season has any — the crowd
 * account's own season return, and the viewer's best round/comeback. A single highlight (e.g.
 * signed out, only the public crowd-account tile applies) renders full-width instead of forcing
 * an empty 3-column grid.
 */
export function LeaderboardHighlights({ items }: { items: LeaderboardHighlight[] }) {
  if (items.length === 0) return null;
  return (
    <div className={items.length > 1 ? "grid-3" : undefined}>
      {items.map((it) => (
        <div key={it.label} className="stat-tile">
          <div className="stat-tile__label">{it.label}</div>
          <div className={`stat-tile__value ${it.valueClassName ?? ""}`}>{it.value}</div>
          {it.meta && (
            <div className="faint" style={{ marginTop: 4 }}>
              {it.meta}
            </div>
          )}
        </div>
      ))}
    </div>
  );
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
