import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiWrapper, fakeApi } from "./apiTestUtils";
import type { MarketReferenceDto } from "./contract";
import { useReferencePrice } from "./useReferencePrice";

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

describe("useReferencePrice(marketId, intervalMs)", () => {
  it("polls referencePrice for the market at the given interval", async () => {
    const referencePrice = vi.fn(async (marketId: string): Promise<MarketReferenceDto> => ({ price: `${marketId.length}.00`, asOfMs: 1 }));
    const { result } = renderHook(() => useReferencePrice("ETH", 2000), { wrapper: apiWrapper(fakeApi({ referencePrice })) });
    expect(result.current).toBeNull();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current).toEqual({ price: "3.00", asOfMs: 1 });
    expect(referencePrice).toHaveBeenCalledWith("ETH");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(4000);
    });
    expect(referencePrice).toHaveBeenCalledTimes(3);
  });

  it("no market (a round that is still loading): nothing is polled and null comes back", async () => {
    const referencePrice = vi.fn(async (): Promise<MarketReferenceDto> => ({ price: "1.00", asOfMs: 1 }));
    const { result } = renderHook(() => useReferencePrice(undefined, 2000), { wrapper: apiWrapper(fakeApi({ referencePrice })) });
    await vi.advanceTimersByTimeAsync(10_000);
    expect(referencePrice).not.toHaveBeenCalled();
    expect(result.current).toBeNull();
  });

  it("never returns the price of the market the screen has moved away from", async () => {
    const referencePrice = vi.fn(async (marketId: string): Promise<MarketReferenceDto> => ({ price: marketId === "ETH" ? "2696.80" : "117.842", asOfMs: 1 }));
    const wrapper = apiWrapper(fakeApi({ referencePrice }));
    const { result, rerender } = renderHook(({ id }: { id: string }) => useReferencePrice(id, 2000), { wrapper, initialProps: { id: "ETH" } });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current?.price).toBe("2696.80");
    rerender({ id: "SOL" });
    expect(result.current).toBeNull(); // ETH's price must not be shown on the SOL screen
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current?.price).toBe("117.842");
  });

  it("a failed poll leaves the last price in place", async () => {
    const referencePrice = vi.fn<(id: string) => Promise<MarketReferenceDto>>().mockResolvedValueOnce({ price: "5.00", asOfMs: 1 }).mockRejectedValue(new Error("down"));
    const { result } = renderHook(() => useReferencePrice("ETH", 2000), { wrapper: apiWrapper(fakeApi({ referencePrice })) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(result.current?.price).toBe("5.00");
  });
});
