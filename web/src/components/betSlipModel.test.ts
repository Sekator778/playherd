import { describe, expect, it } from "vitest";
import { LEVERAGE_MAX, LEVERAGE_MIN } from "../api/contract";
import { defaultLeverage } from "./betSlipModel";
import { leverageHeatTier } from "./heat";

describe("defaultLeverage — where a fresh slip opens (first tester pass, D2)", () => {
  it.each([
    [40, 10], // BTC, the indices: x10, as the slip has always opened
    [30, 8],
    [25, 6], // ETH
    [20, 5], // SOL, XRP, NVDA
    [10, 3], // every odds market, DOGE, HYPE
  ])("a market capped at x%i opens at x%i", (cap, expected) => {
    expect(defaultLeverage(cap)).toBe(expected);
  });

  it("is never the cap while the dial has room to go up — odds markets used to open on their Flame setting", () => {
    for (let cap = LEVERAGE_MIN + 1; cap <= LEVERAGE_MAX; cap++) {
      expect(defaultLeverage(cap), `cap x${cap}`).toBeLessThan(cap);
    }
  });

  it("stays on the dial: never below the minimum, never above a cap that is itself the minimum", () => {
    for (let cap = LEVERAGE_MIN; cap <= LEVERAGE_MAX; cap++) {
      const value = defaultLeverage(cap);
      expect(value, `cap x${cap}`).toBeGreaterThanOrEqual(LEVERAGE_MIN);
      expect(value, `cap x${cap}`).toBeLessThanOrEqual(cap);
    }
    expect(defaultLeverage(LEVERAGE_MIN)).toBe(LEVERAGE_MIN);
  });

  it("is the same heat on every market: the Warm zone, as x10 is on a x40 market", () => {
    for (let cap = 6; cap <= LEVERAGE_MAX; cap++) {
      expect(leverageHeatTier(defaultLeverage(cap), cap), `cap x${cap}`).toBe(2);
    }
  });
});
