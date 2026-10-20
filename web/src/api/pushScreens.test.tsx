import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiWrapper, fakeApi, marketDto, marketsDto } from "./apiTestUtils";
import { PUSH_FALLBACK_POLL_MS, setPushForTests, type PushClient } from "./push";
import { useMarkets, MARKETS_POLL_MS } from "./useMarkets";
import { usePoll } from "./usePoll";

/** A push client the test drives: which topics are held, whether it is healthy, and a way to send a notice. */
function fakePush() {
  const held = new Map<string, Set<() => void>>();
  const healthListeners = new Set<() => void>();
  let healthy = false;
  const push: PushClient = {
    subscribe(topics, onNotice) {
      topics.forEach((t) => {
        if (!held.has(t)) held.set(t, new Set());
        held.get(t)!.add(onNotice);
      });
      return () =>
        topics.forEach((t) => {
          held.get(t)?.delete(onNotice);
          if (held.get(t)?.size === 0) held.delete(t);
        });
    },
    isHealthy: () => healthy,
    onHealthChange(listener) {
      healthListeners.add(listener);
      return () => {
        healthListeners.delete(listener);
      };
    },
    setIdentity: vi.fn(),
  };
  return {
    push,
    topics: () => [...held.keys()].sort(),
    notice(topic: string) {
      held.get(topic)?.forEach((l) => l());
    },
    setHealthy(next: boolean) {
      healthy = next;
      healthListeners.forEach((l) => l());
    },
  };
}

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  setPushForTests(undefined);
  vi.useRealTimers();
});

describe("usePoll with push topics", () => {
  it("reads again on a notice of its topic, and polls every interval while the socket is down", () => {
    const fake = fakePush();
    setPushForTests(fake.push);
    const fetcher = vi.fn();
    renderHook(() => usePoll(fetcher, 2000, [], ["round:1h-5"]));
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fake.topics()).toEqual(["round:1h-5"]);

    vi.advanceTimersByTime(2000);
    expect(fetcher).toHaveBeenCalledTimes(2); // socket down: the 2 s poll as before

    act(() => fake.notice("round:1h-5"));
    expect(fetcher).toHaveBeenCalledTimes(3);
  });

  it("polls only every 15 s while the socket is healthy, and again every 2 s (at once) when it drops", () => {
    const fake = fakePush();
    setPushForTests(fake.push);
    const fetcher = vi.fn();
    renderHook(() => usePoll(fetcher, 2000, [], ["markets"]));
    act(() => fake.setHealthy(true));
    fetcher.mockClear();

    vi.advanceTimersByTime(14_000);
    expect(fetcher).not.toHaveBeenCalled();
    vi.advanceTimersByTime(1000);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(PUSH_FALLBACK_POLL_MS).toBe(15_000);

    fetcher.mockClear();
    act(() => fake.setHealthy(false));
    expect(fetcher).toHaveBeenCalledTimes(1); // a notice may have been missed: read now
    vi.advanceTimersByTime(2000);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("folds a burst of notices into at most one read per half second", () => {
    const fake = fakePush();
    setPushForTests(fake.push);
    const fetcher = vi.fn();
    renderHook(() => usePoll(fetcher, 2000, [], ["markets"]));
    act(() => fake.setHealthy(true));
    fetcher.mockClear();

    act(() => {
      for (let i = 0; i < 30; i++) fake.notice("markets");
    });
    expect(fetcher).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(500);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("unsubscribes when the screen goes away, and a new topic set subscribes anew", () => {
    const fake = fakePush();
    setPushForTests(fake.push);
    const fetcher = vi.fn();
    const { rerender, unmount } = renderHook(({ id }) => usePoll(fetcher, 2000, [id], [`round:${id}`]), {
      initialProps: { id: "a" },
    });
    expect(fake.topics()).toEqual(["round:a"]);

    rerender({ id: "b" });
    expect(fake.topics()).toEqual(["round:b"]);

    unmount();
    expect(fake.topics()).toEqual([]);
  });

  it("is plain polling without topics, and without a push client (the mock app)", () => {
    const fake = fakePush();
    setPushForTests(fake.push);
    const noTopics = vi.fn();
    renderHook(() => usePoll(noTopics, 2000, []));
    expect(fake.topics()).toEqual([]);
    act(() => fake.setHealthy(true));
    noTopics.mockClear();
    vi.advanceTimersByTime(2000);
    expect(noTopics).toHaveBeenCalledTimes(1); // the healthy socket changes nothing for a screen that did not ask

    setPushForTests(null);
    const noPush = vi.fn();
    renderHook(() => usePoll(noPush, 2000, [], ["markets"]));
    vi.advanceTimersByTime(2000);
    expect(noPush).toHaveBeenCalledTimes(2);
  });
});

describe("a polled store with push topics (useMarkets)", () => {
  function setup() {
    const markets = vi.fn(() => Promise.resolve(marketsDto([marketDto("BTC", { symbol: "BTC" })])));
    const api = fakeApi({ markets });
    return { markets, wrapper: apiWrapper(api) };
  }

  it("listens to the lobby topic, reads on a notice, and slows its poll while the socket is healthy", async () => {
    const fake = fakePush();
    setPushForTests(fake.push);
    const { markets, wrapper } = setup();
    renderHook(() => useMarkets(), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(fake.topics()).toEqual(["markets"]);
    expect(markets).toHaveBeenCalledTimes(1);

    await act(async () => {
      fake.notice("markets");
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(markets).toHaveBeenCalledTimes(2);

    act(() => fake.setHealthy(true));
    markets.mockClear();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MARKETS_POLL_MS * 5);
    });
    expect(markets).not.toHaveBeenCalled(); // 10 s: still inside the 15 s safety poll
    await act(async () => {
      await vi.advanceTimersByTimeAsync(PUSH_FALLBACK_POLL_MS);
    });
    expect(markets).toHaveBeenCalled();
  });

  it("goes back to the 2 s poll when the socket closes, and lets go of the topic with the last reader", async () => {
    const fake = fakePush();
    setPushForTests(fake.push);
    const { markets, wrapper } = setup();
    const { unmount } = renderHook(() => useMarkets(), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    act(() => fake.setHealthy(true));
    await act(async () => {
      fake.setHealthy(false);
      await vi.advanceTimersByTimeAsync(0);
    });
    markets.mockClear();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(MARKETS_POLL_MS);
    });
    expect(markets).toHaveBeenCalledTimes(1);

    unmount();
    expect(fake.topics()).toEqual([]);
  });
});
