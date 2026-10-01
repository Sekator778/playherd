import { describe, expect, it } from "vitest";
import { HEAT_VAR, leverageHeatTier, liquidationLiveCardClass, liquidationZone } from "./heat";

/** The table as it was before heat tiers became relative to a market's cap — live-round.md §1, by absolute leverage. */
function absoluteTier(leverage: number): number {
  if (leverage <= 8) return 1;
  if (leverage <= 15) return 2;
  if (leverage <= 24) return 3;
  if (leverage <= 33) return 4;
  return 5;
}

describe("leverageHeatTier — relative to the market's own maximum (Р-69)", () => {
  it("at the platform maximum (x40) it is exactly the original table, x2 through x40", () => {
    for (let leverage = 2; leverage <= 40; leverage++) {
      expect(leverageHeatTier(leverage, 40), `x${leverage}`).toBe(absoluteTier(leverage));
    }
  });

  it("the default maximum is x40, so a caller that passes none keeps the old behaviour", () => {
    expect(leverageHeatTier(8)).toBe(1);
    expect(leverageHeatTier(9)).toBe(2);
    expect(leverageHeatTier(34)).toBe(5);
  });

  it("on a market capped at x10 the dial uses its whole ramp: all-in is Flame, not 'Warm'", () => {
    expect(leverageHeatTier(2, 10)).toBe(1);
    expect(leverageHeatTier(3, 10)).toBe(2);
    expect(leverageHeatTier(4, 10)).toBe(3);
    expect(leverageHeatTier(6, 10)).toBe(3);
    expect(leverageHeatTier(7, 10)).toBe(4);
    expect(leverageHeatTier(8, 10)).toBe(4);
    expect(leverageHeatTier(9, 10)).toBe(5);
    expect(leverageHeatTier(10, 10)).toBe(5);
    // With the old absolute table x10 was only tier 2 — the cap's hottest setting looked mild.
    expect(absoluteTier(10)).toBe(2);
  });

  it("the cap itself is Flame on every market", () => {
    for (const max of [10, 20, 25, 40]) expect(leverageHeatTier(max, max)).toBe(5);
  });

  it("never cooler as the leverage goes up, on any market", () => {
    for (const max of [10, 20, 25, 40]) {
      let previous = 0;
      for (let leverage = 2; leverage <= max; leverage++) {
        const tier = leverageHeatTier(leverage, max);
        expect(tier).toBeGreaterThanOrEqual(previous);
        previous = tier;
      }
    }
  });

  it("every tier has a colour variable", () => {
    expect(Object.keys(HEAT_VAR)).toEqual(["1", "2", "3", "4", "5"]);
  });
});

describe("liquidation zones", () => {
  it("Safe ≥ 50 %, Watch ≥ 25 %, Close ≥ 10 %, Critical below (live-round.md §2)", () => {
    expect(liquidationZone(1).className).toBe("");
    expect(liquidationZone(0.5).className).toBe("");
    expect(liquidationZone(0.49).className).toBe("liq-bar--watch");
    expect(liquidationZone(0.25).className).toBe("liq-bar--watch");
    expect(liquidationZone(0.24).className).toBe("liq-bar--close");
    expect(liquidationZone(0.1).className).toBe("liq-bar--close");
    expect(liquidationZone(0.09).className).toBe("liq-bar--critical");
    expect(liquidationZone(0).className).toBe("liq-bar--critical");
  });

  it("the card pulse follows the room left as a share of the entry distance — a fresh x40 position is NOT critical", () => {
    // A x40 long starts ~2.4 % from liquidation: as a share of the distance it had, it has all of it left.
    expect(liquidationLiveCardClass(1)).toBe("");
    expect(liquidationLiveCardClass(0.4)).toBe("card--pulse-watch");
    expect(liquidationLiveCardClass(0.2)).toBe("card--pulse-close");
    expect(liquidationLiveCardClass(0.05)).toBe("card--pulse-critical");
  });

  it("clamps what it is given", () => {
    expect(liquidationLiveCardClass(7)).toBe("");
    expect(liquidationLiveCardClass(-3)).toBe("card--pulse-critical");
  });
});
