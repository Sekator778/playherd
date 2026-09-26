import { useCallback, useEffect, useState } from "react";
import { useApi, useSession } from "../api/ApiContext";
import type { CrowdAccountDto, PublicProfileDto, RatingsDto, SeasonId } from "../api/contract";
import { ErrorState, MultiAccountDisclosureLine, Skeleton } from "../components/Common";
import { LeaderboardList, LeaderboardHighlights, type LeaderboardEntry, type LeaderboardHighlight } from "../components/Leaderboard";
import { outcomeClassName, outcomeFromNet } from "../api/outcome";
import { bestSingleRound, biggestComeback } from "../api/seasonStats";
import { formatPercent, formatPlayAmount } from "../format";

/**
 * "This season" highlight strip for the Richest tab (`docs/product/design/leaderboard.md`,
 * independent critique 2026-09-26: "reads as everyone loses"). Built only from data already
 * fetched for this page — the crowd account's own season return (public, shown regardless of
 * sign-in), and, once signed in, the viewer's own best round/comeback this season
 * (`api/seasonStats.ts`) — never a fabricated or repainted number, and never shown at all when
 * there isn't a real one (e.g. no highlight tile for a season with no wins yet).
 */
function ratingsHighlights(crowd: CrowdAccountDto | null, season: SeasonId, myProfile: PublicProfileDto | null): LeaderboardHighlight[] {
  const items: LeaderboardHighlight[] = [];

  const seasonReturn = crowd?.seasonReturns.find((r) => r.season === season);
  if (seasonReturn) {
    items.push({
      label: "Crowd account — this season",
      value: formatPercent(seasonReturn.returnPct, { sign: true }),
      valueClassName: outcomeClassName(outcomeFromNet(seasonReturn.returnPct)),
      meta: "The public paper-trading account, not player balances.",
    });
  }

  if (myProfile) {
    const best = bestSingleRound(myProfile.history, season);
    if (best) {
      items.push({
        label: "Your best round — this season",
        value: `${formatPlayAmount(best.net, { sign: true })} PLAY`,
        valueClassName: "outcome-won",
        meta: `${best.entry.direction} · x${best.entry.leverage}`,
      });
    }
    const comeback = biggestComeback(myProfile.history, season);
    if (comeback) {
      items.push({
        label: "Your biggest comeback — this season",
        value: `${formatPlayAmount(comeback.net, { sign: true })} PLAY`,
        valueClassName: "outcome-won",
        meta: "Right after a loss.",
      });
    }
  }

  return items;
}

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
  // reachable here too. Kept in full (not just the season ids) for the highlight strip below —
  // one fetch already happening here, no new endpoint.
  const [seasons, setSeasons] = useState<SeasonId[]>([current]);
  const [crowd, setCrowd] = useState<CrowdAccountDto | null>(null);
  const [myProfile, setMyProfile] = useState<PublicProfileDto | null>(null);

  useEffect(() => {
    api.crowdAccount().then((acc) => {
      setCrowd(acc);
      const ids = new Set(acc.seasonReturns.map((r) => r.season));
      ids.add(current);
      setSeasons([...ids].sort().reverse());
    });
  }, [api, current]);

  useEffect(() => {
    // No `player` → simply don't fetch, same reasoning as `myRoundsPlayed` below: the render call
    // site guards on `player` too, so a stale `myProfile` from a previous session is never shown
    // after sign-out.
    if (!player) return;
    api.myProfile().then(setMyProfile);
  }, [api, player]);

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

      {tab === "RICH" && <LeaderboardHighlights items={ratingsHighlights(crowd, season, player ? myProfile : null)} />}

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
                value: `${formatPlayAmount(r.growth, { sign: true })} PLAY`,
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
