import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createPolledStore, storeFor } from "./polledStore";
import type { Api } from "./contract";

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "visible" });
});

function counting() {
  const fetch = vi.fn(async (current: number) => current + 1);
  return { fetch, store: createPolledStore<number>({ initial: 0, intervalMs: 1000, fetch }) };
}

describe("createPolledStore", () => {
  it("does nothing until someone subscribes, then polls immediately and every interval", async () => {
    const { fetch, store } = counting();
    await vi.advanceTimersByTimeAsync(5000);
    expect(fetch).not.toHaveBeenCalled();

    const listener = vi.fn();
    store.subscribe(listener);
    await vi.advanceTimersByTimeAsync(0);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(store.getSnapshot()).toBe(1);
    expect(listener).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(3000);
    expect(fetch).toHaveBeenCalledTimes(4);
    expect(store.getSnapshot()).toBe(4);
  });

  it("many subscribers share one poll", async () => {
    const { fetch, store } = counting();
    const a = vi.fn();
    const b = vi.fn();
    const c = vi.fn();
    store.subscribe(a);
    store.subscribe(b);
    store.subscribe(c);
    await vi.advanceTimersByTimeAsync(2000);
    expect(fetch).toHaveBeenCalledTimes(3); // 1 immediate + 2 ticks, not 9
    expect(a).toHaveBeenCalledTimes(3);
    expect(b).toHaveBeenCalledTimes(3);
    expect(c).toHaveBeenCalledTimes(3);
  });

  it("stops when the last subscriber leaves, and starts again for the next one", async () => {
    const { fetch, store } = counting();
    const unsubscribeA = store.subscribe(() => {});
    const unsubscribeB = store.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(1000);
    expect(fetch).toHaveBeenCalledTimes(2);
    unsubscribeA();
    await vi.advanceTimersByTimeAsync(1000);
    expect(fetch).toHaveBeenCalledTimes(3); // B still watching
    unsubscribeB();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(fetch).toHaveBeenCalledTimes(3); // nobody watching: no polling at all

    store.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(0);
    expect(fetch).toHaveBeenCalledTimes(4); // immediate fetch for the newcomer
  });

  it("never runs two fetches at once: a slow response is waited for, not overlapped", async () => {
    let resolveFetch: ((n: number) => void) | undefined;
    const fetch = vi.fn((current: number) => new Promise<number>((resolve) => { resolveFetch = () => resolve(current + 1); }));
    const store = createPolledStore<number>({ initial: 0, intervalMs: 1000, fetch });
    store.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(3500); // three ticks pass while the first fetch is still pending
    expect(fetch).toHaveBeenCalledTimes(1);
    resolveFetch!(0);
    await vi.advanceTimersByTimeAsync(0);
    expect(store.getSnapshot()).toBe(1);
    await vi.advanceTimersByTimeAsync(1000);
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it("a failed fetch keeps the last good snapshot (or what onError says) and the next tick tries again", async () => {
    let fail = false;
    const fetch = vi.fn(async (current: { n: number; error: boolean }) => {
      if (fail) throw new Error("boom");
      return { n: current.n + 1, error: false };
    });
    const store = createPolledStore({
      initial: { n: 0, error: false },
      intervalMs: 1000,
      fetch,
      onError: (current) => ({ ...current, error: true }),
    });
    store.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(0);
    expect(store.getSnapshot()).toEqual({ n: 1, error: false });
    fail = true;
    await vi.advanceTimersByTimeAsync(1000);
    expect(store.getSnapshot()).toEqual({ n: 1, error: true });
    fail = false;
    await vi.advanceTimersByTimeAsync(1000);
    expect(store.getSnapshot()).toEqual({ n: 2, error: false });
  });

  it("without onError a failure leaves the snapshot alone", async () => {
    const fetch = vi.fn(async () => {
      throw new Error("boom");
    });
    const store = createPolledStore<number>({ initial: 7, intervalMs: 1000, fetch });
    const listener = vi.fn();
    store.subscribe(listener);
    await vi.advanceTimersByTimeAsync(2000);
    expect(store.getSnapshot()).toBe(7);
    expect(listener).not.toHaveBeenCalled();
  });

  it("drops a response that lands after the poll was stopped", async () => {
    let resolveFetch: (() => void) | undefined;
    const fetch = vi.fn(() => new Promise<number>((resolve) => { resolveFetch = () => resolve(99); }));
    const store = createPolledStore<number>({ initial: 0, intervalMs: 1000, fetch });
    const unsubscribe = store.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(0);
    unsubscribe();
    resolveFetch!();
    await vi.advanceTimersByTimeAsync(0);
    expect(store.getSnapshot()).toBe(0);
  });

  it("onIdle decides where a poll that was stopped starts from", async () => {
    const fetch = vi.fn(async (current: number) => current + 1);
    const store = createPolledStore<number>({ initial: 0, intervalMs: 1000, fetch, onIdle: () => 0 });
    const unsubscribe = store.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(2000);
    expect(store.getSnapshot()).toBe(3);
    unsubscribe();
    expect(store.getSnapshot()).toBe(0);
  });

  it("pauses while the tab is hidden and refreshes the moment it is visible again", async () => {
    const { fetch, store } = counting();
    store.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(0);
    expect(fetch).toHaveBeenCalledTimes(1);

    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "hidden" });
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(10_000);
    expect(fetch).toHaveBeenCalledTimes(1);

    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "visible" });
    document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(0);
    expect(fetch).toHaveBeenCalledTimes(2);
    await vi.advanceTimersByTimeAsync(1000);
    expect(fetch).toHaveBeenCalledTimes(3);
  });

  it("refresh() fetches now", async () => {
    const { fetch, store } = counting();
    store.subscribe(() => {});
    await vi.advanceTimersByTimeAsync(0);
    store.refresh();
    await vi.advanceTimersByTimeAsync(0);
    expect(fetch).toHaveBeenCalledTimes(2);
  });
});

describe("storeFor", () => {
  it("gives one store per (Api instance, key), made once", () => {
    const apiA = {} as Api;
    const apiB = {} as Api;
    const make = vi.fn(() => createPolledStore<number>({ initial: 0, intervalMs: 1000, fetch: async () => 1 }));
    const a1 = storeFor(apiA, "k", make);
    expect(storeFor(apiA, "k", make)).toBe(a1);
    expect(make).toHaveBeenCalledTimes(1);
    expect(storeFor(apiA, "other", make)).not.toBe(a1);
    expect(storeFor(apiB, "k", make)).not.toBe(a1);
  });
});
