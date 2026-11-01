/**
 * Concept A — "Arcade" (D-59 follow-up task, direction "A: arcade/neon lobby with big tiles").
 * Big, saturated, game-cabinet tiles in the lobby; the room is a full-bleed two-color arena with
 * one dominant UP/DOWN action, borrowing the crash-game "one graph, one clear action, nothing
 * else on screen" reading (`docs/product/design/research-game-ux.md` §2) — the arena hides the
 * app's own top nav chrome's visual weight by going full-bleed, though `Layout.tsx`'s DemoBanner/
 * header/footer stay mounted above and below it (this is a hidden /lab route inside the real app,
 * not a separate shell — honesty markers always stay visible, task requirement).
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
import "./ConceptA.css";

function TabBarA({ active }: { active: "lobby" | "stats" }) {
  return (
    <nav className="cA-tabbar" aria-label="Concept A navigation">
      <Link to="/lab/concepts/a" className={`cA-tab ${active === "lobby" ? "cA-tab--active" : ""}`}>
        <span className="cA-tab__icon" aria-hidden="true">
          🎮
        </span>
        Lobby
      </Link>
      <Link to="/lab/concepts/a/stats" className={`cA-tab ${active === "stats" ? "cA-tab--active" : ""}`}>
        <span className="cA-tab__icon" aria-hidden="true">
          🏆
        </span>
        Stats
      </Link>
    </nav>
  );
}

export function ConceptALobby() {
  return (
    <div className="cA-root">
      <div className="cA-lobby-head">
        <h1 className="cA-lobby-title">Pick a game</h1>
        <p className="cA-lobby-sub">Call the next hour of BTC. More tables coming.</p>
      </div>
      <div className="cA-tile-grid">
        {PAIRS.map((pair) =>
          pair.status === "live" ? (
            <Link key={pair.id} to={`/lab/concepts/a/room/${pair.id}`} className={`cA-tile cA-tile--live ${pair.hot ? "cA-tile--hot" : ""}`}>
              {pair.hot && <span className="cA-tile__hot">HOT</span>}
              <span className="cA-tile__glyph" aria-hidden="true">
                ₿
              </span>
              <span className="cA-tile__symbol">{pair.symbol}</span>
              <span className="cA-tile__meta">
                <span className="cA-tile__live-dot" aria-hidden="true" /> {pair.players} playing
              </span>
              <span className="cA-tile__cta">PLAY →</span>
            </Link>
          ) : (
            <div key={pair.id} className="cA-tile cA-tile--soon" aria-disabled="true">
              <span className="cA-tile__lock" aria-hidden="true">
                🔒
              </span>
              <span className="cA-tile__symbol">{pair.symbol}</span>
              <span className="cA-tile__meta">{pair.symbol} — coming soon</span>
            </div>
          ),
        )}
      </div>
      <TabBarA active="lobby" />
    </div>
  );
}

export function ConceptARoom() {
  const { pairId } = useParams<{ pairId: string }>();
  const pair = pairById(pairId);
  const [params] = useSearchParams();
  const phase = (params.get("phase")?.toUpperCase() as "BETTING" | "RUNNING" | "SETTLED") || "BETTING";
  const nowMs = useClock(1000);
  const [direction, setDirection] = useState<Direction>("UP");
  const [leverage, setLeverage] = useState(10);
  const [stakeStr, setStakeStr] = useState("500");
  const stake = Number(stakeStr) || 0;
  const room = buildRoomState(phase, nowMs, { direction, leverage, stake });
  useFireFromQuery(`concept-a-${pair.id}`);

  const totalStake = room.upStake + room.downStake;
  const upPct = totalStake > 0 ? Math.round((room.upStake / totalStake) * 100) : 50;

  return (
    <div className="cA-root cA-room">
      <div className="cA-room-top">
        <Link to="/lab/concepts/a" className="cA-back" aria-label="Back to lobby">
          ←
        </Link>
        <div className="cA-room-price">
          <span className="cA-room-symbol">{pair.symbol}/USD</span>
          <span className="cA-room-price-num mono">${fmtPrice(room.currentPrice)}</span>
        </div>
        <div className={`cA-countdown ${phase === "BETTING" ? "" : "cA-countdown--live"}`}>
          {phase === "SETTLED" ? "DONE" : formatCountdown(Math.max(0, room.endMs - nowMs))}
        </div>
      </div>
      <OracleLine />

      {phase !== "SETTLED" && (
        <div className="cA-arena">
          <button
            type="button"
            className={`cA-arena__side cA-arena__side--up ${direction === "UP" ? "cA-arena__side--selected" : ""}`}
            style={{ flexBasis: `${upPct}%` }}
            onClick={() => setDirection("UP")}
            disabled={phase !== "BETTING"}
          >
            <DirectionGlyph direction="UP" />
            <span>UP</span>
            <span className="cA-arena__pct mono">{upPct}%</span>
          </button>
          <button
            type="button"
            className={`cA-arena__side cA-arena__side--down ${direction === "DOWN" ? "cA-arena__side--selected" : ""}`}
            style={{ flexBasis: `${100 - upPct}%` }}
            onClick={() => setDirection("DOWN")}
            disabled={phase !== "BETTING"}
          >
            <DirectionGlyph direction="DOWN" />
            <span>DOWN</span>
            <span className="cA-arena__pct mono">{100 - upPct}%</span>
          </button>
        </div>
      )}

      {phase === "BETTING" && (
        <div className="cA-slip">
          <LeverageDial id="cA-leverage" value={leverage} min={2} max={40} onChange={setLeverage} />
          <div className="cA-stake-row">
            <div className="cA-stake-hero num-lg mono">{fmtStake(stake)} PLAY</div>
            <div className="cA-chips">
              {[100, 500, 1000].map((amt) => (
                <button key={amt} type="button" className="stake-chip" onClick={() => setStakeStr(String(stake + amt))}>
                  +{amt}
                </button>
              ))}
              <button type="button" className="stake-chip stake-chip--max" onClick={() => setStakeStr("10000")}>
                MAX
              </button>
            </div>
          </div>
          <LiquidationBar
            entryPrice={room.openPrice.toFixed(2)}
            liquidationPrice={room.own.liquidationPrice.toFixed(2)}
            percentToLiquidation={ownPercentToLiquidation(room)}
          />
          <button type="button" className={`cA-place-btn cA-place-btn--${direction.toLowerCase()}`}>
            PLACE BET · {direction}
          </button>
        </div>
      )}

      {phase === "RUNNING" && (
        <div className="cA-live">
          <div className="cA-live-pnl">
            <span className="faint">Your position, unrealized</span>
            <span className={`num-lg mono ${ownLivePnl(room) >= 0 ? "outcome-won" : "outcome-lost"}`}>
              {fmtSigned(ownLivePnl(room))} PLAY
            </span>
          </div>
          <LiquidationBar
            entryPrice={room.own.entryPrice.toFixed(2)}
            liquidationPrice={room.own.liquidationPrice.toFixed(2)}
            percentToLiquidation={ownPercentToLiquidation(room)}
          />
          <h3 className="cA-section-title">In this round</h3>
          <ul className="cA-roster">
            {room.positions.map((p) => (
              <li key={p.nickname} className={`cA-roster__row cA-roster__row--${p.direction.toLowerCase()}`}>
                <DirectionGlyph direction={p.direction} /> {p.nickname} <BotBadge isBot={p.isBot} />
                <span className="mono faint"> x{p.leverage}</span>
                <span className="mono cA-roster__stake">{fmtStake(p.stake)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {phase === "SETTLED" && (
        <div className="cA-settled">
          <p className="cA-settled__headline">
            BTC closed UP — ${fmtPrice(room.openPrice)} → ${fmtPrice(room.closePrice)}
          </p>
          <Link to={`/lab/concepts/a/room/${pair.id}`} className="cA-place-btn cA-place-btn--up">
            NEXT ROUND
          </Link>
        </div>
      )}
    </div>
  );
}

export function ConceptAStats() {
  const [tab, setTab] = useState<"crowd" | "ratings" | "profile">("crowd");
  return (
    <div className="cA-root">
      <div className="cA-segmented" role="tablist">
        {(["crowd", "ratings", "profile"] as const).map((t) => (
          <button key={t} type="button" role="tab" aria-selected={tab === t} className={`cA-segment ${tab === t ? "cA-segment--active" : ""}`} onClick={() => setTab(t)}>
            {t === "crowd" ? "Crowd" : t === "ratings" ? "Ratings" : "Profile"}
          </button>
        ))}
      </div>

      {tab === "crowd" && (
        <div className="cA-duel">
          <div className="cA-duel__card cA-duel__card--crowd">
            <span className="faint">CROWD</span>
            <span className="num-lg mono outcome-won">$146,900</span>
          </div>
          <span className="cA-duel__vs">VS</span>
          <div className="cA-duel__card cA-duel__card--market">
            <span className="faint">HOLD BTC</span>
            <span className="num-lg mono">$122,400</span>
          </div>
          <div className="cA-duel__full">
            <CrowdChart history={MOCK_CROWD_HISTORY} variant="compact" />
          </div>
          <OracleLine />
        </div>
      )}

      {tab === "ratings" && (
        <div>
          <h3 className="cA-section-title">Richest this season</h3>
          <LeaderboardList
            entries={MOCK_RICH_ENTRIES.map((e) => ({
              rank: e.rank,
              player: e.player,
              value: `${fmtSigned(e.growth, 2)} PLAY`,
              valueClassName: e.growth >= 0 ? "outcome-won" : "outcome-lost",
            }))}
          />
        </div>
      )}

      {tab === "profile" && (
        <div className="cA-profile">
          <h2>{MOCK_MY_PROFILE.player.displayName}</h2>
          <div className="cA-profile__stats">
            <div className="stat-tile">
              <div className="stat-tile__label">Rounds played</div>
              <div className="stat-tile__value">{MOCK_MY_PROFILE.roundsPlayed}</div>
            </div>
            <div className="stat-tile">
              <div className="stat-tile__label">Accuracy</div>
              <div className="stat-tile__value">{Math.round(MOCK_MY_PROFILE.accuracy * 100)}%</div>
            </div>
          </div>
          <h3 className="cA-section-title">History</h3>
          <ul className="cA-roster">
            {MOCK_MY_PROFILE.history.map((h) => (
              <li key={h.roundId} className={`cA-roster__row cA-roster__row--${h.direction.toLowerCase()}`}>
                <DirectionGlyph direction={h.direction} /> {h.interval} · x{h.leverage}
                <span className={`mono cA-roster__stake ${h.net >= 0 ? "outcome-won" : "outcome-lost"}`}>
                  {fmtSigned(h.net)} {h.liquidated && "· LIQ"}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <TabBarA active="stats" />
    </div>
  );
}
