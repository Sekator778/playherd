import { useEffect, useRef } from "react";

/**
 * Task item 1 ("RealApi… polling every 2 s for rounds/home/serverInfo while visible"): calls
 * `fetcher` immediately whenever `deps` change, then again every `intervalMs` of real wall-clock
 * time for as long as the tab stays visible — paused entirely while
 * `document.visibilityState !== "visible"` (a backgrounded/minimized tab), and refreshed
 * immediately the moment it becomes visible again rather than waiting out the rest of the
 * paused interval. предложение: the hook shape itself isn't specified by the task, only the
 * polling contract it must honor; used the same way for both `MockApi` (cheap, local) and
 * `RealApi` (actual network calls) so screens don't need to know which one is live.
 */
export function usePoll(fetcher: () => void, intervalMs: number, deps: readonly unknown[]): void {
  const fetcherRef = useRef(fetcher);
  // Refs may not be written during render (react-hooks/refs) — keep it current via its own
  // every-render effect instead, same idiom the rest of this codebase uses for "always call the
  // latest closure" without re-subscribing the interval on every render.
  useEffect(() => {
    fetcherRef.current = fetcher;
  });

  useEffect(() => {
    fetcherRef.current();
    let id: number | undefined;
    const start = () => {
      if (id !== undefined) return;
      id = window.setInterval(() => fetcherRef.current(), intervalMs);
    };
    const stop = () => {
      if (id === undefined) return;
      window.clearInterval(id);
      id = undefined;
    };
    const onVisibility = () => {
      if (document.visibilityState === "visible") {
        fetcherRef.current();
        start();
      } else {
        stop();
      }
    };
    if (typeof document === "undefined" || document.visibilityState === "visible") start();
    document.addEventListener?.("visibilitychange", onVisibility);
    return () => {
      stop();
      document.removeEventListener?.("visibilitychange", onVisibility);
    };
    // deps drive when the effect re-subscribes (new fetcher identity via a changed dep, e.g. a
    // different roundId) — fetcherRef.current always calls the latest closure regardless.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, ...deps]);
}
