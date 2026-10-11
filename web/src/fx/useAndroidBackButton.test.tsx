import { act, render, screen } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { registerBackHandler } from "./backHandlers";
import { useAndroidBackButton } from "./useAndroidBackButton";

/**
 * Player review fix item 4 — verified with a mocked `@capacitor/app` plugin rather than a device
 * (no emulator available in this pass): asserts the hook's own decision logic (navigate back /
 * fall back to the Lobby / exit only from the Lobby), independent of the native bridge itself,
 * which `./gradlew assembleDebug` (build-only, not run here) is what actually exercises.
 */
const { addListenerMock, exitAppMock, removeMock, getCapturedListener } = vi.hoisted(() => {
  let captured: ((event: { canGoBack: boolean }) => void) | null = null;
  const removeMock = vi.fn().mockResolvedValue(undefined);
  const addListenerMock = vi.fn((_eventName: string, listener: (event: { canGoBack: boolean }) => void) => {
    captured = listener;
    return Promise.resolve({ remove: removeMock });
  });
  const exitAppMock = vi.fn().mockResolvedValue(undefined);
  return { addListenerMock, exitAppMock, removeMock, getCapturedListener: () => captured };
});

vi.mock("@capacitor/app", () => ({
  App: { addListener: addListenerMock, exitApp: exitAppMock },
}));

vi.mock("@capacitor/core", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@capacitor/core")>();
  return { ...actual, Capacitor: { ...actual.Capacitor, isNativePlatform: () => true } };
});

function LocationDisplay() {
  const location = useLocation();
  return <div data-testid="path">{location.pathname}</div>;
}

function Harness() {
  useAndroidBackButton();
  return <LocationDisplay />;
}

async function flushMicrotasks() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

beforeEach(() => {
  addListenerMock.mockClear();
  exitAppMock.mockClear();
  removeMock.mockClear();
});

describe("useAndroidBackButton (player review fix item 4: hardware Back from /play/:marketId used to exit the app)", () => {
  it("registers a backButton listener on native platforms", async () => {
    render(
      <MemoryRouter initialEntries={["/play/btc"]}>
        <Harness />
      </MemoryRouter>,
    );
    await flushMicrotasks();
    expect(addListenerMock).toHaveBeenCalledWith("backButton", expect.any(Function));
  });

  it("navigates back in history instead of exiting when not on the Lobby", async () => {
    render(
      <MemoryRouter initialEntries={["/", "/play/btc"]} initialIndex={1}>
        <Harness />
      </MemoryRouter>,
    );
    await flushMicrotasks();
    expect(screen.getByTestId("path").textContent).toBe("/play/btc");

    act(() => {
      getCapturedListener()!({ canGoBack: true });
    });

    expect(screen.getByTestId("path").textContent).toBe("/");
    expect(exitAppMock).not.toHaveBeenCalled();
  });

  it("falls back to the Lobby (never exits) when the WebView reports no history at all", async () => {
    render(
      <MemoryRouter initialEntries={["/play/btc"]}>
        <Harness />
      </MemoryRouter>,
    );
    await flushMicrotasks();

    act(() => {
      getCapturedListener()!({ canGoBack: false });
    });

    expect(screen.getByTestId("path").textContent).toBe("/");
    expect(exitAppMock).not.toHaveBeenCalled();
  });

  it("exits the app only from the Lobby", async () => {
    render(
      <MemoryRouter initialEntries={["/"]}>
        <Harness />
      </MemoryRouter>,
    );
    await flushMicrotasks();

    act(() => {
      getCapturedListener()!({ canGoBack: false });
    });

    expect(exitAppMock).toHaveBeenCalledTimes(1);
  });
});

describe("useAndroidBackButton — an open sheet takes Back first (Р-81, the feedback form)", () => {
  it("closes the sheet instead of navigating back", async () => {
    const closeSheet = vi.fn();
    render(
      <MemoryRouter initialEntries={["/", "/play/btc"]} initialIndex={1}>
        <Harness />
      </MemoryRouter>,
    );
    await flushMicrotasks();
    const off = registerBackHandler(closeSheet);

    act(() => {
      getCapturedListener()!({ canGoBack: true });
    });

    expect(closeSheet).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("path").textContent).toBe("/play/btc"); // the screen behind did not move
    expect(exitAppMock).not.toHaveBeenCalled();
    off();
  });

  it("closes the sheet on the Lobby too — where Back would otherwise exit the app", async () => {
    const closeSheet = vi.fn();
    render(
      <MemoryRouter initialEntries={["/"]}>
        <Harness />
      </MemoryRouter>,
    );
    await flushMicrotasks();
    const off = registerBackHandler(closeSheet);

    act(() => {
      getCapturedListener()!({ canGoBack: false });
    });

    expect(closeSheet).toHaveBeenCalledTimes(1);
    expect(exitAppMock).not.toHaveBeenCalled();
    off();
  });

  it("with the sheet closed again, Back is itself again (navigates, exits from the Lobby)", async () => {
    const closeSheet = vi.fn();
    render(
      <MemoryRouter initialEntries={["/", "/play/btc"]} initialIndex={1}>
        <Harness />
      </MemoryRouter>,
    );
    await flushMicrotasks();
    registerBackHandler(closeSheet)();

    act(() => {
      getCapturedListener()!({ canGoBack: true });
    });

    expect(closeSheet).not.toHaveBeenCalled();
    expect(screen.getByTestId("path").textContent).toBe("/");
  });
});
