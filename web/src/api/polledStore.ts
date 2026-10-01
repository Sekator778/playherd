/**
 * A tiny shared "poll this endpoint while somebody is looking" store for `useSyncExternalStore`:
 * the first subscriber starts a poll (immediately, then every `intervalMs`, paused while the tab is
 * hidden and refreshed the moment it is visible again — the `usePoll` contract), the last one stops
 * it. Hooks that several components use at once (`useMarkets` on the lobby, in the room header and
 * in the bet slip; `useMyPositions` in the strip and the watcher) go through ONE store per `Api`
 * instance, so N consumers cost one request per tick, not N.
 *
 * The store never throws: a failed fetch becomes `onError`'s snapshot (by default the previous one,
 * so the last good data stays on screen) and the next tick tries again.
 */
import { useSyncExternalStore } from "react";
import type { Api } from "./contract";

export interface PolledStore<T> {
  /** Stable identity, as `useSyncExternalStore` needs. */
  subscribe: (listener: () => void) => () => void;
  getSnapshot: () => T;
  /** Fetch now (a no-op while a fetch is already in flight); what the first subscriber triggers. */
  refresh: () => void;
}

export interface PolledStoreOptions<T> {
  initial: T;
  intervalMs: number;
  /** The next snapshot, from the current one (an incremental poll reads what it needs from it). */
  fetch: (current: T) => Promise<T>;
  /** The snapshot after a failed fetch. Default: keep the current one. */
  onError?: (current: T, error: unknown) => T;
  /** The snapshot to start from when the first subscriber comes back after the last one left
   *  (a poll that was stopped has a gap; a store may prefer to start over). Default: keep it. */
  onIdle?: (current: T) => T;
}

export function createPolledStore<T>(options: PolledStoreOptions<T>): PolledStore<T> {
  let snapshot = options.initial;
  const listeners = new Set<() => void>();
  let timer: number | undefined;
  /** Bumped when the poll stops, so a response that lands after that is dropped. */
  let generation = 0;
  let inFlightGeneration = -1;
  let visibilityHandler: (() => void) | undefined;

  const publish = (next: T) => {
    if (next === snapshot) return;
    snapshot = next;
    listeners.forEach((listener) => listener());
  };

  const refresh = () => {
    if (inFlightGeneration === generation) return;
    const gen = generation;
    inFlightGeneration = gen;
    const settle = (apply: () => void) => {
      if (inFlightGeneration === gen) inFlightGeneration = -1;
      if (gen === generation) apply();
    };
    options.fetch(snapshot).then(
      (next) => settle(() => publish(next)),
      (error: unknown) => settle(() => publish(options.onError ? options.onError(snapshot, error) : snapshot)),
    );
  };

  const startTimer = () => {
    if (timer !== undefined) return;
    timer = window.setInterval(refresh, options.intervalMs);
  };
  const stopTimer = () => {
    if (timer === undefined) return;
    window.clearInterval(timer);
    timer = undefined;
  };

  const start = () => {
    refresh();
    if (typeof document === "undefined" || document.visibilityState === "visible") startTimer();
    visibilityHandler = () => {
      if (document.visibilityState === "visible") {
        refresh();
        startTimer();
      } else {
        stopTimer();
      }
    };
    document.addEventListener?.("visibilitychange", visibilityHandler);
  };

  const stop = () => {
    stopTimer();
    if (visibilityHandler) document.removeEventListener?.("visibilitychange", visibilityHandler);
    visibilityHandler = undefined;
    generation += 1;
    if (options.onIdle) snapshot = options.onIdle(snapshot);
  };

  return {
    subscribe: (listener) => {
      listeners.add(listener);
      if (listeners.size === 1) start();
      return () => {
        listeners.delete(listener);
        if (listeners.size === 0) stop();
      };
    },
    getSnapshot: () => snapshot,
    refresh,
  };
}

/** The store's snapshot, re-rendering the caller on every change and keeping the poll alive while mounted. */
export function usePolledStore<T>(store: PolledStore<T>): T {
  return useSyncExternalStore(store.subscribe, store.getSnapshot);
}

const registries = new WeakMap<Api, Map<string, PolledStore<unknown>>>();

/** The one store of `key` for this `Api` instance, made on first use. A test that provides its own
 *  fake `Api` (a new object) gets its own stores — nothing leaks between tests. */
export function storeFor<T>(api: Api, key: string, make: () => PolledStore<T>): PolledStore<T> {
  let stores = registries.get(api);
  if (!stores) {
    stores = new Map();
    registries.set(api, stores);
  }
  let store = stores.get(key);
  if (!store) {
    store = make() as PolledStore<unknown>;
    stores.set(key, store);
  }
  return store as PolledStore<T>;
}
