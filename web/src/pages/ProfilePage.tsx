import { useCallback, useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { isEngineNotReady, isNotFound } from "../api/apiErrors";
import type { MarketDto, ProfileRoundEntryDto, PublicProfileDto } from "../api/contract";
import { useEngineEpoch } from "../api/engineState";
import { netFromPayoutAndStake } from "../api/outcome";
import { seasonHistory } from "../api/seasonStats";
import { useMarkets } from "../api/useMarkets";
import { Avatar } from "../components/Avatar";
import { BotBadge, ErrorState, Skeleton } from "../components/Common";
import { ResultTile } from "../components/ResultTile";
import { StreakBadge } from "../components/StreakBadge";
import { formatDateTime, formatDuration, formatPercent, formatPlayAmount } from "../format";
import "./ProfilePage.css";

/** `SeasonRecordDto` has no "growth" field of its own — derive it the same way the "Richest"
 *  rating does, from the season-bounded slice of the player's full round history (`seasonStats.ts`'s
 *  `seasonHistory`, also used by the Ratings page's own-highlights strip). */
function seasonBalanceChange(profile: PublicProfileDto): number {
  if (!profile.seasonRecord) return 0;
  return seasonHistory(profile.history, profile.seasonRecord.season).reduce(
    (sum, h) => sum + netFromPayoutAndStake(h.payout, h.stake),
    0,
  );
}

/** "3 MIN" for the series a history row's round was in: the row's own length (`durationSec`, which survives
 *  the market leaving the list) when the server sends it; else the market's series, when it is still on the
 *  list; nothing otherwise — never "Hourly"/"Daily" for a round that was three minutes long. */
function seriesLabelOf(entry: ProfileRoundEntryDto, market: MarketDto | undefined): string | undefined {
  if (entry.durationSec !== undefined && Number.isFinite(entry.durationSec) && entry.durationSec > 0) return formatDuration(entry.durationSec);
  const series = market?.series.find((s) => s.interval === entry.interval);
  return series ? formatDuration(series.durationSec) : undefined;
}

export function ProfilePage({ mine = false }: { mine?: boolean }) {
  const { playerId } = useParams<{ playerId: string }>();
  const api = useApi();
  const { player, openConnectModal } = useSession();
  const [profile, setProfile] = useState<PublicProfileDto | null>(null);
  const [error, setError] = useState<"generic" | "not-found" | null>(null);
  // A history row carries its round's real length (`durationSec`); a server that predates the field sends only
  // the series' NAME (`Interval`), whose length is then looked up on the market.
  const { byId } = useMarkets();

  // Reset to loading whenever the profile's *subject* changes (navigating from one player's
  // profile to another) — during render, not in an effect, and deliberately not on every retry
  // of the same subject (that would flash the skeleton for no reason).
  const subjectKey = mine ? "me" : (playerId ?? "");
  const [lastSubjectKey, setLastSubjectKey] = useState(subjectKey);
  if (subjectKey !== lastSubjectKey) {
    setLastSubjectKey(subjectKey);
    setProfile(null);
    setError(null);
  }

  const load = useCallback(() => {
    const request = mine ? api.myProfile() : api.publicProfile(playerId!);
    request
      .then((loaded) => {
        setProfile(loaded);
        setError(null);
      })
      .catch((err) => {
        // A server restarting (503 `ENGINE_NOT_READY`) is not a failure of this page: the skeleton (or the profile
        // already shown) stays, the banner says why, and the epoch below loads it again when the server is back.
        if (isEngineNotReady(err)) return;
        // "No such player" is the server's code (404 `NOT_FOUND`), never its wording.
        setError(isNotFound(err) ? "not-found" : "generic");
      });
  }, [api, mine, playerId]);

  const engineEpoch = useEngineEpoch();
  useEffect(() => {
    if (mine && !player) return;
    load();
  }, [load, mine, player, engineEpoch]);

  if (mine && !player) {
    // Signing in works from any device now (Р-48 replaces Р-31's desktop-only Sage
    // restriction — Sage itself ships Android/iOS builds, and the browser test wallet used on
    // the local stand runs the same flow on a phone too).
    return (
      <div className="state-card">
        <p className="state-card__title">Connect your wallet to see your profile.</p>
        <button type="button" className="btn btn--primary" onClick={openConnectModal}>
          CONNECT WALLET
        </button>
      </div>
    );
  }

  if (error === "not-found") return <ErrorState message="Player not found." />;
  if (error) return <ErrorState onRetry={load} />;
  if (!profile) {
    return (
      <div className="stack" style={{ gap: 16 }}>
        <Skeleton height={60} width={280} />
        <Skeleton height={220} />
      </div>
    );
  }

  return (
    <div className="stack" style={{ gap: 20 }}>
      <h1 style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <Avatar name={profile.player.nickname} />
        <span>
          {profile.player.displayName} <BotBadge isBot={profile.player.isBot} />
        </span>
      </h1>

      {profile.streak && <StreakBadge current={profile.streak.current} best={profile.streak.best} />}

      {profile.seasonRecord && (
        <div className="profile-tiles">
          <div className="stat-tile">
            <div className="stat-tile__label">Rounds played</div>
            <div className="stat-tile__value">{profile.seasonRecord.roundsPlayed}</div>
          </div>
          <div className="stat-tile">
            <div className="stat-tile__label">Direction accuracy</div>
            <div className="stat-tile__value">
              {profile.seasonRecord.directionEligibleRounds > 0
                ? formatPercent(profile.seasonRecord.directionCorrectRounds / profile.seasonRecord.directionEligibleRounds)
                : "—"}
            </div>
          </div>
          <div className="stat-tile">
            <div className="stat-tile__label">Balance change this season</div>
            <div className="stat-tile__value">
              {formatPlayAmount(seasonBalanceChange(profile).toFixed(3), { sign: true })} PLAY
            </div>
          </div>
        </div>
      )}

      <h2>Round history</h2>
      {profile.history.length === 0 ? (
        <p className="muted">No rounds played yet.</p>
      ) : (
        <div className="result-tiles">
          {profile.history.map((h) => (
            // Net result (>0 won, <0 lost, =0 even), never `payout > 0` alone — the bug Alex
            // reported (2026-09-25): a losing round that still returned some equity (e.g. 80.15
            // PLAY back on a 100 PLAY stake) showed as "Won" here. `ProfileRoundEntryDto`
            // already carries the exact `stake`/`payout`, no derivation needed. Outcome is icon +
            // color + text together (`ResultTile`), never color-only (audit.md's Profile
            // section).
            <ResultTile
              key={h.roundId}
              roundId={h.roundId}
              symbol={h.asset}
              seriesLabel={seriesLabelOf(h, byId.get(h.marketId))}
              direction={h.direction}
              leverage={h.leverage}
              net={netFromPayoutAndStake(h.payout, h.stake)}
              liquidated={h.liquidated}
              dateLabel={formatDateTime(h.settledAtMs)}
              odds={(h.kind ?? byId.get(h.marketId)?.kind) === "ODDS"}
              source={byId.get(h.marketId)?.source}
            />
          ))}
        </div>
      )}
    </div>
  );
}
