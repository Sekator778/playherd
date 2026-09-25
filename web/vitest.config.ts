import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import wasm from "vite-plugin-wasm";

// Separate from vite.config.ts so `vite build`/`vite dev` never pick up test-only config
// (Vitest docs' recommended split), while still sharing the same React plugin. `wasm()` mirrors
// vite.config.ts — the browser test wallet's unit tests (`wallet/testWallet.test.ts`) import
// `chia-wallet-sdk-wasm` too, and need the same transform (see vite.config.ts's comment on why
// there's no `vite-plugin-top-level-await` alongside it).
export default defineConfig({
  plugins: [react(), wasm()],
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    css: true,
  },
});
