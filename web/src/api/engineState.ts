/**
 * Whether the server's game engine is ready — one shared fact for the whole app, fed by the transport
 * (`realApi.ts`), read by the banner (`components/EngineBanner.tsx`) and by anything that has to try again once
 * the server is back.
 *
 * While the server rebuilds its state after a failure, every call that reads or changes the engine answers HTTP 503
 * `ENGINE_NOT_READY` and has changed nothing (`contract.ts`, `ApiErrorCode`); what comes from the database (the
 * cancellations log, a settled round, a reference price) is answered as usual — so a success proves the engine is
 * back only for a call that needs it. `realApi.ts` therefore reports "ready" only for those reads and for
 * `serverInfo()` saying `engineReady !== false`.
 *
 * `epoch` counts recoveries (not-ready → ready). A screen that loaded once and failed with `ENGINE_NOT_READY` lists
 * it among its effect dependencies and loads again by itself — no screen keeps its own retry timer.
 */
import { useSyncExternalStore } from "react";

export interface EngineSnapshot {
  /** The last engine-backed call answered `ENGINE_NOT_READY` (or `serverInfo()` said `engineReady: false`). */
  notReady: boolean;
  /** How many times the engine has come back since the page loaded. */
  epoch: number;
}

const INITIAL: EngineSnapshot = { notReady: false, epoch: 0 };

let snapshot: EngineSnapshot = INITIAL;
/** When the latest "not ready" was seen: an answer to a request SENT before it says nothing about now. */
let lastNotReadyAtMs = 0;
const listeners = new Set<() => void>();

function publish(next: EngineSnapshot): void {
  snapshot = next;
  listeners.forEach((listener) => listener());
}

/** A call answered `ENGINE_NOT_READY`, or `serverInfo()` said the engine is not ready. */
export function reportEngineNotReady(): void {
  lastNotReadyAtMs = Date.now();
  if (!snapshot.notReady) publish({ ...snapshot, notReady: true });
}

/**
 * An engine-backed read succeeded. `requestStartedAtMs` is when that request was SENT: an answer that was already on
 * its way when the engine went down must not clear the state (the next poll would set it again — a flicker).
 */
export function reportEngineReady(requestStartedAtMs: number = Date.now()): void {
  if (!snapshot.notReady || requestStartedAtMs < lastNotReadyAtMs) return;
  publish({ notReady: false, epoch: snapshot.epoch + 1 });
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

const getSnapshot = (): EngineSnapshot => snapshot;

export function useEngineState(): EngineSnapshot {
  return useSyncExternalStore(subscribe, getSnapshot);
}

/** The engine is rebuilding — what the banner shows. */
export function useEngineNotReady(): boolean {
  return useEngineState().notReady;
}

/** Changes every time the engine comes back: put it in the dependencies of an effect that should load again then. */
export function useEngineEpoch(): number {
  return useEngineState().epoch;
}

/** Back to a fresh page load — for a test; the app never needs it. */
export function resetEngineStateForTests(): void {
  lastNotReadyAtMs = 0;
  publish(INITIAL);
}
