import { describe, expect, it } from "vitest";
import { LEVERAGE_MAX, LEVERAGE_MIN } from "../api/contract";
import { CAPPED_MARKET_DEFAULT_LEVERAGE, DEFAULT_LEVERAGE, defaultLeverage } from "./betSlipModel";

describe("defaultLeverage — where a fresh slip opens (first tester pass, D2)", () => {
  // The whole rule: x10, as the slip has always opened, wherever the market's cap lies above it; x3 where x10 would BE the cap
  // (every odds market, DOGE, HYPE — their Flame setting, which a player who just pressed PLACE BET had not chosen); never above the cap.
  it.each([
    [40, 10, "BTC and the indices — as it always opened"],
    [25, 10, "ETH — as it always opened"],
    [20, 10, "SOL, XRP, NVDA — as it always opened"],
    [10, 3, "every odds market, DOGE, HYPE — x10 was their cap"],
    [5, 3, "a market capped lower still"],
    [3, 3, "x3 is the cap itself"],
    [2, 2, "the cap is the minimum of the dial"],
  ])("a market capped at x%i opens at x%i (%s)", (cap, expected) => {
    expect(defaultLeverage(cap)).toBe(expected);
  });

  it("the two numbers are the ones the table says", () => {
    expect(DEFAULT_LEVERAGE).toBe(10);
    expect(CAPPED_MARKET_DEFAULT_LEVERAGE).toBe(3);
  });

  it("unchanged for every market whose cap is above x10: x10, whatever the cap", () => {
    for (let cap = DEFAULT_LEVERAGE + 1; cap <= LEVERAGE_MAX; cap++) {
      expect(defaultLeverage(cap), `cap x${cap}`).toBe(10);
    }
  });

  it("a market capped at x10 or lower opens at x3 — or on its cap, when that is below x3", () => {
    for (let cap = LEVERAGE_MIN; cap <= DEFAULT_LEVERAGE; cap++) {
      expect(defaultLeverage(cap), `cap x${cap}`).toBe(Math.min(cap, 3));
    }
  });

  it("stays on the dial: never below the minimum, never above the cap", () => {
    for (let cap = LEVERAGE_MIN; cap <= LEVERAGE_MAX; cap++) {
      const value = defaultLeverage(cap);
      expect(value, `cap x${cap}`).toBeGreaterThanOrEqual(LEVERAGE_MIN);
      expect(value, `cap x${cap}`).toBeLessThanOrEqual(cap);
    }
  });
});
