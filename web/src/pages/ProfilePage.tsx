import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import type { PublicProfileDto } from "../api/contract";
import { netFromPayoutAndStake, outcomeClassName, outcomeFromNet } from "../api/outcome";
import { seasonRange } from "../api/season";
import { BotBadge, ErrorState, Skeleton } from "../components/Common";
import { DirectionGlyph } from "../components/icons";
import { formatDateTimeUtc, formatPercent, formatPlayAmount } from "../format";

/** `SeasonRecordDto` has no "growth" field of its own — derive it the same way the "Richest"
 *  rating does, from the season-bounded slice of the player's full round history. */
function seasonBalanceChange(profile: PublicProfileDto): number {
  if (!profile.seasonRecord) return 0;
  const { startMs, endMs } = seasonRange(profile.seasonRecord.season);
  return profile.history
    .filter((h) => h.settledAtMs >= startMs && h.settledAtMs < endMs)
    .reduce((sum, h) => sum + Number(h.payout) - Number(h.stake), 0);
}

export function ProfilePage({ mine = false }: { mine?: boolean }) {
  const { playerId } = useParams<{ playerId: string }>();
  const api = useApi();
  const { player, openConnectModal } = useSession();
  const [profile, setProfile] = useState<PublicProfileDto | null>(null);
  const [error, setError] = useState<"generic" | "not-found" | null>(null);

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
      .then(setProfile)
      .catch((err) => setError(err instanceof Error && err.message === "Player not found." ? "not-found" : "generic"));
  }, [api, mine, playerId]);

  useEffect(() => {
    if (mine && !player) return;
    load();
  }, [load, mine, player]);

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
      <h1>
        {profile.player.displayName} <BotBadge isBot={profile.player.isBot} />
      </h1>

      {profile.seasonRecord && (
        <div className="grid-3">
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
              {formatPlayAmount(seasonBalanceChange(profile).toFixed(3), { decimals: 0, sign: true })} PLAY
            </div>
          </div>
        </div>
      )}

      <h2>Round history</h2>
      {profile.history.length === 0 ? (
        <p className="muted">No rounds played yet.</p>
      ) : (
        <table className="data-table data-table--responsive">
          <thead>
            <tr>
              <th>Date</th>
              <th>Type</th>
              <th>Direction</th>
              <th>Leverage</th>
              <th>Result</th>
            </tr>
          </thead>
          <tbody>
            {profile.history.map((h) => {
              // Net result (>0 won, <0 lost, =0 even), never `payout > 0` alone — the bug Alex
              // reported (2026-09-25): a losing round that still returned some equity (e.g. 80.15
              // PLAY back on a 100 PLAY stake) showed as "Won" here. `ProfileRoundEntryDto`
              // already carries the exact `stake`/`payout`, no derivation needed.
              const net = netFromPayoutAndStake(h.payout, h.stake);
              const outcome = outcomeFromNet(net);
              return (
                <tr key={h.roundId} className="clickable">
                  <td data-label="Date">
                    <Link to={`/round/${h.roundId}`}>{formatDateTimeUtc(h.settledAtMs)}</Link>
                  </td>
                  <td data-label="Type">{h.interval === "HOURLY" ? "Hourly" : "Daily"}</td>
                  <td data-label="Direction">
                    <DirectionGlyph direction={h.direction} /> {h.direction}
                  </td>
                  <td data-label="Leverage">x{h.leverage}</td>
                  <td data-label="Result">
                    <span className={h.liquidated ? "outcome-lost" : outcomeClassName(outcome)}>
                      {formatPlayAmount(net, { decimals: 2, sign: true })} PLAY
                      {h.liquidated && " · liquidated"}
                    </span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}
