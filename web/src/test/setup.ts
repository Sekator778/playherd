import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";

// The app prints every time in UTC and names the zone (`format.ts`), whatever zone the device is in — so the tests need no pin to be
// the same wherever they run (`TZ=America/Los_Angeles npm test` and `TZ=Asia/Kolkata npm test` are the same run). A test about another
// zone sets its own and puts DEFAULT_DISPLAY_TIME_ZONE back.

// Explicit because vitest.config.ts does not enable `test.globals` — Testing Library's
// auto-cleanup only self-registers when it can see a global `afterEach`.
afterEach(() => {
  cleanup();
});

// jsdom has no layout engine and no ResizeObserver — recharts' <ResponsiveContainer> (used by
// CrowdChart) needs one to exist, even though it will only ever observe a 0×0 box in tests.
if (typeof ResizeObserver === "undefined") {
  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (globalThis as any).ResizeObserver = ResizeObserverStub;
}
