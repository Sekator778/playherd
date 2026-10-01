import type { CapacitorConfig } from '@capacitor/cli';

// Android WebView build (Р-48 — see docs/product/local_stand.md "Android emulators").
//
// This config points the WebView straight at a running server instead of bundling `dist/`.
// `server.url` comes from the HERD_APP_URL environment variable at build time — `make apk
// HERD_APP_URL=https://herd.example.org`; `npx cap sync android` reads this file and writes the
// result into the Android project, so the variable must be set for that step. Unset, it is the
// Docker stand as seen from an emulator: the Mac's localhost is 10.0.2.2 there, so the WebView loads
// the stand's real pages from http://10.0.2.2:8480. That address does not exist for a physical
// phone — an APK for testers needs HERD_APP_URL (the VPS's https URL, or the Mac's LAN address for
// a stand on the same Wi-Fi). Either way the WebView treats the URL as its own origin, which is what
// makes the stand's `/api/rpc` session cookie valid (Р-45 test-wallet flow works unchanged — no
// separate mobile auth path needed for this build).
//
// `cleartext` is true only for an http:// URL: Android blocks plain-http traffic from API 28 on
// unless the app allows it, and an https:// URL needs no exception.
//
// OPEN ITEM (not built here — store build is later work, needs its own decision round):
// a Google Play / App Store release can't ship server.url pointed at a developer's laptop. That
// build instead bundles `dist/` as local assets (drop `server.url`/`server.cleartext`, keep
// `webDir: 'dist'`) and talks to a real deployed backend over an API base URL, which means
// swapping the cookie-based session for token auth (the cookie is only ever valid for the origin
// that set it — file://.../capacitor:// asset origin can't hold it; the client has no API base URL
// yet, `src/api/realApi.ts`). Left open per Р-48; a tester APK only needs HERD_APP_URL
// (docs/reviews/2026-10-01-goals-vs-state.md §3.5).
// When that build happens: build with `npm run build:capacitor`, not plain `npm run build` —
// `vite.config.ts`'s `base` is absolute ("/") by default (polish item 4, 2026-09-26), which
// breaks asset loading from a `file://`/`capacitor://` bundle; `CAPACITOR_BUILD=1` switches it
// back to the relative `"./"` that local-asset loading needs.
const EMULATOR_APP_URL = 'http://10.0.2.2:8480';

// Fails the build early (`cap sync` prints the message) instead of shipping an APK whose WebView
// points nowhere.
function protocolOf(value: string): string {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`HERD_APP_URL is not a valid URL: "${value}"`);
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    throw new Error(`HERD_APP_URL must be an http:// or https:// URL, got "${value}"`);
  }
  return url.protocol;
}

const appUrl = process.env.HERD_APP_URL?.trim() || EMULATOR_APP_URL;

const config: CapacitorConfig = {
  appId: 'app.herd.dev',
  appName: 'Herd',
  webDir: 'dist',
  server: {
    url: appUrl,
    cleartext: protocolOf(appUrl) === 'http:',
  },
};

export default config;
