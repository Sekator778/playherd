import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import wasm from "vite-plugin-wasm";

// https://vite.dev/config/
export default defineConfig({
  // Relative base path (task item 3) — keeps the built app loadable from a Capacitor
  // `file://`/`capacitor://` webview later without an absolute-path rewrite, and is harmless
  // for the nginx-served Docker build (task item 5), which serves it from `/` anyway.
  base: "./",
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
