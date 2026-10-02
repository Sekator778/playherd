import { act, render, renderHook, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiWrapper, fakeApi } from "./apiTestUtils";
import type { PriceSeriesDto, PriceTickDto } from "./contract";
import { PRICE_SERIES_MAX_TICKS, PRICE_SERIES_POLL_MS, mergeTicks, usePriceSeries } from "./usePriceSeries";

const tick = (atMs: number, price = "100.00"): PriceTickDto => ({ atMs, price });
const ticks = (from: number, to: number, step = 3000) => {
  const out: PriceTickDto[] = [];
  for (let t = from; t <= to; t += step) out.push(tick(t, (100 + t / 1e6).toFixed(2)));
  return out;
};

describe("mergeTicks", () => {
  it("appends newer ticks, oldest first", () => {
    expect(mergeTicks([tick(1), tick(2)], [tick(3), tick(4)])).toEqual([tick(1), tick(2), tick(3), tick(4)]);
    expect(mergeTicks([], [tick(1)])).toEqual([tick(1)]);
  });

  it("drops ticks that are not newer than the newest held — an overlapping poll never duplicates a point", () => {
    expect(mergeTicks([tick(1), tick(2)], [tick(2), tick(3)])).toEqual([tick(1), tick(2), tick(3)]);
    expect(mergeTicks([tick(1), tick(2)], [tick(1), tick(2)])).toEqual([tick(1), tick(2)]);
  });

  it("returns the very same array when nothing is new (so nobody re-renders)", () => {
    const existing = [tick(1), tick(2)];
    expect(mergeTicks(existing, [])).toBe(existing);
    expect(mergeTicks(existing, [tick(2)])).toBe(existing);
  });

  it("keeps only the newest `max`", () => {
    const merged = mergeTicks(ticks(0, 9_000, 1000), ticks(10_000, 14_000, 1000), 8);
    expect(merged).toHaveLength(8);
    expect(merged[0].atMs).toBe(7_000);
    expect(merged[merged.length - 1].atMs).toBe(14_000);
    expect(PRICE_SERIES_MAX_TICKS).toBe(600);
    expect(mergeTicks([], ticks(0, 5_000_000, 3000))).toHaveLength(PRICE_SERIES_MAX_TICKS);
  });

  it("a batch that ends before what we hold means the server's series restarted: it replaces the buffer", () => {
    expect(mergeTicks([tick(100), tick(200)], [tick(10), tick(20)])).toEqual([tick(10), tick(20)]);
  });
});

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

/** A fake server with a fixed tick history; `priceSeries` answers like the real one: the last
 *  15 minutes without `sinceMs`, strictly newer ticks with it. */
function serverWith(history: PriceTickDto[]) {
  const all = [...history];
  const calls: Array<{ marketId: string; sinceMs: number | undefined }> = [];
  const priceSeries = vi.fn(async (marketId: string, sinceMs?: number): Promise<PriceSeriesDto> => {
    calls.push({ marketId, sinceMs });
    const newest = all[all.length - 1]?.atMs ?? 0;
    const from = sinceMs ?? newest - 15 * 60_000;
    return { marketId, priceDecimals: 2, ticks: all.filter((t) => t.atMs > from).slice(-600) };
  });
  return { all, calls, priceSeries, api: fakeApi({ priceSeries }) };
}

describe("usePriceSeries", () => {
  it("loads the initial window, then polls incrementally with sinceMs = the newest tick", async () => {
    const server = serverWith(ticks(0, 60_000));
    const { result } = renderHook(() => usePriceSeries("ETH"), { wrapper: apiWrapper(server.api) });
    expect(result.current).toMatchObject({ loading: true, ticks: [], priceDecimals: null });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.loading).toBe(false);
    expect(result.current.priceDecimals).toBe(2);
    expect(result.current.ticks[0].atMs).toBe(0);
    expect(result.current.ticks[result.current.ticks.length - 1].atMs).toBe(60_000);
    expect(server.calls[0]).toEqual({ marketId: "ETH", sinceMs: undefined });

    server.all.push(...ticks(63_000, 75_000));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(PRICE_SERIES_POLL_MS);
    });
    expect(server.calls[1]).toEqual({ marketId: "ETH", sinceMs: 60_000 });
    expect(result.current.ticks[result.current.ticks.length - 1].atMs).toBe(75_000);
    expect(result.current.ticks).toHaveLength(26); // 0..75_000 every 3 s, no duplicates
  });

  it("bounds the buffer however long it runs", async () => {
    const server = serverWith(ticks(0, 100_000));
    const { result } = renderHook(() => usePriceSeries("ETH"), { wrapper: apiWrapper(server.api) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    for (let round = 0; round < 40; round++) {
      const last = server.all[server.all.length - 1].atMs;
      server.all.push(...ticks(last + 3000, last + 3000 * 60));
      await act(async () => {
        await vi.advanceTimersByTimeAsync(PRICE_SERIES_POLL_MS);
      });
    }
    expect(result.current.ticks).toHaveLength(PRICE_SERIES_MAX_TICKS);
    expect(result.current.ticks[result.current.ticks.length - 1].atMs).toBe(server.all[server.all.length - 1].atMs);
  });

  it("does not re-render when a poll brings nothing new", async () => {
    const server = serverWith(ticks(0, 30_000));
    const { result } = renderHook(() => usePriceSeries("ETH"), { wrapper: apiWrapper(server.api) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    const state = result.current;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(PRICE_SERIES_POLL_MS * 3);
    });
    expect(server.priceSeries).toHaveBeenCalledTimes(4);
    expect(result.current).toBe(state);
  });

  it("components of one room share one poll per market", async () => {
    const server = serverWith(ticks(0, 30_000));
    function Reader({ id }: { id: string }) {
      const { ticks: list } = usePriceSeries("ETH");
      return <p data-testid={id}>{list.length}</p>;
    }
    render(
      <>
        <Reader id="chart" />
        <Reader id="header" />
      </>,
      { wrapper: apiWrapper(server.api) },
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(PRICE_SERIES_POLL_MS);
    });
    expect(server.priceSeries).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId("chart").textContent).toBe(screen.getByTestId("header").textContent);
  });

  it("each market has its own series", async () => {
    const server = serverWith(ticks(0, 30_000));
    const a = renderHook(() => usePriceSeries("ETH"), { wrapper: apiWrapper(server.api) });
    const b = renderHook(() => usePriceSeries("SOL"), { wrapper: apiWrapper(server.api) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(server.calls.map((c) => c.marketId).sort()).toEqual(["ETH", "SOL"]);
    expect(a.result.current.ticks.length).toBeGreaterThan(0);
    expect(b.result.current.ticks.length).toBeGreaterThan(0);
  });

  it("forgets its buffer when nobody watches any more: coming back starts from a fresh load", async () => {
    const server = serverWith(ticks(0, 30_000));
    const wrapper = apiWrapper(server.api);
    const first = renderHook(() => usePriceSeries("ETH"), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    first.unmount();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(server.priceSeries).toHaveBeenCalledTimes(1); // stopped

    const second = renderHook(() => usePriceSeries("ETH"), { wrapper });
    expect(second.result.current.loading).toBe(true);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(server.calls[1]).toEqual({ marketId: "ETH", sinceMs: undefined });
  });

  it("no market: nothing is fetched and the state is empty and not loading", async () => {
    const server = serverWith(ticks(0, 30_000));
    const { result } = renderHook(() => usePriceSeries(null), { wrapper: apiWrapper(server.api) });
    await vi.advanceTimersByTimeAsync(10_000);
    expect(server.priceSeries).not.toHaveBeenCalled();
    expect(result.current).toEqual({ ticks: [], priceDecimals: null, loading: false, error: false });
  });

  it("a failing poll keeps what was loaded and flags the error", async () => {
    const server = serverWith(ticks(0, 30_000));
    const { result } = renderHook(() => usePriceSeries("ETH"), { wrapper: apiWrapper(server.api) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    const loaded = result.current.ticks.length;
    server.priceSeries.mockRejectedValueOnce(new Error("down"));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(PRICE_SERIES_POLL_MS);
    });
    expect(result.current.error).toBe(true);
    expect(result.current.ticks).toHaveLength(loaded);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(PRICE_SERIES_POLL_MS);
    });
    expect(result.current.error).toBe(false);
  });
});

/** Thins `list` evenly to `max` points, both ends kept — the contract's wording for "more than 600 exist". */
function thinEvenly(list: PriceTickDto[], max: number): PriceTickDto[] {
  if (list.length <= max) return list;
  return Array.from({ length: max }, (_, i) => list[Math.round((i * (list.length - 1)) / (max - 1))]);
}

describe("usePriceSeries against the contract's wording (Api.priceSeries)", () => {
  /** A server that answers as the contract says: ticks with `atMs > sinceMs`, at most 600, thinned evenly beyond. */
  function contractServer(history: PriceTickDto[]) {
    const all = [...history];
    const priceSeries = vi.fn(async (marketId: string, sinceMs?: number): Promise<PriceSeriesDto> => {
      const newest = all[all.length - 1]?.atMs ?? 0;
      const from = sinceMs ?? newest - 15 * 60_000;
      return { marketId, priceDecimals: 2, ticks: thinEvenly(all.filter((t) => t.atMs > from), PRICE_SERIES_MAX_TICKS) };
    });
    return { all, priceSeries, api: fakeApi({ priceSeries }) };
  }

  it("asks for what is newer than the newest tick it holds, and the answer never repeats that tick", async () => {
    const server = contractServer(ticks(0, 60_000));
    const { result } = renderHook(() => usePriceSeries("ETH"), { wrapper: apiWrapper(server.api) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    server.all.push(...ticks(63_000, 69_000));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(PRICE_SERIES_POLL_MS);
    });
    expect(server.priceSeries).toHaveBeenLastCalledWith("ETH", 60_000);
    const answered = await server.priceSeries.mock.results[server.priceSeries.mock.results.length - 1].value;
    expect(answered.ticks[0].atMs).toBe(63_000); // strictly after 60_000
    const times = result.current.ticks.map((t) => t.atMs);
    expect(times).toEqual([...new Set(times)].sort((a, b) => a - b)); // strictly increasing: no repeat, no disorder
  });

  it("after a long pause (a hidden tab) the answer is a thinned stretch over the gap — merged in order, ending at the newest tick", async () => {
    const server = contractServer(ticks(0, 30_000));
    const { result } = renderHook(() => usePriceSeries("ETH"), { wrapper: apiWrapper(server.api) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    // 40 minutes pass without a poll: 800 ticks exist since the last one held — more than the 600 a call returns
    const gap = ticks(33_000, 33_000 + 800 * 3000);
    server.all.push(...gap);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(PRICE_SERIES_POLL_MS);
    });
    const answered = await server.priceSeries.mock.results[server.priceSeries.mock.results.length - 1].value;
    expect(answered.ticks).toHaveLength(600); // thinned to the cap…
    expect(answered.ticks[0].atMs).toBe(33_000); // …spanning the whole gap, from its start…
    expect(answered.ticks[answered.ticks.length - 1].atMs).toBe(gap[gap.length - 1].atMs); // …to the newest tick
    const times = result.current.ticks.map((t) => t.atMs);
    expect(times).toEqual([...times].sort((a, b) => a - b));
    expect(new Set(times).size).toBe(times.length);
    expect(times[times.length - 1]).toBe(gap[gap.length - 1].atMs);
    expect(times.length).toBeLessThanOrEqual(PRICE_SERIES_MAX_TICKS);
  });

  it("still takes in a server that is inclusive (returns the tick it was asked after): it is dropped, not repeated", async () => {
    const server = serverWith(ticks(0, 60_000));
    server.priceSeries.mockImplementation(async (marketId: string, sinceMs?: number) => ({
      marketId,
      priceDecimals: 2,
      ticks: server.all.filter((t) => t.atMs >= (sinceMs ?? 0)),
    }));
    const { result } = renderHook(() => usePriceSeries("ETH"), { wrapper: apiWrapper(server.api) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    server.all.push(...ticks(63_000, 66_000));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(PRICE_SERIES_POLL_MS);
    });
    const times = result.current.ticks.map((t) => t.atMs);
    expect(new Set(times).size).toBe(times.length);
    expect(times[times.length - 1]).toBe(66_000);
  });
});
