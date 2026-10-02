import { act, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiWrapper, fakeApi, serverInfoDto } from "../api/apiTestUtils";
import { reportEngineNotReady, reportEngineReady, resetEngineStateForTests } from "../api/engineState";
import type { Api } from "../api/contract";
import { ENGINE_RECHECK_MS, EngineBanner } from "./EngineBanner";

function renderBanner(api: Api) {
  return render(<EngineBanner />, { wrapper: apiWrapper(api) });
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  resetEngineStateForTests();
});
afterEach(() => {
  vi.useRealTimers();
  resetEngineStateForTests();
});

describe("EngineBanner — 'the server is restarting'", () => {
  it("is not there while the engine is ready — and asks the server nothing", async () => {
    const serverInfo = vi.fn(async () => serverInfoDto());
    renderBanner(fakeApi({ serverInfo }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ENGINE_RECHECK_MS * 3);
    });
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    expect(serverInfo).not.toHaveBeenCalled();
  });

  it("says plainly that the server is restarting, that the screen shows the last data, and that it updates by itself", () => {
    renderBanner(fakeApi({ serverInfo: vi.fn(async () => serverInfoDto()) }));
    act(() => reportEngineNotReady());
    const banner = screen.getByRole("status");
    expect(banner).toHaveTextContent("The server is restarting.");
    expect(banner).toHaveTextContent(/last data we had/);
    expect(banner).toHaveTextContent(/updates by itself when the server is back/);
  });

  it("while it shows it asks serverInfo() every few seconds — the answer is read by the transport; once ready it goes away and stops asking", async () => {
    const serverInfo = vi.fn(async () => serverInfoDto({ engineReady: false }));
    renderBanner(fakeApi({ serverInfo }));
    act(() => reportEngineNotReady());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ENGINE_RECHECK_MS * 2 + 100);
    });
    expect(serverInfo).toHaveBeenCalledTimes(2);

    act(() => reportEngineReady(Date.now() + 1)); // what the transport does on `engineReady: true`
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ENGINE_RECHECK_MS * 3);
    });
    expect(serverInfo).toHaveBeenCalledTimes(2);
  });

  it("a failed ask is not an error of the banner", async () => {
    const serverInfo = vi.fn(async () => {
      throw new Error("down");
    });
    renderBanner(fakeApi({ serverInfo }));
    act(() => reportEngineNotReady());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ENGINE_RECHECK_MS + 100);
    });
    expect(serverInfo).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("an Api with no serverInfo() (the mock) shows the banner if told, and never throws trying to ask", async () => {
    renderBanner(fakeApi({}));
    act(() => reportEngineNotReady());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ENGINE_RECHECK_MS * 2);
    });
    expect(screen.getByRole("status")).toBeInTheDocument();
  });
});
