/**
 * `prefers-reduced-motion` — read once here, everywhere: the real OS setting, OR the `/lab`
 * "force reduced motion" checkbox (`implementation-plan.md` §5: "overrides CSS via an attribute
 * on <html>"). This module is that override's one implementation — `fx.css` keys every
 * reduced-motion rule off `html[data-reduced-motion="true"]` (kept in sync by
 * `usePrefersReducedMotion` below) instead of a raw `@media` query, so the lab checkbox has real
 * effect on CSS-only animations too, not just the JS-driven ones (particles, `RollingDigits`).
 */
import { useEffect, useState } from "react";

const FORCE_ATTR = "data-force-reduced-motion";
const STATE_ATTR = "data-reduced-motion";

function osPrefersReducedMotion(): boolean {
  try {
    return window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  } catch {
    return false;
  }
}

function forcedOverride(): boolean | null {
  if (typeof document === "undefined") return null;
  const v = document.documentElement.getAttribute(FORCE_ATTR);
  if (v === "true") return true;
  if (v === "false") return false;
  return null;
}

function computeReducedMotion(): boolean {
  const forced = forcedOverride();
  return forced !== null ? forced : osPrefersReducedMotion();
}

export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(computeReducedMotion);

  useEffect(() => {
    const update = () => setReduced(computeReducedMotion());
    let mql: MediaQueryList | undefined;
    try {
      mql = window.matchMedia("(prefers-reduced-motion: reduce)");
      mql.addEventListener?.("change", update);
    } catch {
      // jsdom / a browser without matchMedia support — falls back to the forced override only.
    }
    let observer: MutationObserver | undefined;
    if (typeof MutationObserver !== "undefined" && typeof document !== "undefined") {
      observer = new MutationObserver(update);
      observer.observe(document.documentElement, { attributes: true, attributeFilter: [FORCE_ATTR] });
    }
    return () => {
      mql?.removeEventListener?.("change", update);
      observer?.disconnect();
    };
  }, []);

  useEffect(() => {
    document.documentElement?.setAttribute(STATE_ATTR, String(reduced));
  }, [reduced]);

  return reduced;
}

/** `/lab`'s "force reduced motion" checkbox — `null` clears the override, falling back to the
 *  real OS setting again. Every other mounted `usePrefersReducedMotion()` picks this up via the
 *  `MutationObserver` above. */
export function setForcedReducedMotion(value: boolean | null): void {
  if (typeof document === "undefined") return;
  if (value === null) document.documentElement.removeAttribute(FORCE_ATTR);
  else document.documentElement.setAttribute(FORCE_ATTR, String(value));
}
