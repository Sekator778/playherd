import { act, render, renderHook, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiWrapper, fakeApi, marketDto, marketsDto } from "./apiTestUtils";
import { ApiRequestError, type MarketsDto } from "./contract";
import { clientNowMs } from "./useClock";
import { MARKETS_POLL_MS, useMarket, useMarkets } from "./useMarkets";

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

const MARKETS = [marketDto("BTC", { symbol: "BTC" }), marketDto("ETH"), marketDto("xyz_NVDA", { symbol: "NVDA" })];

function setup(response: () => Promise<MarketsDto> = () => Promise.resolve(marketsDto(MARKETS))) {
  const markets = vi.fn(response);
  const api = fakeApi({ markets });
  return { markets, api, wrapper: apiWrapper(api) };
}

describe("useMarkets", () => {
  it("starts loading, then exposes the categories, the markets in order and a byId map", async () => {
    const { wrapper } = setup();
    const { result } = renderHook(() => useMarkets(), { wrapper });
    expect(result.current.loading).toBe(true);
    expect(result.current.markets).toEqual([]);
    expect(result.current.categories).toEqual([]);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.loading).toBe(false);
    expect(result.current.error).toBe(false);
    expect(result.current.markets.map((m) => m.id)).toEqual(["BTC", "ETH", "xyz_NVDA"]);
    expect(result.current.categories).toEqual([{ id: "crypto", title: "Crypto", marketCount: 3 }]);
    expect(result.current.byId.get("xyz_NVDA")?.symbol).toBe("NVDA");
    expect(result.current.serverTimeMs).toBe(1_000);
  });

  it("polls every 2 s while mounted", async () => {
    const { markets, wrapper } = setup();
    renderHook(() => useMarkets(), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(markets).toHaveBeenCalledTimes(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MARKETS_POLL_MS);
    });
    expect(markets).toHaveBeenCalledTimes(2);
    expect(MARKETS_POLL_MS).toBe(2000);
  });

  it("any number of components share one poll", async () => {
    const { markets, wrapper } = setup();
    function Reader({ label }: { label: string }) {
      const { markets: list } = useMarkets();
      const { market } = useMarket("ETH");
      return (
        <p data-testid={label}>
          {list.length}:{market?.id ?? "-"}
        </p>
      );
    }
    render(
      <>
        <Reader label="a" />
        <Reader label="b" />
        <Reader label="c" />
      </>,
      { wrapper },
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MARKETS_POLL_MS);
    });
    expect(markets).toHaveBeenCalledTimes(2); // 1 immediate + 1 tick, not 6
    expect(screen.getByTestId("a").textContent).toBe("3:ETH");
    expect(screen.getByTestId("c").textContent).toBe("3:ETH");
  });

  it("stops polling when the last reader unmounts", async () => {
    const { markets, wrapper } = setup();
    const { unmount } = renderHook(() => useMarkets(), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    unmount();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(markets).toHaveBeenCalledTimes(1);
  });

  it("keeps the last good list when a poll fails, flags the error, and recovers", async () => {
    let fail = false;
    const { wrapper } = setup(() => (fail ? Promise.reject(new ApiRequestError("OTHER", "boom")) : Promise.resolve(marketsDto(MARKETS))));
    const { result } = renderHook(() => useMarkets(), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    fail = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MARKETS_POLL_MS);
    });
    expect(result.current.error).toBe(true);
    expect(result.current.loading).toBe(false);
    expect(result.current.markets).toHaveLength(3);
    fail = false;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MARKETS_POLL_MS);
    });
    expect(result.current.error).toBe(false);
  });

  it("a first poll that fails leaves the list loading, with error set", async () => {
    const { wrapper } = setup(() => Promise.reject(new Error("down")));
    const { result } = renderHook(() => useMarkets(), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.loading).toBe(true);
    expect(result.current.error).toBe(true);
  });

  it("serverOffsetMs is the server's clock minus the client's at receipt", async () => {
    const { wrapper } = setup(() => Promise.resolve(marketsDto(MARKETS, clientNowMs() + 5_000)));
    const { result } = renderHook(() => useMarkets(), { wrapper });
    expect(result.current.serverOffsetMs).toBe(0); // nothing received yet
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.serverOffsetMs).toBeCloseTo(5_000, -2);
    // the server's "now" is the client's plus the offset
    expect(clientNowMs() + result.current.serverOffsetMs).toBeCloseTo(result.current.serverTimeMs!, -3);
  });

  it("refresh() fetches right away", async () => {
    const { markets, wrapper } = setup();
    const { result } = renderHook(() => useMarkets(), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    act(() => result.current.refresh());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(markets).toHaveBeenCalledTimes(2);
  });

  it("a state object only changes identity when the data does", async () => {
    const { wrapper } = setup();
    const { result, rerender } = renderHook(() => useMarkets(), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    const before = result.current;
    rerender();
    expect(result.current).toBe(before);
  });
});

describe("useMarket", () => {
  it("is loading, then the market, or notFound once the list is in and lacks it", async () => {
    const { wrapper } = setup();
    const eth = renderHook(() => useMarket("ETH"), { wrapper });
    const nope = renderHook(() => useMarket("NOPE"), { wrapper });
    expect(eth.result.current).toMatchObject({ market: undefined, loading: true, notFound: false, error: false });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(eth.result.current.market?.id).toBe("ETH");
    expect(eth.result.current.loading).toBe(false);
    expect(eth.result.current.notFound).toBe(false);
    expect(nope.result.current).toMatchObject({ market: undefined, loading: false, notFound: true, error: false });
  });

  it("matches the id exactly first, then ignoring case, so an old /play/btc link lands on BTC", async () => {
    const { wrapper } = setup();
    const lower = renderHook(() => useMarket("btc"), { wrapper });
    const exact = renderHook(() => useMarket("xyz_NVDA"), { wrapper });
    const mixed = renderHook(() => useMarket("XYZ_nvda"), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(lower.result.current.market?.id).toBe("BTC");
    expect(exact.result.current.market?.symbol).toBe("NVDA");
    expect(mixed.result.current.market?.id).toBe("xyz_NVDA");
  });

  it("while the list cannot be fetched it is still loading, with error set and a refresh to retry with", async () => {
    let fail = true;
    const { markets, wrapper } = setup(() => (fail ? Promise.reject(new Error("down")) : Promise.resolve(marketsDto(MARKETS))));
    const { result } = renderHook(() => useMarket("ETH"), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current).toMatchObject({ market: undefined, loading: true, notFound: false, error: true });
    fail = false;
    act(() => result.current.refresh());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(markets).toHaveBeenCalledTimes(2);
    expect(result.current).toMatchObject({ loading: false, error: false });
    expect(result.current.market?.id).toBe("ETH");
  });

  it("no id (a route without the param) is notFound once loaded", async () => {
    const { wrapper } = setup();
    const { result } = renderHook(() => useMarket(undefined), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.notFound).toBe(true);
    expect(result.current.market).toBeUndefined();
  });

  it("a closed market that left the list is notFound again", async () => {
    let list = MARKETS;
    const { wrapper } = setup(() => Promise.resolve(marketsDto(list)));
    const { result } = renderHook(() => useMarket("ETH"), { wrapper });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.market?.id).toBe("ETH");
    list = MARKETS.filter((m) => m.id !== "ETH");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MARKETS_POLL_MS);
    });
    expect(result.current.notFound).toBe(true);
  });
});
