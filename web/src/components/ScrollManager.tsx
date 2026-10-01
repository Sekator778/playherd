import { useEffect, useLayoutEffect, useRef } from "react";
import { useLocation, useNavigationType } from "react-router-dom";

/** The scroll offset each history entry was left at, keyed by React Router's per-entry `location.key`. */
const scrollOffsets = new Map<string, number>();

/**
 * Scroll behaviour of a multi-screen app inside one document. `BrowserRouter` leaves the window where it
 * was, so a player who scrolled down the lobby and tapped a card landed in the room already scrolled
 * down by the same amount — fine while the lobby was three tiles, broken with eighteen. Here:
 * - a new screen (a link or a redirect to another path) starts at the top;
 * - Back / Forward restores the offset that entry was left at (the lobby on the same card it was on);
 * - a change that keeps the path (search typing and chips in the lobby write the URL with `replace`) leaves
 *   the page exactly where it is.
 * The browser's own restoration is switched off so the two never fight.
 */
export function ScrollManager() {
  const location = useLocation();
  const navigationType = useNavigationType();
  const previousPath = useRef<string | null>(null);

  useEffect(() => {
    const previous = window.history.scrollRestoration;
    window.history.scrollRestoration = "manual";
    return () => {
      window.history.scrollRestoration = previous;
    };
  }, []);

  // Remember where this entry is scrolled to, as it scrolls (one write per frame at most).
  useEffect(() => {
    let frame = 0;
    const remember = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => scrollOffsets.set(location.key, window.scrollY));
    };
    window.addEventListener("scroll", remember, { passive: true });
    return () => {
      window.removeEventListener("scroll", remember);
      cancelAnimationFrame(frame);
    };
  }, [location.key]);

  useLayoutEffect(() => {
    const isFirst = previousPath.current === null;
    const samePath = previousPath.current === location.pathname;
    previousPath.current = location.pathname;
    if (isFirst || location.hash) return; // the first screen, or an in-page anchor that scrolls itself
    if (navigationType === "POP") {
      window.scrollTo(0, scrollOffsets.get(location.key) ?? 0);
      return;
    }
    if (navigationType === "REPLACE" && samePath) return; // a filter or a search that rewrites the URL in place
    window.scrollTo(0, 0);
  }, [location.key, location.pathname, location.hash, navigationType]);

  return null;
}
