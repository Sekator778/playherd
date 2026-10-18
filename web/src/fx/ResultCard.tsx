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
import { outcomeClassName, outcomeFromNet } from "../api/outcome";
import { useMarkets } from "../api/useMarkets";
import { backupRoundOf, type BackupRound } from "../components/marketModel";
import { formatDuration, formatPlayAmount, rollingPlayFormat } from "../format";
import { RollingDigits } from "./RollingDigits";
import { usePrefersReducedMotion } from "./reducedMotion";
import { burst, spawnCoinArc } from "./particles";
import { impact, notification } from "./haptics";
import { playLiquidationCrack, playLossThud, playTick, playWinJingle, primeAudioContext } from "./sound";
import { useFxSettings } from "./fxSettings";
import { useT, type TFn } from "../i18n";
import { Rich } from "../i18n/Rich";
import { TrophyIcon, CrackIcon, MinusDiscIcon, EqualsDiscIcon, SpeakerOnIcon, SpeakerMuteIcon } from "../components/icons";

// Tier tables — result-moment.md §2. Durations in ms.
// T1 raised 2026-09-26 (Р-59 follow-up, Alex: full-stake WIN/LOSS on Android "не заметил
// вообще") — T1's own coins/sparks/duration were still each individually less than T2's (kept
// that way below), but a ~1s total on-screen time made even a full-attention viewer likely to
// miss it; see result-moment.md §2 changelog for the reasoning and the full before/after table.
const WIN_TABLE: Record<Tier, { coins: number; sparks: number; confetti: number; glowPulses: number; countUpMs: number; holdMs: number; staggerMs: number }> = {
  1: { coins: 9, sparks: 16, confetti: 0, glowPulses: 1, countUpMs: 600, holdMs: 650, staggerMs: 50 },
  2: { coins: 14, sparks: 30, confetti: 0, glowPulses: 2, countUpMs: 800, holdMs: 700, staggerMs: 45 },
  3: { coins: 26, sparks: 60, confetti: 20, glowPulses: 3, countUpMs: 1100, holdMs: 900, staggerMs: 32 },
  4: { coins: 42, sparks: 90, confetti: 40, glowPulses: 4, countUpMs: 1500, holdMs: 1200, staggerMs: 22 },
};
const LOSS_TABLE: Record<Tier, { shards: number; shakeAmp: number; flashOpacity: number; countDownMs: number; holdMs: number }> = {
  1: { shards: 7, shakeAmp: 3, flashOpacity: 0.22, countDownMs: 550, holdMs: 550 },
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

/** "3 MIN" for the series an event's round was in: the round's own length when the event carries it
 *  (`ResultEvent.durationSec`, from the server's history row or the round's window); otherwise the length of the
 *  market's series, and nothing when the market has left the list — never "Hourly"/"Daily" for a 3-minute round. */
function useSeriesLabel(event: Pick<ResultEvent, "marketId" | "interval" | "durationSec">, t: TFn): string | null {
  const { byId } = useMarkets();
  if (event.durationSec !== undefined && Number.isFinite(event.durationSec) && event.durationSec > 0) return formatDuration(event.durationSec, t);
  const series = byId.get(event.marketId)?.series.find((s) => s.interval === event.interval);
  return series ? formatDuration(series.durationSec, t) : null;
}

/** An `ODDS` result is worded as odds: the event's own kind when it carries one, else the market's, if it is listed. */
function useIsOdds(event: Pick<ResultEvent, "marketId" | "marketKind">): boolean {
  const { byId } = useMarkets();
  return (event.marketKind ?? byId.get(event.marketId)?.kind) === "ODDS";
}

/** The round settled on its market's BACKUP exchange (Р-76: a round runs entirely on one exchange, chosen when it starts):
 *  the event carries the exchange the round ran on (a live settlement does; a history row does not), the market list says
 *  which exchange is the main one. `null` otherwise — a result on the main exchange says nothing of any. */
function useBackupRound(event: Pick<ResultEvent, "marketId" | "venue">, t: TFn): BackupRound | null {
  const { byId } = useMarkets();
  return backupRoundOf({ source: event.venue }, byId.get(event.marketId), t);
}

function SingleResultCard({ event, onDismiss }: { event: ResultEvent; onDismiss: () => void }) {
  const reducedMotion = usePrefersReducedMotion();
  const fx = useFxSettings();
  const tr = useT();
  const seriesLabel = useSeriesLabel(event, tr);
  const odds = useIsOdds(event);
  const backupRound = useBackupRound(event, tr);
  const cardRef = useRef<HTMLDivElement>(null);
  // Coin-arc launch point for WIN (below) — deliberately the icon, not the whole card: the
  // card's own bounding-rect center used to be the arc's origin, which on a WIN sits at/just
  // below the headline amount, so the trail of freshly-spawned coins rendered right on top of it
  // and hid it (polish item 1, 2026-09-26). The icon sits above the headline, and the arc's
  // quadratic-bezier control point is always further up still (`spawnCoinArc`), so every coin's
  // path moves away from the headline from the very first frame instead of through it.
  const iconRef = useRef<HTMLDivElement>(null);
  const dismissTimer = useRef<number | undefined>(undefined);
  // One dismissal per card, from whoever asks first — the hold timer, a tap on the card, on the dimmed area around it, on
  // "View round". Taps bubble from the card to the overlay, and a second `onDismiss` would skip the NEXT moment as well.
  const dismissedRef = useRef(false);
  const dismiss = () => {
    if (dismissedRef.current) return;
    dismissedRef.current = true;
    window.clearTimeout(dismissTimer.current);
    onDismiss();
  };
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
          ? // The mid-round moment carries a sentence more to read (the stake is lost, the round runs on, a
            // cancelled round refunds it): 2.8 s, tap to skip like every moment.
            event.midRound
            ? 2800
            : 1400
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
      // T1 raised light->medium alongside the table above (2026-09-26) — `vibrate(15)` was easy
      // to miss entirely; `vibrate(30)` (T2's strength) is still below T3/T4's "heavy".
      impact(event.tier >= 3 ? "heavy" : "medium");
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
        const coinOriginRect = iconRef.current?.getBoundingClientRect() ?? originRect;
        // The header lift (fx.css) only needs to be up for as long as the coin-arc is actually in
        // flight — added here, removed the moment the last coin lands (or on unmount/dismiss, see
        // the dismiss-timer effect below) so it never lingers over a LOSS/LIQUIDATION/EVEN that
        // queues right after.
        if (coinTargetRect) document.body.classList.add("fx-header-lift");
        requestAnimationFrame(() => {
          if (t.sparks > 0) burst(originRect, { kind: "spark", count: t.sparks });
          if (coinTargetRect) {
            spawnCoinArc(coinOriginRect, coinTargetRect, {
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
      // T1 raised light->medium alongside the table above (2026-09-26) — see the WIN branch's
      // comment; a loss deserves at least as strong a nudge as a same-tier win.
      impact(event.tier >= 3 ? "heavy" : "medium");
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
    dismissTimer.current = window.setTimeout(dismiss, reducedMotion ? Math.min(total, holdMs + 200) : total);
    return () => {
      window.clearTimeout(dismissTimer.current);
      // Belt-and-braces: a tap-to-skip can dismiss the card before the coin-arc's `onAllArrive`
      // ever fires. Removing a class that isn't there is a no-op, so this is safe to run every
      // time this card unmounts, not only WIN cards.
      document.body.classList.remove("fx-header-lift");
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const isLiquidation = event.kind === "LIQUIDATION";
  const kindClass = event.kind.toLowerCase();

  return (
    <div
      className={`result-overlay result-overlay--${kindClass} ${isLiquidation ? "result-overlay--toast" : ""} ${reducedMotion ? "result-overlay--reduced" : ""}`}
      onClick={dismiss}
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
      >
        <div ref={iconRef} className="result-card__icon" aria-hidden="true">
          {event.kind === "WIN" && <TrophyIcon />}
          {event.kind === "LOSS" && <MinusDiscIcon />}
          {event.kind === "LIQUIDATION" && <CrackIcon />}
          {event.kind === "EVEN" && <EqualsDiscIcon />}
        </div>

        {isLiquidation && <div className="result-card__stamp">{tr("results.liquidated")}</div>}

        <div className={`result-card__title num-xl mono result-card__title--${kindClass}`}>
          {event.kind === "EVEN" ? (
            "0 PLAY"
          ) : (
            <RollingDigits
              value={event.netPlay}
              durationMs={activeMs || 400}
              format={rollingPlayFormat(event.netPlay, { sign: true, unit: true })}
            />
          )}
        </div>
        {event.kind === "EVEN" && <p className="result-card__secondary">{tr("results.noGainNoLoss")}</p>}
        {event.kind !== "EVEN" && (
          <p className="result-card__secondary">
            <strong className="result-card__symbol">{event.symbol}</strong>
            {odds ? ` ${tr("results.oddsSuffix")}` : ""} · {tr("results.stakeLine", { stake: formatPlayAmount(event.stakePlay), leverage: event.leverage })}
            {seriesLabel ? ` · ${tr("results.lengthRound", { length: seriesLabel })}` : ""}
          </p>
        )}
        {event.kind === "EVEN" && (
          <p className="result-card__secondary">
            <strong className="result-card__symbol">{event.symbol}</strong>
            {odds ? ` ${tr("results.oddsSuffix")}` : ""}
            {seriesLabel ? ` · ${tr("results.lengthRound", { length: seriesLabel })}` : ""}
          </p>
        )}
        {/* What came back, next to the signed result (Р-98): a loss at x100 reads as a small difference in the balance otherwise. The headline
            stays the net result; this is the stake and what is returned of it (the payout), never a replacement for the sign. */}
        {event.stakePlay > 0 && !event.midRound && (
          <p className="result-card__returned mono">
            {tr("results.returned", { stake: formatPlayAmount(event.stakePlay), returned: formatPlayAmount(Math.max(0, event.stakePlay + event.netPlay)) })}
            {event.feesPlay != null && event.feesPlay > 0 && <span className="faint result-card__fees"> · {tr("results.fees", { fees: formatPlayAmount(event.feesPlay) })}</span>}
          </p>
        )}
        {/* Р-76: a round that ran on the backup exchange says whose prices settled it — one quiet line, under the figures. */}
        {backupRound && event.kind !== "LIQUIDATION" && (
          <p className="result-card__venue">
            {tr("results.venue", { name: backupRound.name, primary: backupRound.primaryName })}
          </p>
        )}
        {/* Р-50 / Р-7, said plainly while it is true: liquidated NOW, not at the end of the round. And the one rule
            that can still change it, said just as plainly: a round that gets cancelled refunds every bet in it,
            liquidated ones too (Р-23) — so "lost" is what happens when the round settles, which it has not yet. */}
        {event.kind === "LIQUIDATION" && event.midRound && (
          <p className="result-card__note">
            <Rich text={tr("results.midRound")} />
          </p>
        )}

        {!isLiquidation && (
          <p className="result-card__balance mono">
            {tr("results.balance")} <RollingDigits value={balanceDisplay} durationMs={200} format={rollingPlayFormat(balanceDisplay, { unit: true })} />
          </p>
        )}

        <div className="result-card__actions">
          <Link to={`/round/${event.roundId}`} className="result-card__link" onClick={dismiss}>
            {tr("results.viewRound")}
          </Link>
          <button
            type="button"
            className="result-card__sound-toggle"
            aria-label={fx.sfxEnabled ? tr("results.mute") : tr("results.unmute")}
            onClick={(e) => {
              e.stopPropagation();
              if (!fx.sfxEnabled) primeAudioContext();
              fx.setSfxEnabled(!fx.sfxEnabled);
            }}
          >
            {fx.sfxEnabled ? <SpeakerOnIcon /> : <SpeakerMuteIcon />}
          </button>
        </div>
        <p className="result-card__hint faint">{tr("results.tapDismiss")}</p>
      </div>
    </div>
  );
}

/** How long the "+N more results" summary stays when nobody touches it — a little longer than a single moment (it names
 *  more), but it LEAVES, like every other card: it used to sit there, dimming and blurring the app, until it was tapped. */
const OVERFLOW_HOLD_MS = 3000;
/** Once the player has opened the list to read it, the list is longer and so is the hold; it still leaves by itself. */
const OVERFLOW_OPEN_HOLD_MS = 8000;

function resultClass(e: ResultEvent): string {
  return e.kind === "LIQUIDATION" ? "outcome-lost" : outcomeClassName(outcomeFromNet(e.netPlay));
}

/** One result on a line: the signed net first, then which bet it was — "-0.12 PLAY  BTC · x2", "liquidated · BTC · x2". */
function ResultLine({ event }: { event: ResultEvent }) {
  const tr = useT();
  return (
    <>
      <span className={resultClass(event)}>{formatPlayAmount(event.netPlay, { sign: true })} PLAY</span>{" "}
      <span className="muted">
        {event.kind === "LIQUIDATION" ? `${tr("results.liquidatedPrefix")} · ` : ""}
        {event.symbol} · x{event.leverage}
      </span>
    </>
  );
}

/** Whether focus got where it is the way the keyboard (or a screen reader) brings it — `:focus-visible` — and not by a tap or a
 *  click on a button, which focuses it too. A browser that cannot answer is taken to say yes: held, not cut short. */
function focusCameByKeyboard(target: EventTarget): boolean {
  try {
    return (target as Element).matches(":focus-visible");
  } catch {
    return true;
  }
}

function OverflowCard({ events, onDismiss }: { events: ResultEvent[]; onDismiss: () => void }) {
  const tr = useT();
  const [expanded, setExpanded] = useState(false);
  // One dismissal, whoever asks first (the hold timer, the dimmed area, the button) — as on a single card.
  const dismissedRef = useRef(false);
  const dismiss = () => {
    if (dismissedRef.current) return;
    dismissedRef.current = true;
    onDismiss();
  };
  // A card that is being READ is not taken away: while a pointer rests on it, or the keyboard (a screen reader) is on one of its
  // controls, its clock does not run, and when the pointer or the focus leaves it starts again — a full hold, not what was left of
  // it. (A tap or a click that focuses a button does not count as the keyboard: the card would then wait for the next tap, which
  // is how it used to behave, dimming and blurring the app until someone touched it.)
  const [pointerInside, setPointerInside] = useState(false);
  const [focusInside, setFocusInside] = useState(false);
  const held = pointerInside || focusInside;
  useEffect(() => {
    if (held) return;
    const timer = window.setTimeout(dismiss, expanded ? OVERFLOW_OPEN_HOLD_MS : OVERFLOW_HOLD_MS);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [expanded, held]);
  const sum = events.reduce((acc, e) => acc + e.netPlay, 0);
  return (
    <div className="result-overlay result-overlay--overflow" onClick={dismiss} role="status" aria-live="polite">
      <div
        className="result-card result-card--overflow"
        onClick={(e) => e.stopPropagation()}
        onPointerEnter={() => setPointerInside(true)}
        onPointerLeave={() => setPointerInside(false)}
        onFocus={(e) => {
          if (focusCameByKeyboard(e.target)) setFocusInside(true);
        }}
        onBlur={(e) => {
          // focus moving from one control of the card to another is not leaving it
          if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setFocusInside(false);
        }}
      >
        {events.length === 1 ? (
          // A "summary" of one result is that result: named, not "+1 more results" behind a tap.
          <>
            <p className="result-card__overflow-header result-card__overflow-header--one">{tr("results.oneMore")}</p>
            <ul className="result-card__overflow-list">
              <li>
                <ResultLine event={events[0]} />
              </li>
            </ul>
          </>
        ) : (
          <>
            <button type="button" className="result-card__overflow-header" aria-expanded={expanded} onClick={() => setExpanded((v) => !v)}>
              <span>
                {tr("results.moreResults", { count: events.length })} <span className={outcomeClassName(outcomeFromNet(sum))}>{formatPlayAmount(sum, { sign: true })} PLAY</span> {tr("results.net")}
              </span>
              <span aria-hidden="true">{expanded ? "▲" : "▼"}</span>
            </button>
            {expanded && (
              <ul className="result-card__overflow-list">
                {events.map((e) => (
                  <li key={e.id}>
                    <ResultLine event={e} />
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
        <button type="button" className="btn btn--secondary btn--block" onClick={dismiss}>
          {tr("results.dismiss")}
        </button>
      </div>
    </div>
  );
}

export function ResultCard({ display, onDismiss }: { display: DisplayItem; onDismiss: () => void }) {
  const key = display.kind === "single" ? display.event.id : `overflow:${display.events.length}:${display.events[0]?.id}`;
  // `key` on the element itself: the card that FOLLOWS another in the queue is a new card, not the old one with new props.
  // Without it React kept the first card's component instance — so the second showed the first one's balance, played none
  // of its own sound/vibration/particles, and (its dismiss timer is set on mount only) never left by itself: a full-screen
  // overlay over the player's next bet until tapped (first live run, two bets settling at one boundary).
  const node = useMemo(() => {
    if (display.kind === "single") return <SingleResultCard key={key} event={display.event} onDismiss={onDismiss} />;
    return <OverflowCard key={key} events={display.events} onDismiss={onDismiss} />;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return node;
}
