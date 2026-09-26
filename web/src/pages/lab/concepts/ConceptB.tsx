/**
 * Concept B — "Live show" (Р-59 follow-up task, direction "B: 'live show' / stream-like room with
 * crowd feed and reactions"). Borrows directly from `research-game-ux.md` §1.2/§2's Twitch
 * Predictions findings: a two-color split bar with live percentages as the room's dominant
 * fixture, a scrolling activity feed standing in for chat, and lightweight emoji reactions — the
 * one addition §2's "Вывод для Herd" flags as missing from the current spec, not found in any
 * other checked product's *lobby* layout.
 */
import { useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { useClock } from "../../../api/useClock";
import type { Direction } from "../../../api/contract";
import { BotBadge } from "../../../components/Common";
import { DirectionGlyph } from "../../../components/icons";
import { LeverageDial } from "../../../components/LeverageDial";
import { LiquidationBar } from "../../../components/LiquidationBar";
import { LeaderboardList } from "../../../components/Leaderboard";
import { CrowdChart } from "../../../components/CrowdChart";
import { formatCountdown } from "../../../format";
import {
  MOCK_ACTIVITY_FEED,
  MOCK_CROWD_HISTORY,
  MOCK_MY_PROFILE,
  MOCK_RICH_ENTRIES,
  PAIRS,
  buildRoomState,
  ownLivePnl,
  ownPercentToLiquidation,
  pairById,
} from "./mockData";
import { OracleLine, fmtPrice, fmtSigned, fmtStake, useFireFromQuery } from "./shared";
import "./ConceptB.css";

const REACTIONS = ["🔥", "😱", "💀", "🎉"];

function DockB({ active }: { active: "lobby" | "stats" }) {
  return (
    <nav className="cB-dock" aria-label="Concept B navigation">
      <Link to="/lab/concepts/b" className={`cB-dock__btn ${active === "lobby" ? "cB-dock__btn--active" : ""}`} aria-label="Live rooms">
        📺
      </Link>
      <Link to="/lab/concepts/b/stats" className={`cB-dock__btn ${active === "stats" ? "cB-dock__btn--active" : ""}`} aria-label="Stats">
        📊
      </Link>
    </nav>
  );
}

export function ConceptBLobby() {
  const live = PAIRS.filter((p) => p.status === "live");
  const soon = PAIRS.filter((p) => p.status === "coming-soon");
  return (
    <div className="cB-root">
      <h1 className="cB-lobby-title">Now playing</h1>
      {live.map((pair) => (
        <Link key={pair.id} to={`/lab/concepts/b/room/${pair.id}`} className="cB-stream-card">
          <div className="cB-stream-thumb">
            <span className="cB-live-badge">
              <span className="cB-live-dot" /> LIVE
            </span>
            <span className="cB-stream-symbol">{pair.symbol}/USD</span>
            <div className="cB-thumb-split">
              <div className="cB-thumb-split__up" style={{ flexBasis: "62%" }} />
              <div className="cB-thumb-split__down" style={{ flexBasis: "38%" }} />
            </div>
          </div>
          <div className="cB-stream-info">
            <p className="cB-stream-question">Will {pair.symbol} close UP or DOWN this hour?</p>
            <p className="cB-stream-viewers">{pair.players} watching</p>
          </div>
        </Link>
      ))}
      {soon.length > 0 && (
        <p className="cB-more-soon">
          More rooms coming soon: {soon.map((p) => p.symbol).join(", ")}
        </p>
      )}
      <DockB active="lobby" />
    </div>
  );
}

export function ConceptBRoom() {
  const { pairId } = useParams<{ pairId: string }>();
  const pair = pairById(pairId);
  const [params] = useSearchParams();
  const phase = (params.get("phase")?.toUpperCase() as "BETTING" | "RUNNING" | "SETTLED") || "BETTING";
  const nowMs = useClock(1000);
  const [direction, setDirection] = useState<Direction>("UP");
  const [leverage, setLeverage] = useState(10);
  const [stakeStr, setStakeStr] = useState("500");
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [reactionCounts, setReactionCounts] = useState<Record<string, number>>({});
  const stake = Number(stakeStr) || 0;
  const room = buildRoomState(phase, nowMs, { direction, leverage, stake });
  useFireFromQuery(`concept-b-${pair.id}`);

  const totalStake = room.upStake + room.downStake;
  const upPct = totalStake > 0 ? Math.round((room.upStake / totalStake) * 100) : 50;

  const pickSide = (d: Direction) => {
    setDirection(d);
    if (phase === "BETTING") setDrawerOpen(true);
  };

  return (
    <div className="cB-root cB-room">
      <div className="cB-channel-header">
        <Link to="/lab/concepts/b" className="cB-back">
          ←
        </Link>
        <span className="cB-live-badge">
          <span className="cB-live-dot" /> LIVE
        </span>
        <span className="cB-channel-title">{pair.symbol}/USD predictions</span>
        <span className="cB-viewers">{room.participantCount} watching</span>
      </div>

      <div className="cB-broadcast">
        <span className="cB-broadcast-price mono">${fmtPrice(room.currentPrice)}</span>
        <span className="cB-broadcast-countdown mono">{phase === "SETTLED" ? "Ended" : formatCountdown(Math.max(0, room.endMs - nowMs))}</span>
      </div>
      <OracleLine />

      {phase !== "SETTLED" && (
        <div className="cB-prediction-panel">
          <div className="cB-prediction-bar">
            <div className="cB-prediction-bar__up" style={{ width: `${upPct}%` }} />
            <div className="cB-prediction-bar__down" style={{ width: `${100 - upPct}%` }} />
          </div>
          <div className="cB-prediction-row">
            <button type="button" className={`cB-side-btn cB-side-btn--up ${direction === "UP" ? "cB-side-btn--selected" : ""}`} onClick={() => pickSide("UP")} disabled={phase !== "BETTING"}>
              <DirectionGlyph direction="UP" /> UP · {upPct}%
            </button>
            <button type="button" className={`cB-side-btn cB-side-btn--down ${direction === "DOWN" ? "cB-side-btn--selected" : ""}`} onClick={() => pickSide("DOWN")} disabled={phase !== "BETTING"}>
              <DirectionGlyph direction="DOWN" /> DOWN · {100 - upPct}%
            </button>
          </div>
        </div>
      )}

      {phase === "BETTING" && drawerOpen && (
        <div className="cB-drawer">
          <div className="cB-drawer__handle" />
          <p className="cB-drawer__title">
            Back <DirectionGlyph direction={direction} /> {direction}
          </p>
          <LeverageDial id="cB-leverage" value={leverage} min={2} max={40} onChange={setLeverage} />
          <div className="cB-stake-row">
            <div className="num-lg mono">{fmtStake(stake)} PLAY</div>
            <div className="cB-chips">
              {[100, 500, 1000].map((amt) => (
                <button key={amt} type="button" className="stake-chip" onClick={() => setStakeStr(String(stake + amt))}>
                  +{amt}
                </button>
              ))}
            </div>
          </div>
          <LiquidationBar entryPrice={room.openPrice.toFixed(2)} liquidationPrice={room.own.liquidationPrice.toFixed(2)} percentToLiquidation={ownPercentToLiquidation(room)} />
          <button type="button" className={`cB-confirm-btn cB-confirm-btn--${direction.toLowerCase()}`} onClick={() => setDrawerOpen(false)}>
            CONFIRM · {direction} · x{leverage}
          </button>
        </div>
      )}

      {phase === "RUNNING" && (
        <div className="cB-live-pnl">
          <span className="faint">Your position, unrealized</span>
          <span className={`num-lg mono ${ownLivePnl(room) >= 0 ? "outcome-won" : "outcome-lost"}`}>{fmtSigned(ownLivePnl(room))} PLAY</span>
        </div>
      )}

      {phase === "SETTLED" && (
        <p className="cB-settled">
          BTC closed UP — ${fmtPrice(room.openPrice)} → ${fmtPrice(room.closePrice)}
        </p>
      )}

      <div className="cB-reactions">
        {REACTIONS.map((r) => (
          <button key={r} type="button" className="cB-reaction-btn" onClick={() => setReactionCounts((c) => ({ ...c, [r]: (c[r] ?? 0) + 1 }))}>
            {r} {reactionCounts[r] ? <span className="cB-reaction-count">{reactionCounts[r]}</span> : null}
          </button>
        ))}
      </div>

      <h3 className="cB-section-title">Activity</h3>
      <ul className="cB-feed">
        {MOCK_ACTIVITY_FEED.map((item, i) => (
          <li key={i} className={`cB-feed__row cB-feed__row--${item.direction.toLowerCase()}`}>
            {item.text}
          </li>
        ))}
      </ul>
      {room.positions.some((p) => p.isBot) && (
        <p className="faint cB-bot-hint">
          <BotBadge isBot /> = test-mode bot, not a real player.
        </p>
      )}

      <DockB active="lobby" />
    </div>
  );
}

export function ConceptBStats() {
  const [tab, setTab] = useState<"recap" | "ratings" | "profile">("recap");
  return (
    <div className="cB-root">
      <h1 className="cB-lobby-title">Stream recap</h1>
      <div className="cB-tabs">
        {(["recap", "ratings", "profile"] as const).map((t) => (
          <button key={t} type="button" className={`cB-tabs__btn ${tab === t ? "cB-tabs__btn--active" : ""}`} onClick={() => setTab(t)}>
            {t === "recap" ? "Crowd vs market" : t === "ratings" ? "Top predictors" : "Your profile"}
          </button>
        ))}
      </div>

      {tab === "recap" && (
        <div className="cB-recap-card">
          <p className="faint">Crowd account, since day one</p>
          <p className="num-xl mono outcome-won">$146,900</p>
          <CrowdChart history={MOCK_CROWD_HISTORY} variant="compact" />
          <OracleLine />
        </div>
      )}

      {tab === "ratings" && (
        <LeaderboardList
          entries={MOCK_RICH_ENTRIES.map((e) => ({
            rank: e.rank,
            player: e.player,
            value: `${fmtSigned(e.growth, 2)} PLAY`,
            valueClassName: e.growth >= 0 ? "outcome-won" : "outcome-lost",
          }))}
        />
      )}

      {tab === "profile" && (
        <div className="cB-profile-card">
          <h2>{MOCK_MY_PROFILE.player.displayName}</h2>
          <p className="faint">
            {MOCK_MY_PROFILE.roundsPlayed} rounds · {Math.round(MOCK_MY_PROFILE.accuracy * 100)}% accuracy
          </p>
          <ul className="cB-feed">
            {MOCK_MY_PROFILE.history.map((h) => (
              <li key={h.roundId} className={`cB-feed__row cB-feed__row--${h.direction.toLowerCase()}`}>
                {h.interval} · x{h.leverage} ·{" "}
                <span className={h.net >= 0 ? "outcome-won" : "outcome-lost"}>
                  {fmtSigned(h.net)} {h.liquidated && "· LIQ"}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <DockB active="stats" />
    </div>
  );
}
