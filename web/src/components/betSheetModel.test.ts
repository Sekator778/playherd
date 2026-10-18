import { describe, expect, it } from "vitest";
import { defaultRiskId, exampleFor, optionFor, riskOptions, signedMove, stakeIsValid } from "./betSheetModel";

describe("riskOptions — the three levels, clamped", () => {
  it("a roomy market gives Safer x3, Normal x10, Bold x25", () => {
    expect(riskOptions(40, null)).toEqual([
      { id: "safer", leverage: 3 },
      { id: "normal", leverage: 10 },
      { id: "bold", leverage: 25 },
    ]);
  });

  it("the market's cap lowers a level and a level the clamp makes identical to a milder one is dropped", () => {
    expect(riskOptions(10, null)).toEqual([
      { id: "safer", leverage: 3 },
      { id: "normal", leverage: 10 },
    ]);
    expect(riskOptions(5, null)).toEqual([
      { id: "safer", leverage: 3 },
      { id: "normal", leverage: 5 },
    ]);
    expect(riskOptions(15, null)).toEqual([
      { id: "safer", leverage: 3 },
      { id: "normal", leverage: 10 },
      { id: "bold", leverage: 15 },
    ]);
  });

  it("the stake's tier lowers them further; the cap above x100 never leaks in (the sheet stops at x25)", () => {
    expect(riskOptions(1200, 8).map((o) => o.leverage)).toEqual([3, 8]);
    expect(riskOptions(1200, null).map((o) => o.leverage)).toEqual([3, 10, 25]);
  });

  it("never offers less than the minimum boost, even on a tiny cap", () => {
    expect(riskOptions(2, null)).toEqual([{ id: "safer", leverage: 2 }]);
  });

  it("a level that was dropped falls back to the nearest milder one", () => {
    const options = riskOptions(10, null);
    expect(optionFor(options, "bold")).toEqual({ id: "normal", leverage: 10 });
    expect(optionFor(options, "safer")).toEqual({ id: "safer", leverage: 3 });
  });
});

describe("defaultRiskId — Normal, except where x10 would be the cap itself", () => {
  it("opens on Normal on a roomy market and on Safer on a market capped at x10 or lower (D2)", () => {
    expect(defaultRiskId(40)).toBe("normal");
    expect(defaultRiskId(11)).toBe("normal");
    expect(defaultRiskId(10)).toBe("safer");
    expect(defaultRiskId(5)).toBe("safer");
  });
});

describe("exampleFor — plain numbers", () => {
  it("1% of a 100 stake at x10 is 10, won in the bet's direction and lost against it", () => {
    const e = exampleFor("DOWN", 100, 10, 0.09);
    expect(e.step).toBe(0.01);
    expect(e.win).toBeCloseTo(10);
    expect(e.lose).toBeCloseTo(-10);
    expect(e.wipeoutSide).toBe("up"); // a DOWN bet is wiped out when the price rises
    expect(exampleFor("UP", 100, 10, 0.09).wipeoutSide).toBe("down");
  });

  it("with the wipeout nearer than 2% the move shrinks to a round value inside half the way, so no example loses more than the bet", () => {
    const e = exampleFor("UP", 100, 100, 0.009);
    expect(e.step).toBe(0.002);
    expect(e.step).toBeLessThanOrEqual(0.009 / 2);
    expect(Math.abs(e.lose)).toBeLessThan(100);
  });
});

describe("exampleFor — net of the fees", () => {
  it("a win is the gain minus the fees, a loss is the loss plus the fees", () => {
    const e = exampleFor("DOWN", 100, 10, 0.09, 1.07);
    expect(e.afterFees).toBe(true);
    expect(e.win).toBeCloseTo(10 - 1.07);
    expect(e.lose).toBeCloseTo(-10 - 1.07);
  });

  it("without the fees (the preview did not name them) the figures stay gross", () => {
    for (const none of [null, 0, Number.NaN]) {
      const e = exampleFor("UP", 100, 10, 0.09, none);
      expect(e.afterFees).toBe(false);
      expect(e.win).toBeCloseTo(10);
      expect(e.lose).toBeCloseTo(-10);
    }
  });

  it("fees bigger than the gain make the 'win' negative — the sheet words that as a loss", () => {
    expect(exampleFor("UP", 100, 3, 0.9, 5).win).toBeLessThan(0);
  });
});

describe("small helpers", () => {
  it("stakeIsValid wants a number of at least the minimum stake", () => {
    expect(stakeIsValid("100")).toBe(true);
    expect(stakeIsValid("99.999")).toBe(false);
    expect(stakeIsValid("")).toBe(false);
    expect(stakeIsValid(".")).toBe(false);
  });

  it("signedMove uses a real minus sign", () => {
    expect(signedMove(-0.01, "1%")).toBe("−1%");
    expect(signedMove(0.086, "8.6%")).toBe("+8.6%");
  });
});
