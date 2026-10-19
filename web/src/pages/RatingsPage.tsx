import { useCallback, useEffect, useState } from "react";
import { useApi, useSession } from "../api/ApiContext";
import { isEngineNotReady } from "../api/apiErrors";
import { RATING_ROUNDS_THRESHOLD, type CrowdAccountDto, type PublicProfileDto, type RatingsDto, type SeasonId } from "../api/contract";
import { useEngineEpoch } from "../api/engineState";
import { ErrorState, MultiAccountDisclosureLine, Skeleton } from "../components/Common";
import { LeaderboardList, LeaderboardHighlights, type LeaderboardEntry, type LeaderboardHighlight } from "../components/Leaderboard";
import { outcomeClassName, outcomeFromNet } from "../api/outcome";
import { bestSingleRound, biggestComeback } from "../api/seasonStats";
import { formatPercent, formatPlayAmount } from "../format";
import { useT, type TFn } from "../i18n";

/**
 * "This season" highlight strip for the Richest tab (`docs/product/design/leaderboard.md`,
 * independent critique 2026-09-26: "reads as everyone loses"). Built only from data already
 * fetched for this page — the crowd account's own season return (public, shown regardless of
 * sign-in), and, once signed in, the viewer's own best round/comeback this season
 * (`api/seasonStats.ts`) — never a fabricated or repainted number, and never shown at all when
 * there isn't a real one (e.g. no highlight tile for a season with no wins yet).
 */
function ratingsHighlights(
  crowd: CrowdAccountDto | null,
  season: SeasonId,
  myProfile: PublicProfileDto | null,
  t: TFn,
): LeaderboardHighlight[] {
  const items: LeaderboardHighlight[] = [];

  const seasonReturn = crowd?.seasonReturns.find((r) => r.season === season);
  if (seasonReturn) {
    items.push({
      label: t("ratings.crowdSeasonLabel"),
      value: formatPercent(seasonReturn.returnPct, { sign: true }),
      valueClassName: outcomeClassName(outcomeFromNet(seasonReturn.returnPct)),
      meta: t("ratings.crowdSeasonMeta"),
    });
  }

  if (myProfile) {
    const best = bestSingleRound(myProfile.history, season);
    if (best) {
      items.push({
        label: t("ratings.bestRound"),
        value: `${formatPlayAmount(best.net, { sign: true })} PLAY`,
        valueClassName: "outcome-won",
        meta: `${best.entry.direction} · x${best.entry.leverage}`,
      });
    }
    const comeback = biggestComeback(myProfile.history, season);
    if (comeback) {
      items.push({
        label: t("ratings.comeback"),
        value: `${formatPlayAmount(comeback.net, { sign: true })} PLAY`,
        valueClassName: "outcome-won",
        meta: t("ratings.comebackMeta"),
      });
    }
  }

  return items;
}

function currentSeasonId(): SeasonId {
  const d = new Date();
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, "0")}`;
}

type Tab = "RICH" | "PROPHETS" | "BOTS";

/** What the bots' tab shows when the server cannot give the boards (older server, or none answered): no one has qualified. */
const EMPTY_BOT_RATINGS: RatingsDto = { season: "", roundsThreshold: RATING_ROUNDS_THRESHOLD, rich: [], prophets: [] };

/** Р-106: the boards of the API bots - the same two rankings as the people's, one under the other, with a line saying who they are. */
function BotBoards({ ratings, t }: { ratings: RatingsDto | null; t: TFn }) {
  if (!ratings) return <Skeleton height={200} />;
  return (
    <div className="stack" style={{ gap: 16 }}>
      <p className="muted">{t("ratings.botsNote", { count: ratings.roundsThreshold })}</p>
      {ratings.rich.length === 0 ? (
        <p className="muted">{t("ratings.noQualified", { count: ratings.roundsThreshold })}</p>
      ) : (
        <>
          <h2>{t("ratings.richest")}</h2>
          <LeaderboardList
            entries={ratings.rich.map(
              (r): LeaderboardEntry => ({
                rank: r.rank,
                player: r.player,
                value: `${formatPlayAmount(r.growth, { sign: true })} PLAY`,
                valueClassName: outcomeClassName(outcomeFromNet(Number(r.growth))),
                meta: t("ratings.roundsMeta", { count: r.roundsPlayed }),
              }),
            )}
          />
          <h2>{t("ratings.prophets")}</h2>
          <LeaderboardList
            entries={ratings.prophets.map(
              (r): LeaderboardEntry => ({
                rank: r.rank,
                player: r.player,
                value: formatPercent(r.accuracy),
                meta: t("ratings.roundsMeta", { count: r.roundsPlayed }),
              }),
            )}
          />
        </>
      )}
    </div>
  );
}

export function RatingsPage() {
  const api = useApi();
  const { player } = useSession();
  const t = useT();
  const [tab, setTab] = useState<Tab>("RICH");
  const current = currentSeasonId();
  const [season, setSeason] = useState<SeasonId>(current);
  const [ratings, setRatings] = useState<RatingsDto | null>(null);
  // Р-106: the API bots' own boards, fetched when their tab is opened (and again for another season).
  const [botRatings, setBotRatings] = useState<RatingsDto | null>(null);
  const [error, setError] = useState(false);
  const [myRoundsPlayed, setMyRoundsPlayed] = useState<number | null>(null);
  // The full season archive (Р-10) — same source CrowdPage's season-returns table uses, not a
  // hardcoded current+previous pair, so every past season the crowd account has data for is
  // reachable here too. Kept in full (not just the season ids) for the highlight strip below —
  // one fetch already happening here, no new endpoint.
  const [seasons, setSeasons] = useState<SeasonId[]>([current]);
  const [crowd, setCrowd] = useState<CrowdAccountDto | null>(null);
  const [myProfile, setMyProfile] = useState<PublicProfileDto | null>(null);

  // Every read here loads again when the server comes back from a restart (`api/engineState.ts`); the three extras
  // (the crowd's seasons, the player's own profile and season record) only decorate the lists, so a failed one just
  // leaves its strip out rather than raising an error over the ratings.
  const engineEpoch = useEngineEpoch();
  useEffect(() => {
    api
      .crowdAccount()
      .then((acc) => {
        setCrowd(acc);
        const ids = new Set(acc.seasonReturns.map((r) => r.season));
        ids.add(current);
        setSeasons([...ids].sort().reverse());
      })
      .catch(() => {});
  }, [api, current, engineEpoch]);

  useEffect(() => {
    // No `player` → simply don't fetch, same reasoning as `myRoundsPlayed` below: the render call
    // site guards on `player` too, so a stale `myProfile` from a previous session is never shown
    // after sign-out.
    if (!player) return;
    api
      .myProfile()
      .then(setMyProfile)
      .catch(() => {});
  }, [api, player, engineEpoch]);

  const load = useCallback(() => {
    api
      .ratings(season)
      .then((r) => {
        setRatings(r);
        setError(false);
      })
      // A restarting server (503) is not a failure of this page: the skeleton (or the lists shown) stay, and the
      // epoch above loads them again when it is back.
      .catch((err) => {
        if (!isEngineNotReady(err)) setError(true);
      });
  }, [api, season]);

  useEffect(() => {
    load();
  }, [load, engineEpoch]);

  useEffect(() => {
    if (tab !== "BOTS") return;
    let live = true;
    Promise.resolve()
      .then(() => api.botRatings(season))
      .then(
        (r) => live && setBotRatings(r),
        // a server without the bots' boards, or one restarting: the tab shows the empty state, the page stays
        () => live && setBotRatings(EMPTY_BOT_RATINGS),
      );
    return () => {
      live = false;
    };
  }, [api, season, tab, engineEpoch]);

  useEffect(() => {
    // No `player` → simply don't fetch; every render already gates the "below threshold" banner
    // on `player` being truthy too, so a stale `myRoundsPlayed` from a previous session can't
    // leak into view after sign-out.
    if (!player) return;
    api
      .mySeasonRecord(season)
      .then((r) => setMyRoundsPlayed(r.roundsPlayed))
      .catch(() => {});
  }, [api, player, season, engineEpoch]);

  if (error && !ratings) return <ErrorState onRetry={load} />;

  // Hero zone (direction.md §4): the player's own rank in the tab they're looking at, if they're
  // in it — derived from data already fetched for the list below, nothing fabricated.
  const ownRank =
    player && ratings && tab !== "BOTS"
      ? (tab === "RICH" ? ratings.rich : ratings.prophets).find((r) => r.player.playerId === player.playerId)?.rank
      : undefined;

  return (
    <div className="stack" style={{ gap: 16 }}>
      <h1>{t("ratings.title")}</h1>
      <MultiAccountDisclosureLine />

      {ownRank !== undefined && (
        <div className="card card--hero ratings-hero">
          <span className="ratings-hero__label">{t("ratings.yourRank", { tab: tab === "RICH" ? t("ratings.richest") : t("ratings.prophets") })}</span>
          <span className="num-lg">#{ownRank}</span>
        </div>
      )}

      {player && ratings && myRoundsPlayed !== null && myRoundsPlayed < ratings.roundsThreshold && (
        <div className="card card--tight">
          {t("ratings.playMore", { count: ratings.roundsThreshold - myRoundsPlayed })}
        </div>
      )}

      {tab === "RICH" && <LeaderboardHighlights items={ratingsHighlights(crowd, season, player ? myProfile : null, t)} />}

      <div className="direction-row" style={{ alignItems: "center", flexWrap: "wrap", gap: 12 }}>
        <div className="tabs" role="tablist" aria-label={t("ratings.tabsAria")}>
          <button type="button" role="tab" aria-selected={tab === "RICH"} onClick={() => setTab("RICH")}>
            {t("ratings.richest")}
          </button>
          <button type="button" role="tab" aria-selected={tab === "PROPHETS"} onClick={() => setTab("PROPHETS")}>
            {t("ratings.prophets")}
          </button>
          <button type="button" role="tab" aria-selected={tab === "BOTS"} onClick={() => setTab("BOTS")}>
            {t("ratings.botsTab")}
          </button>
        </div>
        <div className="field" style={{ margin: 0 }}>
          <label htmlFor="season-picker" className="visually-hidden">
            {t("ratings.season")}
          </label>
          <select id="season-picker" value={season} onChange={(e) => setSeason(e.target.value)}>
            {seasons.map((s) => (
              <option key={s} value={s}>
                {s === current ? t("ratings.seasonCurrent", { season: s }) : s}
              </option>
            ))}
          </select>
        </div>
      </div>

      {tab === "BOTS" ? (
        <BotBoards ratings={botRatings} t={t} />
      ) : !ratings ? (
        <Skeleton height={360} />
      ) : tab === "RICH" ? (
        ratings.rich.length === 0 ? (
          <p className="muted">{t("ratings.noQualified", { count: ratings.roundsThreshold })}</p>
        ) : (
          <LeaderboardList
            entries={ratings.rich.map(
              (r): LeaderboardEntry => ({
                rank: r.rank,
                player: r.player,
                value: `${formatPlayAmount(r.growth, { sign: true })} PLAY`,
                valueClassName: outcomeClassName(outcomeFromNet(Number(r.growth))),
                meta: t("ratings.roundsMeta", { count: r.roundsPlayed }),
              }),
            )}
          />
        )
      ) : ratings.prophets.length === 0 ? (
        <p className="muted">{t("ratings.noQualified", { count: ratings.roundsThreshold })}</p>
      ) : (
        <LeaderboardList
          entries={ratings.prophets.map(
            (r): LeaderboardEntry => ({
              rank: r.rank,
              player: r.player,
              value: formatPercent(r.accuracy),
              meta: t("ratings.roundsMeta", { count: r.roundsPlayed }),
            }),
          )}
        />
      )}
    </div>
  );
}
