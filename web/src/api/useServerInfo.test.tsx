import { act, render, renderHook, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiWrapper, fakeApi, serverInfoDto } from "./apiTestUtils";
import type { ServerInfoDto } from "./contract";
import { SERVER_INFO_POLL_MS, useServerInfo } from "./useServerInfo";

beforeEach(() => {
  vi.useFakeTimers();
});
afterEach(() => {
  vi.useRealTimers();
});

describe("useServerInfo", () => {
  it("an Api with no serverInfo (the mock build) is 'unsupported' — a state of its own, not an error — and nothing is polled", async () => {
    const { result } = renderHook(() => useServerInfo(), { wrapper: apiWrapper(fakeApi({})) });
    await vi.advanceTimersByTimeAsync(60_000);
    expect(result.current).toMatchObject({ status: "unsupported", info: null, refreshFailed: false });
  });

  it("loading, then ready with the server's own description", async () => {
    const serverInfo = vi.fn(async () => serverInfoDto({ bots: 7 }));
    const { result } = renderHook(() => useServerInfo(), { wrapper: apiWrapper(fakeApi({ serverInfo })) });
    expect(result.current.status).toBe("loading");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.status).toBe("ready");
    expect(result.current.info?.bots).toBe(7);
    expect(result.current.refreshFailed).toBe(false);
  });

  it("polls every 15 s", async () => {
    const serverInfo = vi.fn(async () => serverInfoDto());
    renderHook(() => useServerInfo(), { wrapper: apiWrapper(fakeApi({ serverInfo })) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(serverInfo).toHaveBeenCalledTimes(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SERVER_INFO_POLL_MS);
    });
    expect(serverInfo).toHaveBeenCalledTimes(2);
    expect(SERVER_INFO_POLL_MS).toBe(15_000);
  });

  it("a server that cannot be reached is an error until it answers; refresh() is the retry", async () => {
    const serverInfo = vi.fn<() => Promise<ServerInfoDto>>().mockRejectedValueOnce(new Error("down")).mockResolvedValue(serverInfoDto());
    const { result } = renderHook(() => useServerInfo(), { wrapper: apiWrapper(fakeApi({ serverInfo })) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current).toMatchObject({ status: "error", info: null });
    act(() => result.current.refresh());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(result.current.status).toBe("ready");
  });

  it("a later failure keeps the last good answer, flagged as possibly old", async () => {
    const serverInfo = vi.fn<() => Promise<ServerInfoDto>>().mockResolvedValueOnce(serverInfoDto({ bots: 5 })).mockRejectedValueOnce(new Error("blip")).mockResolvedValue(serverInfoDto({ bots: 6 }));
    const { result } = renderHook(() => useServerInfo(), { wrapper: apiWrapper(fakeApi({ serverInfo })) });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SERVER_INFO_POLL_MS);
    });
    expect(result.current).toMatchObject({ status: "ready", refreshFailed: true });
    expect(result.current.info?.bots).toBe(5);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(SERVER_INFO_POLL_MS);
    });
    expect(result.current).toMatchObject({ status: "ready", refreshFailed: false });
    expect(result.current.info?.bots).toBe(6);
  });

  it("every reader on the page shares one poll", async () => {
    const serverInfo = vi.fn(async () => serverInfoDto());
    function Reader({ id }: { id: string }) {
      const { info } = useServerInfo();
      return <p data-testid={id}>{info?.bots ?? "-"}</p>;
    }
    render(
      <>
        <Reader id="a" />
        <Reader id="b" />
        <Reader id="c" />
      </>,
      { wrapper: apiWrapper(fakeApi({ serverInfo })) },
    );
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    expect(serverInfo).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("a").textContent).toBe("20");
    expect(screen.getByTestId("c").textContent).toBe("20");
  });
});
