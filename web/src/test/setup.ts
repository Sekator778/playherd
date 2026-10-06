import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";
import { cleanup } from "@testing-library/react";
import { setDisplayTimeZone } from "../format";

// The app prints every time in the device's own zone (`format.ts`); the tests pin UTC so that what they assert does not
// depend on where they run. A test about another zone sets its own and puts UTC back.
setDisplayTimeZone("UTC");

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
