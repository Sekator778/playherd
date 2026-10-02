import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { reportEngineNotReady, reportEngineReady, resetEngineStateForTests, useEngineEpoch, useEngineNotReady } from "./engineState";

beforeEach(() => resetEngineStateForTests());

describe("engineState — one shared fact: is the server's engine ready?", () => {
  it("starts ready, with no recovery behind it", () => {
    expect(renderHook(() => useEngineNotReady()).result.current).toBe(false);
    expect(renderHook(() => useEngineEpoch()).result.current).toBe(0);
  });

  it("not ready → ready is one recovery; a screen that lists the epoch among its dependencies loads again exactly then", () => {
    const { result } = renderHook(() => ({ notReady: useEngineNotReady(), epoch: useEngineEpoch() }));
    act(() => reportEngineNotReady());
    expect(result.current).toEqual({ notReady: true, epoch: 0 });
    act(() => reportEngineNotReady()); // asked again, still down: nothing changes, no spurious epoch
    expect(result.current).toEqual({ notReady: true, epoch: 0 });
    act(() => reportEngineReady(Date.now() + 1));
    expect(result.current).toEqual({ notReady: false, epoch: 1 });
    act(() => reportEngineReady(Date.now() + 1)); // ready again, already ready: not a recovery
    expect(result.current.epoch).toBe(1);
  });

  it("an answer to a request sent BEFORE the latest 'not ready' is ignored", () => {
    const { result } = renderHook(() => useEngineNotReady());
    const sentAt = Date.now() - 10;
    act(() => reportEngineNotReady());
    act(() => reportEngineReady(sentAt));
    expect(result.current).toBe(true);
    act(() => reportEngineReady(Date.now() + 1));
    expect(result.current).toBe(false);
  });

  it("every subscriber sees it, and a subscriber that left stops hearing", () => {
    const a = renderHook(() => useEngineNotReady());
    const b = renderHook(() => useEngineNotReady());
    b.unmount();
    act(() => reportEngineNotReady());
    expect(a.result.current).toBe(true);
  });
});
