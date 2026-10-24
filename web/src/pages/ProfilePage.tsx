import { useCallback, useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { useApi, useSession } from "../api/ApiContext";
import { isEngineNotReady, isNotFound } from "../api/apiErrors";
import type { InviteDto, MarketDto, ProfileRoundEntryDto, PublicProfileDto } from "../api/contract";
import { useEngineEpoch } from "../api/engineState";
import { netFromPayoutAndStake } from "../api/outcome";
import { seasonHistory } from "../api/seasonStats";
import { inviteLink } from "../api/referral";
import { useMarkets } from "../api/useMarkets";
import { Avatar } from "../components/Avatar";
import { BotsBlock } from "../components/BotsBlock";
import { BotBadge, CopyButton, ErrorState, Skeleton } from "../components/Common";
import { ResultTile } from "../components/ResultTile";
import { ShareResultButton } from "../components/ShareResultButton";
import { StreakBadge } from "../components/StreakBadge";
import { LastResults } from "../components/LastResults";
import { lastResults } from "../api/lastResults";
import { PlayDaysLine } from "../components/PlayDaysLine";
import { formatDateTime, formatDuration, formatPercent, formatPlayAmount } from "../format";
import { useT, type TFn } from "../i18n";
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
function seriesLabelOf(entry: ProfileRoundEntryDto, market: MarketDto | undefined, t: TFn): string | undefined {
  if (entry.durationSec !== undefined && Number.isFinite(entry.durationSec) && entry.durationSec > 0) return formatDuration(entry.durationSec, t);
  const series = market?.series.find((s) => s.interval === entry.interval);
  return series ? formatDuration(series.durationSec, t) : undefined;
}

/** Own profile only: the personal invite link with a copy button and how many players came through it. No rewards. A failed
 *  load shows nothing - the block is a convenience, never a reason to break the profile. */
export function InviteBlock() {
  const api = useApi();
  const t = useT();
  const [invite, setInvite] = useState<InviteDto | null>(null);
  useEffect(() => {
    let live = true;
    Promise.resolve()
      .then(() => api.myInvite())
      .then(
      (v) => live && setInvite(v),
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, [api]);
  if (!invite) return null;
  const link = inviteLink(invite.code);
  return (
    <section className="invite-block" aria-labelledby="invite-title">
      <h2 id="invite-title">{t("profile.inviteTitle")}</h2>
      <p className="muted">{t("profile.inviteHint")}</p>
      <div className="invite-block__row">
        <input className="invite-block__link" type="text" readOnly value={link} aria-label={t("profile.inviteLinkAria")} onFocus={(e) => e.currentTarget.select()} />
        <CopyButton value={link} />
      </div>
      <p className="invite-block__count">{t("profile.inviteCount", { count: invite.referredCount })}</p>
    </section>
  );
}

export function ProfilePage({ mine = false }: { mine?: boolean }) {
  const { playerId } = useParams<{ playerId: string }>();
  const api = useApi();
  const { player, openConnectModal } = useSession();
  const t = useT();
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
        <p className="state-card__title">{t("profile.connectToSee")}</p>
        <button type="button" className="btn btn--primary" onClick={openConnectModal}>
          {t("common.connectWallet")}
        </button>
      </div>
    );
  }

  if (error === "not-found") return <ErrorState message={t("profile.notFound")} />;
  if (error) return <ErrorState onRetry={load} />;
  if (!profile) {
    return (
      <div className="stack" style={{ gap: 16 }}>
        <Skeleton height={60} width={280} />
        <Skeleton height={220} />
      </div>
    );
  }

  // Р-90: another player's profile shows their wins only - never a lost or liquidated round, and no balance
  // change (it would be computed from the wins alone). The player's own profile is complete. The server filters
  // the same way; this keeps an older server or the mock from showing more.
  const isOwn = mine || profile.player.playerId === player?.playerId;
  const history = isOwn
    ? profile.history
    : profile.history.filter((h) => !h.liquidated && netFromPayoutAndStake(h.payout, h.stake) > 0);

  return (
    <div className="stack" style={{ gap: 20 }}>
      <h1 style={{ display: "flex", alignItems: "center", gap: 12 }}>
        <Avatar name={profile.player.nickname} />
        <span>
          {profile.player.displayName} <BotBadge isBot={profile.player.isBot} />
        </span>
      </h1>

      {profile.streak && <StreakBadge current={profile.streak.current} best={profile.streak.best} />}
      <LastResults results={lastResults(profile.history)} />
      {profile.playDays && <PlayDaysLine current={profile.playDays.current} best={profile.playDays.best} />}

      {profile.seasonRecord && (
        <div className="profile-tiles">
          <div className="stat-tile">
            <div className="stat-tile__label">{t("profile.roundsPlayed")}</div>
            <div className="stat-tile__value">{profile.seasonRecord.roundsPlayed}</div>
          </div>
          <div className="stat-tile">
            <div className="stat-tile__label">{t("profile.accuracy")}</div>
            <div className="stat-tile__value">
              {profile.seasonRecord.directionEligibleRounds > 0
                ? formatPercent(profile.seasonRecord.directionCorrectRounds / profile.seasonRecord.directionEligibleRounds)
                : "—"}
            </div>
          </div>
          {isOwn && (
            <div className="stat-tile">
              <div className="stat-tile__label">{t("profile.balanceChange")}</div>
              <div className="stat-tile__value">
                {formatPlayAmount(seasonBalanceChange(profile).toFixed(3), { sign: true })} PLAY
              </div>
            </div>
          )}
        </div>
      )}

      {isOwn && <InviteBlock />}
      {isOwn && !profile.player.isBot && <BotsBlock />}

      <h2>{t("profile.roundHistory")}</h2>
      {history.length === 0 ? (
        <p className="muted">{t("profile.noRounds")}</p>
      ) : (
        <div className="result-tiles">
          {history.map((h) => (
            <div key={h.roundId} className="history-row">
            {/* Net result (>0 won, <0 lost, =0 even), never `payout > 0` alone — the bug Alex reported (2026-09-25): a losing
                round that still returned some equity showed as "Won" here. Outcome is icon + color + text together
                (`ResultTile`), never color-only. */}
            <ResultTile
              roundId={h.roundId}
              symbol={h.asset}
              seriesLabel={seriesLabelOf(h, byId.get(h.marketId), t)}
              direction={h.direction}
              leverage={h.leverage}
              net={netFromPayoutAndStake(h.payout, h.stake)}
              stake={h.stake}
              payout={h.payout}
              fees={h.fees != null ? Number(h.fees) : null}
              mode={h.mode}
              liquidated={h.liquidated}
              dateLabel={formatDateTime(h.settledAtMs)}
              odds={(h.kind ?? byId.get(h.marketId)?.kind) === "ODDS"}
              source={byId.get(h.marketId)?.source}
            />
            {isOwn && (
              <ShareResultButton
                className="history-row__share"
                result={{
                  roundId: h.roundId,
                  symbol: h.asset,
                  seriesLabel: seriesLabelOf(h, byId.get(h.marketId), t),
                  direction: h.direction,
                  mode: h.mode,
                  leverage: h.leverage,
                  stake: Number(h.stake),
                  net: netFromPayoutAndStake(h.payout, h.stake),
                  liquidated: h.liquidated,
                }}
              />
            )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
