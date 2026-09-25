/**
 * The result-moment card itself (`docs/product/design/result-moment.md` §2–§4). One component,
 * branching heavily on `kind`/`tier`: WIN/LOSS/EVEN render as a full-screen moment (backdrop +
 * centered card, §3/§4); LIQUIDATION renders as a compact, backdrop-less toast (§2's "не общий
 * оверлей на весь экран... компактная toast-карточка" branch — always taken here rather than
 * trying to locate the specific position card in the DOM, a scoped-down but spec-permitted
 * fallback). `ResultMomentHost.tsx` is the only caller.
 */
import { useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { Link } from "react-router-dom";
import type { ResultEvent, Tier } from "./resultEvent";
import type { DisplayItem } from "./resultQueue";
import { formatPlayAmount } from "../format";
import { RollingDigits } from "./RollingDigits";
import { usePrefersReducedMotion } from "./reducedMotion";
import { burst, spawnCoinArc } from "./particles";
import { impact, notification } from "./haptics";
import { playLiquidationCrack, playLossThud, playTick, playWinJingle, primeAudioContext } from "./sound";
import { useFxSettings } from "./fxSettings";
import { TrophyIcon, CrackIcon, MinusDiscIcon, EqualsDiscIcon, SpeakerOnIcon, SpeakerMuteIcon } from "../components/icons";

// Tier tables — result-moment.md §2. Durations in ms.
const WIN_TABLE: Record<Tier, { coins: number; sparks: number; confetti: number; glowPulses: number; countUpMs: number; holdMs: number; staggerMs: number }> = {
  1: { coins: 6, sparks: 12, confetti: 0, glowPulses: 1, countUpMs: 500, holdMs: 500, staggerMs: 60 },
  2: { coins: 14, sparks: 30, confetti: 0, glowPulses: 2, countUpMs: 800, holdMs: 700, staggerMs: 45 },
  3: { coins: 26, sparks: 60, confetti: 20, glowPulses: 3, countUpMs: 1100, holdMs: 900, staggerMs: 32 },
  4: { coins: 42, sparks: 90, confetti: 40, glowPulses: 4, countUpMs: 1500, holdMs: 1200, staggerMs: 22 },
};
const LOSS_TABLE: Record<Tier, { shards: number; shakeAmp: number; flashOpacity: number; countDownMs: number; holdMs: number }> = {
  1: { shards: 4, shakeAmp: 2, flashOpacity: 0.18, countDownMs: 400, holdMs: 400 },
  2: { shards: 8, shakeAmp: 4, flashOpacity: 0.26, countDownMs: 600, holdMs: 550 },
  3: { shards: 14, shakeAmp: 7, flashOpacity: 0.32, countDownMs: 850, holdMs: 700 },
  4: { shards: 22, shakeAmp: 10, flashOpacity: 0.38, countDownMs: 1100, holdMs: 850 },
};
const LIQ_TABLE: Record<Tier, { cracks: number; shards: number; shakeAmp: number }> = {
  1: { cracks: 2, shards: 10, shakeAmp: 5 },
  2: { cracks: 3, shards: 16, shakeAmp: 8 },
  3: { cracks: 4, shards: 24, shakeAmp: 11 },
  4: { cracks: 6, shards: 36, shakeAmp: 14 },
};

function balancePillRect(): DOMRect | null {
  const el = document.querySelector(".balance-pill");
  return el ? el.getBoundingClientRect() : null;
}

function SingleResultCard({ event, onDismiss }: { event: ResultEvent; onDismiss: () => void }) {
  const reducedMotion = usePrefersReducedMotion();
  const fx = useFxSettings();
  const cardRef = useRef<HTMLDivElement>(null);
  const dismissTimer = useRef<number | undefined>(undefined);
  const playedRef = useRef(false);
  // The header balance pill is a plain, already-mounted DOM node (Layout.tsx, unrelated React
  // subtree) — looked up once, synchronously, so the initial `balanceDisplay` state below can
  // already tell whether a coin-arc animation is even going to happen, and the mount effect never
  // needs to call `setState` synchronously in its own body (react-hooks/set-state-in-effect) just
  // to handle the "no arc" fallback cases.
  const [coinTargetRect] = useState(() => (event.kind === "WIN" && !reducedMotion ? balancePillRect() : null));
  const [balanceDisplay, setBalanceDisplay] = useState(() => (coinTargetRect ? event.balanceBefore : event.balanceAfter));

  const holdMs =
    event.kind === "WIN"
      ? WIN_TABLE[event.tier].holdMs
      : event.kind === "LOSS"
        ? LOSS_TABLE[event.tier].holdMs
        : event.kind === "LIQUIDATION"
          ? 1400
          : 1200;
  const activeMs =
    event.kind === "WIN"
      ? WIN_TABLE[event.tier].countUpMs
      : event.kind === "LOSS"
        ? LOSS_TABLE[event.tier].countDownMs
        : 0;

  // Haptics + sound + particles fire exactly once per card, even under React StrictMode's double
  // effect invocation in dev (playedRef guards it).
  useEffect(() => {
    if (playedRef.current) return;
    playedRef.current = true;

    if (event.kind === "WIN") {
      const t = WIN_TABLE[event.tier];
      impact(event.tier >= 3 ? "heavy" : event.tier === 2 ? "medium" : "light");
      notification("success");
      playWinJingle(event.tier);
      if (!reducedMotion && cardRef.current) {
        // Spread the burst across a couple of rAF ticks rather than measuring+spawning every
        // sprite synchronously in this mount effect (implementation-plan.md §6's frame-budget
        // target; a T4 WIN's 90 sparks + 40 confetti in one frame measured 6/91 janky frames on
        // an Android emulator, one at ~200ms). Sparks + the coin-arc start next frame, once the
        // card itself has actually painted; confetti (purely decorative, never the honesty-
        // relevant coin-to-balance arc) waits one frame more.
        const originRect = cardRef.current.getBoundingClientRect();
        // The header lift (fx.css) only needs to be up for as long as the coin-arc is actually in
        // flight — added here, removed the moment the last coin lands (or on unmount/dismiss, see
        // the dismiss-timer effect below) so it never lingers over a LOSS/LIQUIDATION/EVEN that
        // queues right after.
        if (coinTargetRect) document.body.classList.add("fx-header-lift");
        requestAnimationFrame(() => {
          if (t.sparks > 0) burst(originRect, { kind: "spark", count: t.sparks });
          if (coinTargetRect) {
            spawnCoinArc(originRect, coinTargetRect, {
              count: t.coins,
              staggerMs: t.staggerMs,
              onEachArrive: () => setBalanceDisplay((b) => b + event.netPlay / t.coins),
              onAllArrive: () => {
                setBalanceDisplay(event.balanceAfter);
                document.body.classList.remove("fx-header-lift");
              },
            });
          }
          if (t.confetti > 0) {
            requestAnimationFrame(() => burst(originRect, { kind: "confetti", count: t.confetti }));
          }
        });
      }
    } else if (event.kind === "LOSS") {
      const t = LOSS_TABLE[event.tier];
      impact(event.tier >= 3 ? "heavy" : event.tier === 2 ? "medium" : "light");
      if (event.tier >= 3) notification("error");
      playLossThud(event.tier);
      if (!reducedMotion && cardRef.current) {
        const originRect = cardRef.current.getBoundingClientRect();
        requestAnimationFrame(() => burst(originRect, { kind: "shard", count: t.shards }));
      }
    } else if (event.kind === "LIQUIDATION") {
      const t = LIQ_TABLE[event.tier];
      impact(event.tier >= 2 ? "heavy" : "medium");
      if (event.tier >= 3) notification("error");
      playLiquidationCrack(event.tier);
      if (!reducedMotion && cardRef.current) {
        const originRect = cardRef.current.getBoundingClientRect();
        requestAnimationFrame(() => burst(originRect, { kind: "shard", count: t.shards, colors: ["#ff2e9f", "#b3006e"] }));
      }
    } else {
      playTick();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const total = activeMs + holdMs;
    dismissTimer.current = window.setTimeout(onDismiss, reducedMotion ? Math.min(total, holdMs + 200) : total);
    return () => {
      window.clearTimeout(dismissTimer.current);
      // Belt-and-braces: a tap-to-skip can dismiss the card before the coin-arc's `onAllArrive`
      // ever fires. Removing a class that isn't there is a no-op, so this is safe to run every
      // time this card unmounts, not only WIN cards.
      document.body.classList.remove("fx-header-lift");
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleTap = () => {
    window.clearTimeout(dismissTimer.current);
    onDismiss();
  };

  const isLiquidation = event.kind === "LIQUIDATION";
  const kindClass = event.kind.toLowerCase();

  return (
    <div
      className={`result-overlay result-overlay--${kindClass} ${isLiquidation ? "result-overlay--toast" : ""} ${reducedMotion ? "result-overlay--reduced" : ""}`}
      onClick={handleTap}
      role="status"
      aria-live="polite"
    >
      {event.kind === "LOSS" && (
        <div className="result-flash" style={{ "--flash-opacity": LOSS_TABLE[event.tier].flashOpacity } as CSSProperties} />
      )}
      <div
        ref={cardRef}
        className={`result-card result-card--${kindClass} result-card--tier-${event.tier}`}
        style={
          {
            "--glow-pulses": event.kind === "WIN" ? WIN_TABLE[event.tier].glowPulses : undefined,
            "--shake-amp": event.kind === "LOSS" ? `${LOSS_TABLE[event.tier].shakeAmp}px` : isLiquidation ? `${LIQ_TABLE[event.tier].shakeAmp}px` : undefined,
          } as CSSProperties
        }
        onClick={(e) => e.stopPropagation()}
      >
        <div className="result-card__icon" aria-hidden="true">
          {event.kind === "WIN" && <TrophyIcon />}
          {event.kind === "LOSS" && <MinusDiscIcon />}
          {event.kind === "LIQUIDATION" && <CrackIcon />}
          {event.kind === "EVEN" && <EqualsDiscIcon />}
        </div>

        {isLiquidation && <div className="result-card__stamp">LIQUIDATED</div>}

        <div className={`result-card__title num-xl mono result-card__title--${kindClass}`}>
          {event.kind === "EVEN" ? (
            "0 PLAY"
          ) : (
            <RollingDigits
              value={event.netPlay}
              durationMs={activeMs || 400}
              format={(n) => `${formatPlayAmount(n, { decimals: 2, sign: true })} PLAY`}
            />
          )}
        </div>
        {event.kind === "EVEN" && <p className="result-card__secondary">No gain, no loss</p>}
        {event.kind !== "EVEN" && (
          <p className="result-card__secondary">
            stake {formatPlayAmount(event.stakePlay, { decimals: 0 })} PLAY · x{event.leverage}
            {event.interval === "DAILY" ? " · daily round" : ""}
          </p>
        )}

        {!isLiquidation && (
          <p className="result-card__balance mono">
            Balance: <RollingDigits value={balanceDisplay} durationMs={200} format={(n) => `${formatPlayAmount(n, { decimals: 0 })} PLAY`} />
          </p>
        )}

        <div className="result-card__actions">
          <Link to={`/round/${event.roundId}`} className="result-card__link" onClick={handleTap}>
            View round
          </Link>
          <button
            type="button"
            className="result-card__sound-toggle"
            aria-label={fx.sfxEnabled ? "Mute sound" : "Unmute sound"}
            onClick={(e) => {
              e.stopPropagation();
              if (!fx.sfxEnabled) primeAudioContext();
              fx.setSfxEnabled(!fx.sfxEnabled);
            }}
          >
            {fx.sfxEnabled ? <SpeakerOnIcon /> : <SpeakerMuteIcon />}
          </button>
        </div>
        <p className="result-card__hint faint">Tap to dismiss</p>
      </div>
    </div>
  );
}

function OverflowCard({ events, onDismiss }: { events: ResultEvent[]; onDismiss: () => void }) {
  const [expanded, setExpanded] = useState(false);
  const sum = events.reduce((acc, e) => acc + e.netPlay, 0);
  return (
    <div className="result-overlay result-overlay--overflow" onClick={onDismiss} role="status" aria-live="polite">
      <div className="result-card result-card--overflow" onClick={(e) => e.stopPropagation()}>
        <button type="button" className="result-card__overflow-header" onClick={() => setExpanded((v) => !v)}>
          <span>
            +{events.length} more results — {formatPlayAmount(sum, { decimals: 2, sign: true })} PLAY net
          </span>
          <span aria-hidden="true">{expanded ? "▲" : "▼"}</span>
        </button>
        {expanded && (
          <ul className="result-card__overflow-list">
            {events.map((e) => (
              <li key={e.id}>
                <span className={e.kind === "WIN" ? "outcome-won" : e.kind === "LOSS" || e.kind === "LIQUIDATION" ? "outcome-lost" : "outcome-even"}>
                  {formatPlayAmount(e.netPlay, { decimals: 2, sign: true })} PLAY
                </span>{" "}
                <span className="muted">
                  {e.kind === "LIQUIDATION" ? "liquidated · " : ""}
                  {e.interval.toLowerCase()} round · x{e.leverage}
                </span>
              </li>
            ))}
          </ul>
        )}
        <button type="button" className="btn btn--secondary btn--block" onClick={onDismiss}>
          Dismiss
        </button>
      </div>
    </div>
  );
}

export function ResultCard({ display, onDismiss }: { display: DisplayItem; onDismiss: () => void }) {
  const key = display.kind === "single" ? display.event.id : `overflow:${display.events.length}:${display.events[0]?.id}`;
  const node = useMemo(() => {
    if (display.kind === "single") return <SingleResultCard event={display.event} onDismiss={onDismiss} />;
    return <OverflowCard events={display.events} onDismiss={onDismiss} />;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return node;
}
