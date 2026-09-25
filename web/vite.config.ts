import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import wasm from "vite-plugin-wasm";

// https://vite.dev/config/
export default defineConfig({
  // Absolute base path for every real deploy of this app — the Docker/nginx build (task item 5)
  // and plain `npm run dev`/`npm run build`. Required so a client-side route entered directly
  // (a hard refresh or a shared link, e.g. `/round/<id>`) still resolves its script/CSS/manifest
  // URLs against the site root: `nginx.conf`'s SPA fallback serves that SAME `index.html` at
  // every path, so a *relative* `"./assets/…"` there resolved differently depending on which path
  // served it — confirmed live: a blank white screen loading `/round/<id>` directly (404s on
  // `/round/assets/…`), fixed by making every reference root-relative instead (polish item 4,
  // 2026-09-26).
  //
  // The one build that still needs the OLD relative base is a future Capacitor build that bundles
  // `dist/` as local `file://`/`capacitor://` assets instead of pointing `server.url` at a live
  // stand (see `capacitor.config.ts`'s "OPEN ITEM" — not built yet). That build sets
  // `CAPACITOR_BUILD=1` (`npm run build:capacitor`) to switch back to `"./"`; today's Capacitor
  // build (`server.url` pointed at the Docker stand, same as a browser) is unaffected either way
  // since it never loads the bundled `dist/` at runtime in the first place.
  base: process.env.CAPACITOR_BUILD ? "./" : "/",
  plugins: [
    react(),
    // `chia-wallet-sdk-wasm` (task item 2) ships wasm-bindgen's "bundler" target output — a
    // `.wasm` file imported directly as an ES module — which Vite doesn't understand natively;
    // this plugin teaches it to (async instantiation + the top-level `await` the generated glue
    // code needs). `vite-plugin-top-level-await` (the usual second half of this pair on Vite
    // <8) was tried too, but its `generateBundle` hook assumes Rollup's/esbuild's output shape
    // and hard-fails against Vite 8's Rolldown-based bundler ("missing field `type`" from its
    // internal SWC re-print step) — dropped. Rolldown's own ESM output natively supports
    // top-level await (this app's built `<script type="module">` targets modern browsers
    // regardless), so no replacement was needed; `npm run build`'s bundle is the proof.
    wasm(),
  ],
});
