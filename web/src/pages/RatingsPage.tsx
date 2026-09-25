import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import type { RatingsDto, SeasonId } from "../api/contract";
import { ErrorState, MultiAccountDisclosureLine, Skeleton } from "../components/Common";
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

  return (
    <div className="stack" style={{ gap: 16 }}>
      <h1>Ratings</h1>
      <MultiAccountDisclosureLine />

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
          <table className="data-table data-table--responsive">
            <thead>
              <tr>
                <th>Rank</th>
                <th>Player</th>
                <th>Balance change</th>
                <th>Rounds</th>
              </tr>
            </thead>
            <tbody>
              {ratings.rich.map((r) => (
                <tr key={r.player.playerId}>
                  <td data-label="Rank">#{r.rank}</td>
                  <td data-label="Player">
                    <Link to={`/profile/${r.player.playerId}`}>{r.player.displayName}</Link>
                  </td>
                  <td data-label="Balance change" className="mono">
                    {formatPlayAmount(r.growth, { decimals: 0, sign: true })} PLAY
                  </td>
                  <td data-label="Rounds">{r.roundsPlayed}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )
      ) : ratings.prophets.length === 0 ? (
        <p className="muted">No one has qualified yet — play {ratings.roundsThreshold} rounds this season to appear here.</p>
      ) : (
        <table className="data-table data-table--responsive">
          <thead>
            <tr>
              <th>Rank</th>
              <th>Player</th>
              <th>Direction accuracy</th>
              <th>Rounds</th>
            </tr>
          </thead>
          <tbody>
            {ratings.prophets.map((r) => (
              <tr key={r.player.playerId}>
                <td data-label="Rank">#{r.rank}</td>
                <td data-label="Player">
                  <Link to={`/profile/${r.player.playerId}`}>{r.player.displayName}</Link>
                </td>
                <td data-label="Direction accuracy" className="mono">
                  {formatPercent(r.accuracy)}
                </td>
                <td data-label="Rounds">{r.roundsPlayed}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
