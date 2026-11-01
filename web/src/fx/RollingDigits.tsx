/**
 * "Odometer" number component (`implementation-plan.md` §2) — reused by the result card's
 * headline AND `Layout.tsx`'s `BalancePill` (any balance change rolls, not only while a result
 * moment is on screen — the direct answer to Alex's "so that the amount... shows up right away").
 * Interpolates the numeric value smoothly via `requestAnimationFrame` rather than animating each
 * digit independently on its own delay (result-moment.md §3's "each digit spins
 * independently") — a scoped-down but still clearly "rolling" reading of that spec line, chosen to
 * keep this component small and framework-free (no per-digit DOM diffing).
 *
 * `prefers-reduced-motion: reduce` (real or the `/lab` override, `reducedMotion.ts`) skips the
 * animation entirely and renders the final value immediately (`direction.md` §6: "the number appears
 * immediately with its final value").
 */
import { useEffect, useRef, useState } from "react";
import { usePrefersReducedMotion } from "./reducedMotion";

interface Anim {
  from: number;
  to: number;
}

export function RollingDigits({
  value,
  format,
  durationMs = 600,
  className,
}: {
  value: number;
  format: (n: number) => string;
  durationMs?: number;
  className?: string;
}) {
  const reducedMotion = usePrefersReducedMotion();
  const [lastValue, setLastValue] = useState(value);
  const [display, setDisplay] = useState(value);
  const [anim, setAnim] = useState<Anim>({ from: value, to: value });
  const rafRef = useRef<number | undefined>(undefined);

  // Render-time state adjustment (the same documented React pattern this codebase already uses
  // in `BetSlip.tsx`'s `resetKey` check, via `useState`, never a ref, during render) rather than
  // an effect — so the reduced-motion "jump straight to the final value" path never calls
  // `setState` synchronously from inside an effect body (react-hooks/set-state-in-effect). Only
  // the actual rAF loop below lives in an effect, since that genuinely synchronizes with an
  // external timer; it's the only thing here allowed to touch a ref (react-hooks/refs bans ref
  // reads/writes during render, not inside an effect).
  if (lastValue !== value) {
    setLastValue(value);
    if (reducedMotion) {
      setDisplay(value);
    } else {
      setAnim({ from: display, to: value }); // animate from wherever it's currently sitting
    }
  }

  useEffect(() => {
    if (reducedMotion) return;
    const { from, to } = anim;
    if (from === to) return;
    const start = performance.now();
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / durationMs);
      const eased = 1 - Math.pow(1 - t, 3); // ease-out-cubic
      setDisplay(from + (to - from) * eased);
      rafRef.current = t < 1 ? requestAnimationFrame(tick) : undefined;
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => {
      if (rafRef.current !== undefined) cancelAnimationFrame(rafRef.current);
    };
  }, [anim, reducedMotion, durationMs]);

  return (
    <span className={className} aria-live="off">
      {format(display)}
    </span>
  );
}
