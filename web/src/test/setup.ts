import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";

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
