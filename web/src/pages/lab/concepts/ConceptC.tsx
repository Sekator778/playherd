/**
 * Concept C — "Call it" (Р-59 follow-up task, direction "C: minimal card-swipe 'call it' game").
 * The opposite bet from A/B: instead of more game chrome (arenas, feeds, reactions), strip the
 * screen down to one huge number and one decision at a time — closest in spirit to the crash-game
 * reading in `research-game-ux.md` §2/§4 ("a crash game app... keep the screen clean, show only
 * what matters, and let the decision carry the drama"; §4: "ни crash-игры, ни Twitch Predictions
 * не показывают свечной график вообще"). The Stats card below drops the line chart entirely
 * (task's own example: "a 'Crowd vs Market' duel card *instead of* a trader chart") rather than
 * keeping a smaller chart alongside the duel framing the way concepts A/B do — see
 * `docs/product/design/concepts.md`'s recommendation for the tradeoff this creates.
 */
import { useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { useClock } from "../../../api/useClock";
import type { Direction } from "../../../api/contract";
import { DirectionGlyph } from "../../../components/icons";
import { LeverageDial } from "../../../components/LeverageDial";
import { LiquidationBar } from "../../../components/LiquidationBar";
import { LeaderboardList } from "../../../components/Leaderboard";
import { formatCountdown } from "../../../format";
import { MOCK_MY_PROFILE, MOCK_RICH_ENTRIES, PAIRS, buildRoomState, ownLivePnl, ownPercentToLiquidation, pairById } from "./mockData";
import { OracleLine, fmtPrice, fmtSigned, fmtStake, useFireFromQuery } from "./shared";
import "./ConceptC.css";

function DockC({ active }: { active: "play" | "you" }) {
  return (
    <nav className="cC-dock" aria-label="Concept C navigation">
      <Link to="/lab/concepts/c" className={`cC-dock__item ${active === "play" ? "cC-dock__item--active" : ""}`}>
        Play
      </Link>
      <Link to="/lab/concepts/c/stats" className={`cC-dock__item ${active === "you" ? "cC-dock__item--active" : ""}`}>
        You
      </Link>
    </nav>
  );
}

export function ConceptCLobby() {
  const [index, setIndex] = useState(0);
  const pair = PAIRS[index];

  return (
    <div className="cC-root">
      <p className="cC-eyebrow">Call the next hour</p>
      {pair.status === "live" ? (
        <Link to={`/lab/concepts/c/room/${pair.id}`} className="cC-card cC-card--live">
          <span className="cC-card__symbol">{pair.symbol}</span>
          <span className="cC-card__name">{pair.name}</span>
          <span className="cC-card__cta">CALL IT →</span>
        </Link>
      ) : (
        <div className="cC-card cC-card--soon" aria-disabled="true">
          <span className="cC-card__symbol">{pair.symbol}</span>
          <span className="cC-card__name">{pair.name}</span>
          <span className="cC-card__soon">Coming soon</span>
        </div>
      )}
      <div className="cC-dots">
        {PAIRS.map((p, i) => (
          <button key={p.id} type="button" className={`cC-dot ${i === index ? "cC-dot--active" : ""}`} aria-label={`Show ${p.symbol}`} onClick={() => setIndex(i)} />
        ))}
      </div>
      <DockC active="play" />
    </div>
  );
}

export function ConceptCRoom() {
  const { pairId } = useParams<{ pairId: string }>();
  const pair = pairById(pairId);
  const [params] = useSearchParams();
  const phase = (params.get("phase")?.toUpperCase() as "BETTING" | "RUNNING" | "SETTLED") || "BETTING";
  const nowMs = useClock(1000);
  const [direction, setDirection] = useState<Direction | null>(null);
  const [leverage, setLeverage] = useState(10);
  const [stakeStr, setStakeStr] = useState("500");
  const stake = Number(stakeStr) || 0;
  const room = buildRoomState(phase, nowMs, { direction: direction ?? "UP", leverage, stake });
  useFireFromQuery(`concept-c-${pair.id}`);

  return (
    <div className="cC-root cC-room">
      <div className="cC-top">
        <Link to="/lab/concepts/c" className="cC-back">
          ←
        </Link>
        <span className="cC-top-timer mono">{phase === "SETTLED" ? "Ended" : formatCountdown(Math.max(0, room.endMs - nowMs))}</span>
      </div>

      <p className="cC-price mono">${fmtPrice(room.currentPrice)}</p>
      <OracleLine />

      {phase === "BETTING" && direction === null && (
        <div className="cC-calls">
          <button type="button" className="cC-call cC-call--up" onClick={() => setDirection("UP")}>
            CALL UP
          </button>
          <button type="button" className="cC-call cC-call--down" onClick={() => setDirection("DOWN")}>
            CALL DOWN
          </button>
        </div>
      )}

      {phase === "BETTING" && direction !== null && (
        <div className="cC-slip">
          <p className="cC-slip__title">
            You called <DirectionGlyph direction={direction} /> <strong>{direction}</strong>
          </p>
          <LeverageDial id="cC-leverage" value={leverage} min={2} max={40} onChange={setLeverage} />
          <div className="cC-stake-row">
            <span className="num-lg mono">{fmtStake(stake)} PLAY</span>
            <div className="cC-stake-ticks">
              {[100, 500, 1000].map((amt) => (
                <button key={amt} type="button" className="stake-chip" onClick={() => setStakeStr(String(stake + amt))}>
                  +{amt}
                </button>
              ))}
            </div>
          </div>
          <LiquidationBar entryPrice={room.openPrice.toFixed(2)} liquidationPrice={room.own.liquidationPrice.toFixed(2)} percentToLiquidation={ownPercentToLiquidation(room)} />
          <button type="button" className="cC-lock-btn">
            LOCK IT IN
          </button>
          <button type="button" className="cC-change-call" onClick={() => setDirection(null)}>
            Change call
          </button>
        </div>
      )}

      {phase === "RUNNING" && (
        <div className="cC-waiting">
          <p className="cC-waiting__label">Your call is live</p>
          <p className={`cC-waiting__pnl mono ${ownLivePnl(room) >= 0 ? "outcome-won" : "outcome-lost"}`}>{fmtSigned(ownLivePnl(room))} PLAY</p>
          <LiquidationBar entryPrice={room.own.entryPrice.toFixed(2)} liquidationPrice={room.own.liquidationPrice.toFixed(2)} percentToLiquidation={ownPercentToLiquidation(room)} />
        </div>
      )}

      {phase === "SETTLED" && (
        <div className="cC-waiting">
          <p className="cC-waiting__label">BTC closed UP</p>
          <p className="cC-waiting__pnl mono">
            ${fmtPrice(room.openPrice)} → ${fmtPrice(room.closePrice)}
          </p>
          <Link to="/lab/concepts/c" className="cC-lock-btn" style={{ display: "block", textAlign: "center", textDecoration: "none" }}>
            NEXT
          </Link>
        </div>
      )}

      <DockC active="play" />
    </div>
  );
}

const STATS_CARDS = ["crowd", "ratings", "profile"] as const;
type StatsCard = (typeof STATS_CARDS)[number];

export function ConceptCStats() {
  const [card, setCard] = useState<StatsCard>("crowd");
  const idx = STATS_CARDS.indexOf(card);
  const go = (delta: number) => setCard(STATS_CARDS[(idx + delta + STATS_CARDS.length) % STATS_CARDS.length]);

  return (
    <div className="cC-root">
      <div className="cC-stat-card">
        {card === "crowd" && (
          <>
            <p className="cC-eyebrow">Crowd vs Market</p>
            <div className="cC-duel">
              <div className="cC-duel__num outcome-won">$146,900</div>
              <div className="cC-duel__vs">vs</div>
              <div className="cC-duel__num">$122,400</div>
            </div>
            <p className="cC-duel__caption">Crowd account is beating hold-BTC by +20% since day one.</p>
            <OracleLine />
          </>
        )}
        {card === "ratings" && (
          <>
            <p className="cC-eyebrow">Your rank</p>
            <p className="cC-big-rank">#3</p>
            <LeaderboardList
              entries={MOCK_RICH_ENTRIES.slice(0, 3).map((e) => ({
                rank: e.rank,
                player: e.player,
                value: `${fmtSigned(e.growth, 2)} PLAY`,
                valueClassName: e.growth >= 0 ? "outcome-won" : "outcome-lost",
              }))}
            />
          </>
        )}
        {card === "profile" && (
          <>
            <p className="cC-eyebrow">You</p>
            <h2 style={{ margin: "0 0 4px" }}>{MOCK_MY_PROFILE.player.displayName}</h2>
            <p className="faint" style={{ marginBottom: 12 }}>
              {MOCK_MY_PROFILE.roundsPlayed} rounds · {Math.round(MOCK_MY_PROFILE.accuracy * 100)}% accuracy
            </p>
            {MOCK_MY_PROFILE.history.slice(0, 3).map((h) => (
              <div key={h.roundId} className="cC-profile-row">
                <span>
                  {h.interval} · x{h.leverage}
                </span>
                <span className={h.net >= 0 ? "outcome-won" : "outcome-lost"}>{fmtSigned(h.net)}</span>
              </div>
            ))}
          </>
        )}
      </div>
      <div className="cC-card-nav">
        <button type="button" className="cC-arrow" onClick={() => go(-1)} aria-label="Previous card">
          ‹
        </button>
        <div className="cC-dots">
          {STATS_CARDS.map((c) => (
            <span key={c} className={`cC-dot ${c === card ? "cC-dot--active" : ""}`} />
          ))}
        </div>
        <button type="button" className="cC-arrow" onClick={() => go(1)} aria-label="Next card">
          ›
        </button>
      </div>
      <DockC active="you" />
    </div>
  );
}
