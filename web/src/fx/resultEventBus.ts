/**
 * Minimal pub/sub (`implementation-plan.md` §2: "без Redux/Zustand — масштаб задачи этого не
 * требует") between `useSettlementWatcher` (publisher, runs regardless of the mounted page) and
 * `ResultMomentHost` (the one subscriber, mounted once in `Layout.tsx`).
 */
import type { ResultEvent } from "./resultEvent";

type Listener = (event: ResultEvent) => void;

const listeners = new Set<Listener>();

export function publish(event: ResultEvent): void {
  listeners.forEach((listener) => listener(event));
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
