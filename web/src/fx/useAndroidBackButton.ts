/**
 * Android hardware Back (player review fix item 4). Without this, Capacitor's Android bridge
 * exits the whole app on any hardware Back press that isn't otherwise handled — confirmed on
 * `/play/:marketId`: Lobby → Pair room is real in-app navigation (React Router's `BrowserRouter`,
 * `main.tsx`), but nothing wired the WebView's back button to it, so the OS default ("nothing
 * handled it, finish the Activity") fired instead of returning to the Lobby.
 *
 * `@capacitor/app`'s `backButton` event is the documented fix (Capacitor docs: "by default...the
 * app will exit" unless a listener is registered) — native Android/iOS only, so this no-ops on the
 * web/PWA build (`Capacitor.isNativePlatform()`), where the browser's own Back already works.
 * Wired once at `Layout.tsx` level (same place as `useSettlementWatcher`/`useAwayReplay`), so it
 * applies to every screen, not just the pair room the bug was filed against.
 *
 * Behavior: pop one entry off history (`navigate(-1)`) when there's somewhere to go; if the WebView
 * reports no history at all (e.g. a cold app launch straight into a route via a saved-state
 * restore), land on the Lobby instead of exiting. The **only** place hardware Back exits the app is
 * the Lobby itself (`/`, the app's one root screen per the Lobby → Pair-room → Stats/Profile IA,
 * `the decision log` Р-59/Р-61) — matching the task's "only exit from the lobby".
 *
 * Before any of that, a sheet that is open takes the press (`backHandlers.ts`, Р-81: the feedback form closes on Back
 * wherever it was opened, even on the Lobby, where Back would otherwise exit the app).
 */
import { useEffect } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Capacitor } from "@capacitor/core";
import type { PluginListenerHandle } from "@capacitor/core";
import { handleBackPress } from "./backHandlers";

const LOBBY_PATH = "/";

/**
 * Kept as its own async function (rather than inlined in the effect) so tests can `vi.mock`
 * `@capacitor/app` without the dynamic-import machinery leaking into every call site — the
 * plugin is imported lazily so the web/PWA build never pulls in its native-bridge glue at all.
 *
 * Returns `{ App }`, not `App` itself — confirmed live on `emulator-5554`/`-5556` (task's own
 * emulators): returning the plugin object directly from an async function makes the JS engine
 * treat it as a thenable (Capacitor's plugin proxy answers `typeof App.then === "function"` for
 * ANY property access, forwarding it to the native bridge), so `return App` here triggered the
 * engine to call `App.then(...)` while settling this function's own promise — surfaced on-device
 * as `Uncaught (in promise) Error: "App.then() is not implemented on android"` on every
 * navigation, and silently broke `exitApp()` specifically: `App.exitApp()` from inside the
 * `.then()` callback ran on a value that had already gone through this same thenable-unwrapping
 * once, and the Lobby's hardware Back press did not exit the app at all (confirmed: the Activity
 * stayed `topResumedActivity` after Back from `/`). `navigate(-1)`/the Lobby fallback still
 * worked either way (they never touch `App` through the broken path), which is why this stayed
 * invisible in the unit tests' fully mocked `@capacitor/app` (`useAndroidBackButton.test.tsx`) —
 * a plain mock object has no `.then` of its own to trip on. Wrapping the plugin in a fresh POJO
 * gives the async function a return value with no `.then` property, so `Promise.resolve` never
 * mistakes it for a thenable.
 */
async function loadAppPlugin() {
  const { App } = await import("@capacitor/app");
  return { App };
}

export function useAndroidBackButton(): void {
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    if (!Capacitor.isNativePlatform()) return;
    let cancelled = false;
    let listenerHandle: PluginListenerHandle | undefined;

    loadAppPlugin().then(({ App }) => {
      if (cancelled) return;
      App.addListener("backButton", ({ canGoBack }) => {
        // An open sheet (the feedback form, `backHandlers.ts`) closes first: Back is the way out of it, not out of the screen behind it.
        if (handleBackPress()) return;
        if (location.pathname === LOBBY_PATH) {
          void App.exitApp();
        } else if (canGoBack) {
          navigate(-1);
        } else {
          navigate(LOBBY_PATH, { replace: true });
        }
      }).then((handle) => {
        if (cancelled) {
          void handle.remove();
        } else {
          listenerHandle = handle;
        }
      });
    });

    return () => {
      cancelled = true;
      void listenerHandle?.remove();
    };
    // Re-subscribes whenever the route changes so the listener's closure always sees the current
    // `location.pathname` (the Lobby check above) and a fresh `navigate` — cheap (native add/remove
    // listener calls only, no UI work) and avoids a stale-closure bug where an old pathname keeps
    // being checked after in-app navigation.
  }, [location.pathname, navigate]);
}
