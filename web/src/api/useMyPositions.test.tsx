import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TEST_PLAYER, apiWrapper, fakeApi } from "./apiTestUtils";
import type { MyPositionDto } from "./contract";
import { MY_POSITIONS_POLL_MS, useMyPositions } from "./useMyPositions";

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

const position = (marketId: string, overrides: Partial<MyPositionDto> = {}): MyPositionDto => ({
  marketId,
  symbol: marketId,
  roundId: `${marketId}-1h-3600000`,
  interval: "HOURLY",
  phase: "BETTING",
  direction: "UP",
  leverage: 5,
  stake: "100.000",
  entryPrice: null,
  liquidationPrice: null,
  liquidated: false,
  untilMs: 3_600_000,
  ...overrides,
});

describe("useMyPositions", () => {
  it("signed out: nothing is fetched, the list is empty and not loading", async () => {
    const myPositions = vi.fn(async () => [position("ETH")]);
    const { result } = renderHook(() => useMyPositions(), { wrapper: apiWrapper(fakeApi({ myPositions }), null) });
    await vi.advanceTimersByTimeAsync(10_000);
    expect(myPositions).not.toHaveBeenCalled();
    expect(result.current).toMatchObject({ positions: [], loading: false, error: false });
  });

  it("signed in: loads, then polls every 2 s", async () => {
    const myPositions = vi.fn(async () => [position("ETH"), position("SOL", { phase: "RUNNING", entryPrice: "117.840", liquidationPrice: "112.000" })]);
    const { result } = renderHook(() => useMyPositions(), { wrapper: apiWrapper(fakeApi({ myPositions }), TEST_PLAYER) });
    expect(result.current.loading).toBe(true);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.loading).toBe(false);
    expect(result.current.positions.map((p) => p.marketId)).toEqual(["ETH", "SOL"]);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MY_POSITIONS_POLL_MS);
    });
    expect(myPositions).toHaveBeenCalledTimes(2);
  });

  it("keeps the same list object while the server says the same thing", async () => {
    const myPositions = vi.fn(async () => [position("ETH")]);
    const { result } = renderHook(() => useMyPositions(), { wrapper: apiWrapper(fakeApi({ myPositions }), TEST_PLAYER) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    const first = result.current;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MY_POSITIONS_POLL_MS * 3);
    });
    expect(myPositions).toHaveBeenCalledTimes(4);
    expect(result.current).toBe(first);
  });

  it("a change (the round started and the position got its prices) is picked up", async () => {
    let rows = [position("ETH")];
    const myPositions = vi.fn(async () => rows);
    const { result } = renderHook(() => useMyPositions(), { wrapper: apiWrapper(fakeApi({ myPositions }), TEST_PLAYER) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    rows = [position("ETH", { phase: "RUNNING", entryPrice: "2694.40", liquidationPrice: "2474.45", untilMs: 7_200_000 })];
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MY_POSITIONS_POLL_MS);
    });
    expect(result.current.positions[0]).toMatchObject({ phase: "RUNNING", entryPrice: "2694.40" });
  });

  it("a failed poll keeps the last list and flags the error", async () => {
    const myPositions = vi.fn<() => Promise<MyPositionDto[]>>().mockResolvedValueOnce([position("ETH")]).mockRejectedValueOnce(new Error("down")).mockResolvedValue([position("ETH")]);
    const { result } = renderHook(() => useMyPositions(), { wrapper: apiWrapper(fakeApi({ myPositions }), TEST_PLAYER) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MY_POSITIONS_POLL_MS);
    });
    expect(result.current.error).toBe(true);
    expect(result.current.positions).toHaveLength(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MY_POSITIONS_POLL_MS);
    });
    expect(result.current.error).toBe(false);
  });

  it("refresh() fetches now, e.g. right after a bet", async () => {
    const myPositions = vi.fn(async () => [] as MyPositionDto[]);
    const { result } = renderHook(() => useMyPositions(), { wrapper: apiWrapper(fakeApi({ myPositions }), TEST_PLAYER) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    act(() => result.current.refresh());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(myPositions).toHaveBeenCalledTimes(2);
  });

  it("stops polling on unmount", async () => {
    const myPositions = vi.fn(async () => [] as MyPositionDto[]);
    const { unmount } = renderHook(() => useMyPositions(), { wrapper: apiWrapper(fakeApi({ myPositions }), TEST_PLAYER) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    unmount();
    await vi.advanceTimersByTimeAsync(10_000);
    expect(myPositions).toHaveBeenCalledTimes(1);
  });
});
