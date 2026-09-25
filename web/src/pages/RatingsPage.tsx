import { useCallback, useEffect, useState } from "react";
import { useApi, useSession } from "../api/ApiContext";
import type { RatingsDto, SeasonId } from "../api/contract";
import { ErrorState, MultiAccountDisclosureLine, Skeleton } from "../components/Common";
import { LeaderboardList, type LeaderboardEntry } from "../components/Leaderboard";
import { outcomeClassName, outcomeFromNet } from "../api/outcome";
import { formatPercent, formatPlayAmount } from "../format";

function currentSeasonId(): SeasonId {
  const d = new Date();
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

type Tab = "RICH" | "PROPHETS";

export function RatingsPage() {
  const api = useApi();
  const { player } = useSession();
  const [tab, setTab] = useState<Tab>("RICH");
  const current = currentSeasonId();
  const [season, setSeason] = useState<SeasonId>(current);
  const [ratings, setRatings] = useState<RatingsDto | null>(null);
  const [error, setError] = useState(false);
  const [myRoundsPlayed, setMyRoundsPlayed] = useState<number | null>(null);
  // The full season archive (Р-10) — same source CrowdPage's season-returns table uses, not a
  // hardcoded current+previous pair, so every past season the crowd account has data for is
  // reachable here too.
  const [seasons, setSeasons] = useState<SeasonId[]>([current]);

  useEffect(() => {
    api.crowdAccount().then((acc) => {
      const ids = new Set(acc.seasonReturns.map((r) => r.season));
      ids.add(current);
      setSeasons([...ids].sort().reverse());
    });
  }, [api, current]);

  const load = useCallback(() => {
    api
      .ratings(season)
      .then((r) => {
        setRatings(r);
        setError(false);
      })
      .catch(() => setError(true));
  }, [api, season]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    // No `player` → simply don't fetch; every render already gates the "below threshold" banner
    // on `player` being truthy too, so a stale `myRoundsPlayed` from a previous session can't
    // leak into view after sign-out.
    if (!player) return;
    api.mySeasonRecord(season).then((r) => setMyRoundsPlayed(r.roundsPlayed));
  }, [api, player, season]);

  if (error && !ratings) return <ErrorState onRetry={load} />;

  // Hero zone (direction.md §4): the player's own rank in the tab they're looking at, if they're
  // in it — derived from data already fetched for the list below, nothing fabricated.
  const ownRank =
    player && ratings
      ? (tab === "RICH" ? ratings.rich : ratings.prophets).find((r) => r.player.playerId === player.playerId)?.rank
      : undefined;

  return (
    <div className="stack" style={{ gap: 16 }}>
      <h1>Ratings</h1>
      <MultiAccountDisclosureLine />

      {ownRank !== undefined && (
        <div className="card card--hero ratings-hero">
          <span className="ratings-hero__label">Your rank · {tab === "RICH" ? "Richest" : "Prophets"}</span>
          <span className="num-lg">#{ownRank}</span>
        </div>
      )}

      {player && ratings && myRoundsPlayed !== null && myRoundsPlayed < ratings.roundsThreshold && (
        <div className="card card--tight">
          Play {ratings.roundsThreshold - myRoundsPlayed} more rounds this season to appear in the ratings.
        </div>
      )}

      <div className="direction-row" style={{ alignItems: "center", flexWrap: "wrap", gap: 12 }}>
        <div className="tabs" role="tablist" aria-label="Rating">
          <button type="button" role="tab" aria-selected={tab === "RICH"} onClick={() => setTab("RICH")}>
            Richest
          </button>
          <button type="button" role="tab" aria-selected={tab === "PROPHETS"} onClick={() => setTab("PROPHETS")}>
            Prophets
          </button>
        </div>
        <div className="field" style={{ margin: 0 }}>
          <label htmlFor="season-picker" className="visually-hidden">
            Season
          </label>
          <select id="season-picker" value={season} onChange={(e) => setSeason(e.target.value)}>
            {seasons.map((s) => (
              <option key={s} value={s}>
                {s}
                {s === current ? " (current)" : ""}
              </option>
            ))}
          </select>
        </div>
      </div>

      {!ratings ? (
        <Skeleton height={360} />
      ) : tab === "RICH" ? (
        ratings.rich.length === 0 ? (
          <p className="muted">No one has qualified yet — play {ratings.roundsThreshold} rounds this season to appear here.</p>
        ) : (
          <LeaderboardList
            entries={ratings.rich.map(
              (r): LeaderboardEntry => ({
                rank: r.rank,
                player: r.player,
                value: `${formatPlayAmount(r.growth, { decimals: 0, sign: true })} PLAY`,
                valueClassName: outcomeClassName(outcomeFromNet(Number(r.growth))),
                meta: `${r.roundsPlayed} rounds`,
              }),
            )}
          />
        )
      ) : ratings.prophets.length === 0 ? (
        <p className="muted">No one has qualified yet — play {ratings.roundsThreshold} rounds this season to appear here.</p>
      ) : (
        <LeaderboardList
          entries={ratings.prophets.map(
            (r): LeaderboardEntry => ({
              rank: r.rank,
              player: r.player,
              value: formatPercent(r.accuracy),
              meta: `${r.roundsPlayed} rounds`,
            }),
          )}
        />
      )}
    </div>
  );
}
