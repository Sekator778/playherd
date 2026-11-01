import { useEffect, useRef } from "react";
import { PUSH_FALLBACK_POLL_MS, getPush, throttled } from "./push";

/**
 * Task item 1 ("RealApi… polling every 2 s for rounds/home/serverInfo while visible"): calls
 * `fetcher` immediately whenever `deps` change, then again every `intervalMs` of real wall-clock
 * time for as long as the tab stays visible — paused entirely while
 * `document.visibilityState !== "visible"` (a backgrounded/minimized tab), and refreshed
 * immediately the moment it becomes visible again rather than waiting out the rest of the
 * paused interval. proposal: the hook shape itself isn't specified by the task, only the
 * polling contract it must honor; used the same way for both `MockApi` (cheap, local) and
 * `RealApi` (actual network calls) so screens don't need to know which one is live.
 *
 * `topics` (push.ts): the screen also listens to those topics of the push socket and calls `fetcher` when one changes. While the
 * socket is healthy the timer slows to `PUSH_FALLBACK_POLL_MS` (a safety net); with the socket down, unsupported or not asked for
 * (no `topics`, or the mock app) the screen polls every `intervalMs` exactly as before.
 */
export function usePoll(fetcher: () => void, intervalMs: number, deps: readonly unknown[], topics?: readonly string[]): void {
  const fetcherRef = useRef(fetcher);
  // Refs may not be written during render (react-hooks/refs) — keep it current via its own
  // every-render effect instead, same idiom the rest of this codebase uses for "always call the
  // latest closure" without re-subscribing the interval on every render.
  useEffect(() => {
    fetcherRef.current = fetcher;
  });
  const topicKey = topics && topics.length > 0 ? topics.join("|") : "";

  useEffect(() => {
    fetcherRef.current();
    const push = topicKey ? getPush() : null;
    let id: number | undefined;
    const currentMs = () => (push && push.isHealthy() ? PUSH_FALLBACK_POLL_MS : intervalMs);
    const start = () => {
      if (id !== undefined) return;
      id = window.setInterval(() => fetcherRef.current(), currentMs());
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
    const notice = push ? throttled(() => fetcherRef.current()) : null;
    const unsubscribePush = push && notice ? push.subscribe(topicKey.split("|"), notice.call) : undefined;
    const unsubscribeHealth = push?.onHealthChange(() => {
      // The poll speed follows the socket's health; a socket that just went down may have missed notices: read now.
      const wasRunning = id !== undefined;
      stop();
      if (!push.isHealthy()) fetcherRef.current();
      if (wasRunning) start();
    });
    if (typeof document === "undefined" || document.visibilityState === "visible") start();
    document.addEventListener?.("visibilitychange", onVisibility);
    return () => {
      stop();
      unsubscribePush?.();
      unsubscribeHealth?.();
      notice?.cancel();
      document.removeEventListener?.("visibilitychange", onVisibility);
    };
    // deps drive when the effect re-subscribes (new fetcher identity via a changed dep, e.g. a
    // different roundId) — fetcherRef.current always calls the latest closure regardless.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [intervalMs, topicKey, ...deps]);
}
