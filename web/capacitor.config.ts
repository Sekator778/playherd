import type { CapacitorConfig } from '@capacitor/cli';

// Local-stand Android build (Р-48 spike — see docs/product/local_stand.md "Android emulators").
//
// This config points the WebView straight at the running Docker stand (`tools/stand.sh up`,
// http://localhost:8480 on the Mac) instead of bundling `dist/`. From inside an emulator the
// Mac's localhost is reachable at 10.0.2.2, so `server.url` below loads the stand's real pages
// over HTTP. `cleartext: true` is required because that URL is http:// not https://, and it's
// also what makes the WebView treat http://10.0.2.2:8480 as the same origin the stand's
// `/api/rpc` session cookie was set for (Р-45 test-wallet flow works unchanged — no separate
// mobile auth path needed for this stand build).
//
// OPEN ITEM (not built here — store build is later work, needs its own decision round):
// a Google Play / App Store release can't ship server.url pointed at a developer's laptop.
// That build instead bundles `dist/` as local assets (drop `server.url`/`server.cleartext`,
// keep `webDir: 'dist'`) and talks to a real deployed backend over an API base URL, which means
// swapping the cookie-based session for token auth (the cookie is only ever valid for the
// origin that set it — file://.../capacitor:// asset origin can't hold it). Left open per Р-48.
// When that build happens: build with `npm run build:capacitor`, not plain `npm run build` —
// `vite.config.ts`'s `base` is absolute ("/") by default (polish item 4, 2026-09-26), which
// breaks asset loading from a `file://`/`capacitor://` bundle; `CAPACITOR_BUILD=1` switches it
// back to the relative `"./"` that local-asset loading needs.
const config: CapacitorConfig = {
  appId: 'app.herd.dev',
  appName: 'Herd',
  webDir: 'dist',
  server: {
    url: 'http://10.0.2.2:8480',
    cleartext: true,
  },
};

export default config;
