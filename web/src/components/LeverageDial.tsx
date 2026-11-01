import { useEffect, useRef, type CSSProperties } from "react";
import { startAmbientEmission } from "../fx/particles";
import { impact } from "../fx/haptics";
import { usePrefersReducedMotion } from "../fx/reducedMotion";
import { HEAT_VAR, leverageHeatTier, type HeatTier } from "./heat";
import { useT, type TKey } from "../i18n";
import { Rich } from "../i18n/Rich";
import { DEFAULT_LEVERAGE_LIMIT, SLIDER_STEPS, leverageAfterMove, leverageToPosition, usesLogScale } from "./leverageScale";

/**
 * "Power dial" leverage control (`docs/product/design/live-round.md` §1) — replaces the unstyled
 * `<input type="range">` the audit flagged ("x10 and x39 look identical apart from the number in
 * the caption"). Stays a real range input under the hood (keyboard/screen-reader semantics for
 * free) — only the visual layer changes: a filled track colored by the heat ramp
 * (`index.css`'s `--heat-1..5`), a big tabular-nums value above it, and a subtle pulse in the
 * top "Flame" zone (x34-40) — the "leverage should heat up with the size of the win, so that there is
 * momentum" Alex asked for. Ambient sparks (Hot/Danger/Flame zones) and per-step haptics from
 * live-round.md §1's table are wired below via `fx/particles.ts`/`fx/haptics.ts`.
 */
const ZONE_LABEL: Record<HeatTier, TKey> = {
  1: "dial.calm",
  2: "dial.warm",
  3: "dial.hot",
  4: "dial.danger",
  5: "dial.flame",
};

/** live-round.md §1's ambient-particle rate per zone — `0` for Calm/Warm (no particles there). */
const AMBIENT_RATE: Record<HeatTier, number> = { 1: 0, 2: 0, 3: 1.5, 4: 5, 5: 9 };
/** Haptic step interval per zone — fires every Nth step change; `1` = every step (Flame). */
const HAPTIC_STEP_INTERVAL: Record<HeatTier, number> = { 1: 0, 2: 0, 3: 5, 4: 2, 5: 1 };
const HAPTIC_MIN_GAP_MS = 80; // Flame's "throttle ≥80ms between ticks, not a continuous buzz"

export function LeverageDial({
  id,
  value,
  min,
  max,
  onChange,
}: {
  id: string;
  value: number;
  min: number;
  max: number;
  onChange: (value: number) => void;
}) {
  const t = useT();
  // The zones are fractions of the dial's range up to x100; above x100 every value is Flame (x100 is no longer "calm" on a x1200 cap).
  const tier = leverageHeatTier(value, Math.min(max, DEFAULT_LEVERAGE_LIMIT));
  // Above x100 the track is logarithmic (`leverageScale.ts`): the input's value is a position, not the leverage itself.
  const logScale = usesLogScale(max);
  const position = logScale ? leverageToPosition(value, min, max) : value;
  const pct = logScale ? (position / SLIDER_STEPS) * 100 : ((value - min) / (max - min)) * 100;
  const style = {
    "--fill-pct": `${pct}%`,
    "--heat-color": HEAT_VAR[tier],
  } as CSSProperties;

  const reducedMotion = usePrefersReducedMotion();
  const trackRef = useRef<HTMLInputElement>(null);
  const prevValueRef = useRef(value);
  const prevTierRef = useRef(tier);
  const stepsSinceHapticRef = useRef(0);
  const lastHapticAtRef = useRef(0);

  // Ambient sparks/flame near the thumb, Hot zone and up (live-round.md §1) — restarted whenever
  // the zone changes so the rate always matches the CURRENT zone, not the one it started in.
  useEffect(() => {
    if (reducedMotion) return;
    const rate = AMBIENT_RATE[tier];
    if (rate <= 0) return;
    const stop = startAmbientEmission(
      () => {
        const el = trackRef.current;
        if (!el) return null;
        const rect = el.getBoundingClientRect();
        const p = logScale ? el.valueAsNumber / SLIDER_STEPS : (el.valueAsNumber - min) / (max - min);
        return new DOMRect(rect.left + p * rect.width - 4, rect.top + rect.height / 2 - 4, 8, 8);
      },
      "spark",
      rate,
    );
    return stop;
  }, [tier, reducedMotion, min, max, logScale]);

  const handleChange = (next: number) => {
    const crossedIntoHotter = next > prevValueRef.current && leverageHeatTier(next, max) > prevTierRef.current;
    const nextTier = leverageHeatTier(next, max);
    const interval = HAPTIC_STEP_INTERVAL[nextTier];
    const now = performance.now();
    if (crossedIntoHotter && nextTier === 5) {
      impact("medium"); // "a single Medium impact on the first crossing into this zone"
      lastHapticAtRef.current = now;
    } else if (interval > 0) {
      stepsSinceHapticRef.current += 1;
      if (stepsSinceHapticRef.current >= interval && now - lastHapticAtRef.current >= HAPTIC_MIN_GAP_MS) {
        impact("light");
        stepsSinceHapticRef.current = 0;
        lastHapticAtRef.current = now;
      }
    }
    prevValueRef.current = next;
    prevTierRef.current = nextTier;
    onChange(next);
  };

  return (
    <div className={`leverage-dial ${tier === 5 ? "leverage-dial--flame" : ""}`} style={style}>
      <div className="leverage-dial__value">x{value}</div>
      <input
        ref={trackRef}
        id={id}
        type="range"
        min={logScale ? 0 : min}
        max={logScale ? SLIDER_STEPS : max}
        step={1}
        value={position}
        aria-valuetext={`x${value}`}
        onChange={(e) => handleChange(logScale ? leverageAfterMove(Number(e.target.value), value, min, max) : Number(e.target.value))}
        aria-describedby={`${id}-zone`}
      />
      <div className="leverage-dial__foot">
        <span id={`${id}-zone`} className="leverage-dial__zone">
          {t(ZONE_LABEL[tier])}
        </span>
        {/* The market's own cap, always in view: the dial's right end differs per market, round length and stake. */}
        <span className="leverage-dial__cap">
          <Rich text={t("dial.cap", { min, max })} />
        </span>
      </div>
    </div>
  );
}
